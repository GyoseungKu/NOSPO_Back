package kr.nospo.book.application;

import java.time.LocalDate;
import java.util.List;

public record EditionMetadata(String sourceItemId, String title, List<String> authors, String publisher,
        String editionLabel, String isbn, String coverUrl, String description, Integer totalUnits,
        LocalDate publicationDate) {

    public EditionMetadata {
        requireText(sourceItemId, 768);
        if (sourceItemId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 768)
            throw new IllegalArgumentException("Edition key exceeds storage limit");
        requireText(title, 512);
        authors = List.copyOf(authors);
        authors.forEach(author -> requireText(author, 512));
        checkLength(publisher, 512);
        checkLength(editionLabel, 255);
        checkLength(isbn, 32);
        checkLength(coverUrl, 2048);
        if (description != null && description.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
            throw new IllegalArgumentException("Description exceeds storage limit");
        if (totalUnits != null && totalUnits < 0)
            throw new IllegalArgumentException("Invalid total units");
    }

    static void requireText(String value, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required metadata is missing");
        checkLength(value, max);
    }

    static void checkLength(String value, int max) {
        if (value != null && value.codePointCount(0, value.length()) > max)
            throw new IllegalArgumentException("Metadata exceeds storage limit");
    }
}
