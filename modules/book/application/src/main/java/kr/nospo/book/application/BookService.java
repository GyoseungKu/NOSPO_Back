package kr.nospo.book.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.book.contract.*;
import kr.nospo.support.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookService implements BookCatalog {
    private final BookStore store;
    private final BookCursor cursors;
    private final ObjectProvider<BookSearchProvider> providers;
    private final TransientBookCatalog transientBooks;
    private final TransactionTemplate transactions;

    BookService(BookStore store, BookCursor cursors, ObjectProvider<BookSearchProvider> providers,
            TransientBookCatalog transientBooks, org.springframework.transaction.PlatformTransactionManager manager) {
        this.store = store;
        this.cursors = cursors;
        this.providers = providers;
        this.transientBooks = transientBooks;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Optional<Book> find(UUID bookId) {
        return store.find(bookId);
    }

    public Book detail(UUID bookId) {
        return find(bookId).or(() -> transientBooks.find(bookId))
                .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    }

    public Page<Episode> structure(UUID bookId, String cursor, int limit) {
        checkLimit(limit);
        String scope = "structure:" + bookId;
        String position = cursors.decode(scope, cursor);
        if (detail(bookId).bookType() != BookType.WEB_NOVEL) {
            throw new ApiException(409, "BOOK_TYPE_MISMATCH");
        }
        var rows = store.episodes(bookId, position == null ? 0 : Integer.parseInt(position), limit + 1);
        if (rows.size() <= limit) return Page.of(rows, null);
        return Page.of(rows.subList(0, limit), cursors.encode(scope, Integer.toString(rows.get(limit - 1).sequence())));
    }

    public Page<BookSummary> search(String query, SearchType type, BookType bookType, String cursor, int limit) {
        checkLimit(limit);
        String scope = "search:" + type + ":" + bookType + ":" + query;
        String state = cursors.decode(scope, cursor);
        if (bookType == BookType.WEB_NOVEL || (state != null && state.startsWith("novel:"))) {
            String last = state == null ? "" : state.substring("novel:".length());
            UUID after = last.isEmpty() ? null : UUID.fromString(last);
            return novels(query, type, after, scope, limit, List.of());
        }
        var provider = providers.getIfAvailable();
        if (provider == null) throw providerUnavailable();
        String source = provider.source();
        if (source == null || !source.matches("[A-Z0-9_]{1,64}") || source.equals(WebNovelImport.SOURCE)) {
            throw providerUnavailable();
        }
        String prefix = "provider:" + source + ":";
        if (state != null && !state.startsWith(prefix)) throw new ApiException(400, "INVALID_CURSOR");
        String continuation = state == null ? null : state.substring(prefix.length());
        BookSearchProvider.Results results;
        try {
            results = provider.search(query, type, continuation, limit);
        } catch (BookSearchProvider.InvalidContinuation invalid) {
            throw new ApiException(400, "INVALID_CURSOR");
        } catch (BookSearchProvider.Unavailable | IllegalArgumentException failure) {
            throw providerUnavailable();
        }
        if (results == null || results.items().size() > limit) throw providerUnavailable();
        String next = results.nextContinuation();
        if (next != null && (next.isBlank() || next.getBytes(UTF_8).length > BookCursor.MAX_STATE_BYTES
                || Objects.equals(next, continuation))) {
            throw providerUnavailable();
        }

        var items = provider.transientResults()
                ? results.items().stream().map(metadata -> transientBooks.publish(source, metadata)).map(Book::summary).toList()
                : transactions.execute(status -> results.items().stream()
                .map(metadata -> store.storeEdition(null, BookType.BOOK, source, metadata))
                .distinct().map(this::detail).map(Book::summary).toList());
        if (next != null) return Page.of(items, cursors.encode(scope, prefix + next));
        if (bookType == BookType.BOOK) return Page.of(items, null);
        return novels(query, type, null, scope, limit, items);
    }

    private Page<BookSummary> novels(String query, SearchType type, UUID after, String scope, int limit,
            List<BookSummary> prefix) {
        int remaining = limit - prefix.size();
        var rows = store.searchNovels(query, type, after, remaining + 1);
        var items = new ArrayList<>(prefix);
        rows.stream().limit(remaining).map(Book::summary).forEach(items::add);
        if (rows.size() <= remaining) return Page.of(items, null);
        String last = remaining == 0 ? "" : rows.get(remaining - 1).bookId().toString();
        return Page.of(items, cursors.encode(scope, "novel:" + last));
    }

    static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(422, "VALIDATION_ERROR");
    }

    private static ApiException providerUnavailable() {
        return new ApiException(503, "BOOK_PROVIDER_UNAVAILABLE");
    }
}
