package kr.nospo.book.integration;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kr.nospo.book.application.EditionMetadata;
import tools.jackson.databind.JsonNode;

record KakaoBookResponse(List<EditionMetadata> books, boolean lastPage) {
    static KakaoBookResponse parse(JsonNode root, int pageSize) {
        var meta = root.path("meta");
        var end = meta.path("is_end");
        var documents = root.path("documents");
        long total = nonNegativeCount(meta.path("total_count"));
        long pageable = nonNegativeCount(meta.path("pageable_count"));
        if (pageable > total || !end.isBoolean() || !documents.isArray() || documents.size() > pageSize)
            throw new IllegalArgumentException("Invalid book search envelope");
        var books = new ArrayList<EditionMetadata>();
        for (JsonNode row : documents) {
            if (!row.isObject()) throw new IllegalArgumentException("Invalid book search document");
            String isbn = KakaoIsbn.canonical(field(row, "isbn"));
            if (isbn == null) continue;
            books.add(new EditionMetadata(isbn, field(row, "title"), authors(row.path("authors")),
                    field(row, "publisher"), null, isbn, cover(field(row, "thumbnail")),
                    field(row, "contents"), null, date(field(row, "datetime"))));
        }
        return new KakaoBookResponse(List.copyOf(books), end.asBoolean());
    }

    private static long nonNegativeCount(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
            throw new IllegalArgumentException("Invalid book search count");
        return value.asLong();
    }

    private static String field(JsonNode row, String name) {
        var value = row.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException("Invalid book search field");
        String text = value.asString().strip();
        return text.isBlank() ? null : text;
    }

    private static List<String> authors(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("Invalid book search authors");
        var names = new ArrayList<String>();
        for (var name : node) {
            if (!name.isString()) throw new IllegalArgumentException("Invalid book search author");
            if (!name.asString().isBlank()) names.add(name.asString().strip());
        }
        return names;
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid publication date");
        }
    }

    private static String cover(String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                return value;
            }
        } catch (IllegalArgumentException invalid) {
            // Treat unsafe or unparseable display URLs as absent.
        }
        return null;
    }
}
