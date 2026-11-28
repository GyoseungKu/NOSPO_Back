package kr.nospo.nospo_backend.wiki;

import java.util.Optional;
import java.util.UUID;

import kr.nospo.book.contract.BookCatalog;
import kr.nospo.book.contract.ReadingProgressCatalog;
import kr.nospo.wiki.application.WikiBookAccess;
import org.springframework.stereotype.Component;

@Component
class ReadingWikiBookAccess implements WikiBookAccess {
    private final BookCatalog books;
    private final ReadingProgressCatalog progress;

    ReadingWikiBookAccess(BookCatalog books, ReadingProgressCatalog progress) {
        this.books = books;
        this.progress = progress;
    }

    public Optional<Edition> find(UUID book) {
        return books.find(book).map(edition -> new Edition(Unit.valueOf(edition.positionUnit().name()), edition.totalUnits()));
    }

    public Optional<Position> currentPosition(UUID member, UUID book) {
        return progress.activeProgress(member, book).map(snapshot -> {
            var position = snapshot.progress().currentPosition();
            return new Position(Unit.valueOf(position.unit().name()), position.value());
        });
    }
}
