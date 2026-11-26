package kr.nospo.book.application;

import java.util.List;
import java.util.UUID;
import kr.nospo.book.contract.Book;
import kr.nospo.book.contract.BookCatalog;
import kr.nospo.book.contract.BookType;
import kr.nospo.book.contract.PositionUnit;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShelfEditionRegistration {
    private static final String SOURCE = "KAKAO_DAUM";

    private final BookCatalog catalog;
    private final BookStore books;
    private final TransientBookCatalog transientBooks;
    private final KakaoShelfStoragePolicy policy;

    ShelfEditionRegistration(BookCatalog catalog, BookStore books, TransientBookCatalog transientBooks,
            KakaoShelfStoragePolicy policy) {
        this.catalog = catalog;
        this.books = books;
        this.transientBooks = transientBooks;
        this.policy = policy;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Book resolve(UUID requestedId) {
        return catalog.find(requestedId).orElseGet(() -> resolveCached(requestedId));
    }

    private Book resolveCached(UUID requestedId) {
        Book cached = transientBooks.find(requestedId).orElseThrow(ShelfEditionRegistration::notFound);
        String isbn = BookIsbn.canonical(cached.isbn());
        String title = cached.title() == null ? "" : BookNames.query(cached.title());
        if (!SOURCE.equals(cached.source()) || cached.bookType() != BookType.BOOK
                || cached.positionUnit() != PositionUnit.PAGE || isbn == null || !isbn.equals(cached.sourceItemId())
                || title.isEmpty()) {
            throw notFound();
        }

        var existing = books.findBySource(SOURCE, isbn);
        if (existing.isPresent()) return matching(existing.get(), isbn);

        if (!policy.allowsSharedEditionCreation()) throw notFound();

        // Only the title required by V12 and the source/ISBN identity are candidates for this path.
        var minimal = new EditionMetadata(isbn, title, List.of(), null, null, isbn, null, null, null, null);
        UUID permanentId = books.storeEdition(null, BookType.BOOK, SOURCE, minimal);
        return matching(catalog.find(permanentId).orElseThrow(ShelfEditionRegistration::notFound), isbn);
    }

    private static Book matching(Book book, String isbn) {
        if (!SOURCE.equals(book.source()) || !isbn.equals(book.sourceItemId()) || book.bookType() != BookType.BOOK
                || (book.isbn() != null && !isbn.equals(BookIsbn.canonical(book.isbn())))) {
            throw notFound();
        }
        return book;
    }

    private static ApiException notFound() {
        return new ApiException(404, "RESOURCE_NOT_FOUND");
    }
}
