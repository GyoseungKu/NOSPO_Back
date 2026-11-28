package kr.nospo.book.application;

import java.util.List;
import java.util.Optional;
import kr.nospo.book.contract.Book;
import kr.nospo.book.contract.BookType;
import kr.nospo.book.contract.PositionUnit;
import org.springframework.stereotype.Component;

@Component
class KakaoShelfStoragePolicy {
    static final String SOURCE = "KAKAO_DAUM";

    // Applies only to a verified search result selected through shelf registration.
    Optional<EditionMetadata> selectedEdition(Book cached) {
        String isbn = BookIsbn.canonical(cached.isbn());
        String title = cached.title() == null ? "" : BookNames.query(cached.title());
        if (!SOURCE.equals(cached.source()) || cached.bookType() != BookType.BOOK
                || cached.positionUnit() != PositionUnit.PAGE || isbn == null || !isbn.equals(cached.sourceItemId())
                || title.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EditionMetadata(isbn, title, List.of(), null, null, isbn, null, null, null, null));
    }
}
