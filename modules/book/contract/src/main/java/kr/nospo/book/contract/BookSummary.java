package kr.nospo.book.contract;

import java.util.List;
import java.util.UUID;

public record BookSummary(UUID bookId, UUID workId, BookType bookType, String title,
        List<String> authors, String publisher, String editionLabel, String isbn, String coverUrl) {}
