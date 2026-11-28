package kr.nospo.book.contract;

import java.util.Optional;
import java.util.UUID;

/** Read access for modules that reference Book-owned edition IDs. */
public interface BookCatalog {
    Optional<Book> find(UUID bookId);
}
