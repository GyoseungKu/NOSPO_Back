package kr.nospo.book.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.book.contract.Book;
import kr.nospo.book.contract.BookType;
import kr.nospo.book.contract.PositionUnit;
import kr.nospo.support.Secrets;
import kr.nospo.support.SqlIds;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TransientBookCatalog {
    private final Secrets secrets;
    private final BookSearchCache<UUID, Book> cache;

    TransientBookCatalog(Secrets secrets, Clock clock) {
        this.secrets = secrets;
        cache = new BookSearchCache<>(clock, Duration.ofMinutes(5), 1024);
    }

    Book publish(String source, EditionMetadata edition) {
        byte[] key = Arrays.copyOf(secrets.fingerprint("book:transient:v1:" + source + ":" + edition.sourceItemId()), 16);
        key[6] = (byte) ((key[6] & 0x0f) | 0x80);
        key[8] = (byte) ((key[8] & 0x3f) | 0x80);
        UUID bookId = SqlIds.uuid(key);
        var book = new Book(bookId, null, BookType.BOOK, edition.title(), edition.authors(), edition.publisher(),
                edition.editionLabel(), edition.isbn(), edition.coverUrl(), edition.description(),
                source, edition.sourceItemId(), PositionUnit.PAGE, edition.totalUnits(), edition.publicationDate());
        cache.put(bookId, book);
        return book;
    }

    Optional<Book> find(UUID bookId) {
        return Optional.ofNullable(cache.get(bookId));
    }

    @Scheduled(fixedDelay = 60_000)
    void prune() {
        cache.prune();
    }
}
