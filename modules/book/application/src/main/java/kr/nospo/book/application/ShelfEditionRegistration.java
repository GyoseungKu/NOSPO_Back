package kr.nospo.book.application;

import static kr.nospo.book.application.KakaoShelfStoragePolicy.SOURCE;

import java.util.UUID;
import kr.nospo.book.contract.Book;
import kr.nospo.book.contract.BookCatalog;
import kr.nospo.book.contract.BookType;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShelfEditionRegistration {
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
        var minimal = transientBooks.findSelected(requestedId).flatMap(policy::selectedEdition)
                .orElseThrow(ShelfEditionRegistration::notFound);
        String isbn = minimal.isbn();

        var existing = books.findBySource(SOURCE, isbn);
        if (existing.isPresent()) return matching(existing.get(), isbn);

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
