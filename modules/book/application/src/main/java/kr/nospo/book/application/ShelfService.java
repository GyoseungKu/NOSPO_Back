package kr.nospo.book.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.nospo.book.contract.*;
import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.member.contract.Members;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShelfService implements ReadingProgressCatalog {
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);

    private final ShelfStore shelf;
    private final BookCatalog catalog;
    private final BookStore books;
    private final ShelfEditionRegistration editions;
    private final Members members;
    private final SignupIdentity identity;
    private final Clock clock;
    private final BookCursor cursors;
    private final ProgressTags progressTags;

    public record ProgressUpdate(Snapshot snapshot, int addedUnits, UUID recordId) {}

    ShelfService(ShelfStore shelf, BookCatalog catalog, BookStore books, ShelfEditionRegistration editions,
            Members members, SignupIdentity identity, Clock clock, BookCursor cursors, ProgressTags progressTags) {
        this.shelf = shelf;
        this.catalog = catalog;
        this.books = books;
        this.editions = editions;
        this.members = members;
        this.identity = identity;
        this.clock = clock;
        this.cursors = cursors;
        this.progressTags = progressTags;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ShelfItem> list(UUID owner, BookType type, ShelfSort sort, String cursor, int limit) {
        BookService.checkLimit(limit);
        String scope = "shelf:" + owner + ":" + type + ":" + sort + ":" + limit;
        String decoded = cursors.decode(scope, cursor);
        var expiresAt = clock.instant().plus(CURSOR_TTL);
        ShelfStore.Boundary after = null;
        if (decoded != null) {
            String[] values = decoded.split("\\n", 3);
            expiresAt = Instant.parse(values[0]);
            if (!expiresAt.isAfter(clock.instant())) throw new ApiException(400, "INVALID_CURSOR");
            after = new ShelfStore.Boundary(UUID.fromString(values[1]), values[2]);
        }

        var rows = shelf.list(owner, type, sort, after, limit + 1);
        var visible = rows.subList(0, Math.min(rows.size(), limit));
        var editions = books.findAll(visible.stream().map(ShelfStore.Entry::bookId).toList()).stream()
                .collect(Collectors.toMap(Book::bookId, Function.identity()));
        var items = visible.stream().map(row -> item(editions.get(row.bookId()), row)).toList();
        if (rows.size() <= limit) return Page.of(items, null);
        var last = visible.getLast();
        String key = switch (sort) {
            case TITLE -> last.title();
            case RECENT_ADDED -> last.addedAt().toString();
            case RECENT_ACTIVITY -> (last.progress().lastProgressAt() == null
                    ? last.addedAt() : last.progress().lastProgressAt()).toString();
        };
        return Page.of(items, cursors.encode(scope, expiresAt + "\n" + last.bookId() + "\n" + key));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShelfItem detail(UUID owner, UUID bookId) {
        var row = shelf.find(owner, bookId).orElseThrow(ShelfService::notFound);
        return item(catalog.find(bookId).orElseThrow(ShelfService::notFound), row);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ShelfItem> current(UUID owner) {
        return shelf.current(owner).map(row -> item(catalog.find(row.bookId()).orElseThrow(), row));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShelfItem add(UUID owner, UUID session, UUID requestedId, Position position) {
        lockOwner(owner, session);
        var book = editions.resolve(requestedId);
        UUID bookId = book.bookId();
        if (shelf.find(owner, bookId).isPresent()) throw new ApiException(409, "SHELF_ALREADY_EXISTS");
        validate(book, position);
        var previous = shelf.progress(owner, bookId).orElse(null);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int current = position.value();
        if (previous == null) {
            shelf.insertProgress(owner, bookId, new ShelfStore.Progress(current, current, null, 0));
        } else {
            int highest = Math.max(current, previous.highest());
            Instant lastActivity = current == previous.current() ? previous.lastProgressAt() : now;
            shelf.updateProgress(owner, bookId,
                    new ShelfStore.Progress(current, highest, lastActivity, previous.revision() + 1));
            if (highest > previous.highest()) {
                shelf.record(owner, bookId, previous.current(), current, highest - previous.highest(), now);
            }
        }
        shelf.attach(owner, bookId, now);
        return item(book, shelf.find(owner, bookId).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(UUID owner, UUID session, UUID bookId) {
        lockOwner(owner, session);
        if (!shelf.detach(owner, bookId)) throw notFound();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Snapshot> activeProgress(UUID owner, UUID bookId) {
        return shelf.find(owner, bookId).flatMap(row -> catalog.find(bookId).map(book -> snapshot(book, row)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProgressUpdate changeProgress(UUID owner, UUID session, UUID bookId, String ifMatch, Position position) {
        lockOwner(owner, session);
        var row = shelf.find(owner, bookId).orElseThrow(ShelfService::notFound);
        var previous = row.progress();
        progressTags.check(ifMatch, owner, bookId, previous.revision());
        var book = catalog.find(bookId).orElseThrow(ShelfService::notFound);
        validate(book, position);
        int current = position.value();
        if (current == previous.current()) return new ProgressUpdate(snapshot(book, row), 0, null);

        int added = Math.max(0, current - previous.highest());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        shelf.updateProgress(owner, bookId, new ShelfStore.Progress(current,
                Math.max(previous.highest(), current), now, previous.revision() + 1));
        UUID record = shelf.record(owner, bookId, previous.current(), current, added, now);
        var updated = shelf.find(owner, bookId).orElseThrow();
        return new ProgressUpdate(snapshot(book, updated), added, record);
    }

    private static Snapshot snapshot(Book book, ShelfStore.Entry row) {
        var progress = row.progress();
        Instant updatedAt = progress.lastProgressAt();
        if (updatedAt == null || updatedAt.isBefore(row.addedAt())) updatedAt = row.addedAt();
        var unit = book.positionUnit();
        return new Snapshot(new Progress(book.bookId(), new Position(unit, progress.current()),
                new Position(unit, progress.highest()), row.context(), percent(book, progress.current()), updatedAt),
                progress.revision());
    }

    private void lockOwner(UUID owner, UUID session) {
        // Match the lifecycle lock order so withdrawal cannot race past a shelf write.
        members.lockActive(owner);
        if (!identity.sessionActive(owner, session, clock.instant())) {
            throw new ApiException(401, "INVALID_CREDENTIALS");
        }
    }

    private static void validate(Book book, Position position) {
        if (position == null || position.unit() != book.positionUnit() || position.value() < 0
                || (book.totalUnits() != null && position.value() > book.totalUnits())) {
            throw new ApiException(422, "INVALID_POSITION");
        }
    }

    private static ShelfItem item(Book book, ShelfStore.Entry row) {
        var progress = row.progress();
        var unit = book.positionUnit();
        return new ShelfItem(book.summary(), new Position(unit, progress.current()), new Position(unit, progress.highest()),
                row.context(), percent(book, progress.current()), row.addedAt(), progress.lastProgressAt());
    }

    private static BigDecimal percent(Book book, int current) {
        if (book.totalUnits() == null) return null;
        return book.totalUnits() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(current)
                .movePointRight(2).divide(BigDecimal.valueOf(book.totalUnits()), 2, RoundingMode.HALF_UP);
    }

    private static ApiException notFound() {
        return new ApiException(404, "RESOURCE_NOT_FOUND");
    }
}
