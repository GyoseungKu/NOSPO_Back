package kr.nospo.book.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Book(UUID bookId, @Schema(nullable = true) UUID workId, BookType bookType, String title,
        @Schema(description = "Empty when author information is unavailable") List<String> authors,
        @Schema(nullable = true) String publisher, @Schema(nullable = true) String editionLabel,
        @Schema(nullable = true) String isbn, @Schema(nullable = true) String coverUrl,
        @Schema(nullable = true) String description, String source, String sourceItemId, PositionUnit positionUnit,
        @Schema(nullable = true, description = "Null when total length is unknown") Integer totalUnits,
        @Schema(nullable = true) LocalDate publicationDate) {
    public BookSummary summary() {
        return new BookSummary(bookId, workId, bookType, title, authors, publisher, editionLabel, isbn, coverUrl);
    }
}
