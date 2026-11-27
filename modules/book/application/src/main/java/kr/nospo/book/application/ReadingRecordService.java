package kr.nospo.book.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.nospo.book.contract.Page;
import kr.nospo.book.contract.ReadingRecord;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;

@Service
public class ReadingRecordService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);
    private final ShelfStore shelf;
    private final BookCursor cursors;
    private final Clock clock;

    ReadingRecordService(ShelfStore shelf, BookCursor cursors, Clock clock) {
        this.shelf = shelf;
        this.cursors = cursors;
        this.clock = clock;
    }

    public Page<ReadingRecord> list(UUID owner, LocalDate from, LocalDate to, UUID bookId, String cursor, int limit) {
        BookService.checkLimit(limit);
        if (from != null && to != null) {
            long days = ChronoUnit.DAYS.between(from, to);
            if (days < 0 || days >= 366) throw new ApiException(422, "VALIDATION_ERROR");
        }
        String scope = "reading-records:" + owner + ":" + from + ":" + to + ":" + bookId + ":" + limit;
        String decoded = cursors.decode(scope, cursor);
        Instant expires = clock.instant().plus(CURSOR_TTL);
        ShelfStore.RecordBoundary after = null;
        if (decoded != null) {
            var parts = decoded.split("\\|", -1);
            expires = Instant.parse(parts[0]);
            if (!expires.isAfter(clock.instant())) throw new ApiException(400, "INVALID_CURSOR");
            after = new ShelfStore.RecordBoundary(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        }
        Instant start = from == null ? null : from.atStartOfDay(SEOUL).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(SEOUL).toInstant();
        var rows = shelf.records(owner, bookId, start, end, after, limit + 1);
        if (rows.size() <= limit) return Page.of(rows, null);
        var last = rows.get(limit - 1);
        return Page.of(rows.subList(0, limit), cursors.encode(scope,
                expires + "|" + last.recordedAt() + "|" + last.recordId()));
    }
}
