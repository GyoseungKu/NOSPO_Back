package kr.nospo.book.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Book(UUID bookId, UUID workId, BookType bookType, String title, List<String> authors,
        String publisher, String editionLabel, String isbn, String coverUrl, String description,
        String source, String sourceItemId, PositionUnit positionUnit, Integer totalUnits, LocalDate publicationDate) {
    public BookSummary summary() {
        return new BookSummary(bookId, workId, bookType, title, authors, publisher, editionLabel, isbn, coverUrl);
    }
}
