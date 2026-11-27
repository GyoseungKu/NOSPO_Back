package kr.nospo.book.web;

import java.util.UUID;
import kr.nospo.book.application.BookNames;
import kr.nospo.book.contract.Position;
import kr.nospo.book.contract.PositionUnit;
import kr.nospo.support.ApiException;
import tools.jackson.databind.JsonNode;

final class BookRequest {
    private BookRequest() {}

    static String query(String raw) {
        String query = BookNames.query(raw);
        if (query.isEmpty() || raw.codePointCount(0, raw.length()) > 100
                || query.codePointCount(0, query.length()) > 100)
            throw new ApiException(422, "VALIDATION_ERROR");
        return query;
    }

    static int limit(String raw) {
        if (raw == null) return 20;
        if (!raw.matches("[+-]?[0-9]+")) throw invalid();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException overflow) {
            throw new ApiException(422, "VALIDATION_ERROR");
        }
    }

    static UUID id(String raw) {
        try {
            UUID value = UUID.fromString(raw);
            if (value.toString().equalsIgnoreCase(raw)) return value;
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
        throw invalid();
    }

    static Position position(JsonNode position) {
        var failure = new ApiException(422, "INVALID_POSITION");
        if (position == null || !position.isObject()) throw failure;
        var value = position.path("value");
        var unit = position.path("unit");
        if (!value.isIntegralNumber() || !value.canConvertToInt() || !unit.isString()) throw failure;
        try {
            return new Position(PositionUnit.valueOf(unit.asString()), value.asInt());
        } catch (IllegalArgumentException invalidUnit) {
            throw failure;
        }
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_REQUEST");
    }
}
