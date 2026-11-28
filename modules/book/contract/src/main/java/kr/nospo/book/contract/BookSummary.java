package kr.nospo.book.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record BookSummary(UUID bookId, @Schema(nullable = true) UUID workId, BookType bookType, String title,
        @Schema(description = "Empty when author information is unavailable") List<String> authors,
        @Schema(nullable = true) String publisher, @Schema(nullable = true) String editionLabel,
        @Schema(nullable = true) String isbn, @Schema(nullable = true) String coverUrl) {}
