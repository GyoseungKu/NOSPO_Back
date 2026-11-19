package kr.nospo.notice.web;

import java.util.List;
import kr.nospo.notice.application.NoticeStore.Changes;
import kr.nospo.support.ApiException;
import tools.jackson.databind.JsonNode;

final class AdminNoticeInput {
    private static final List<String> FIELDS = List.of("title", "summary", "content", "pinned", "published");

    private AdminNoticeInput() {}

    static Changes parse(JsonNode input, boolean create) {
        if (!input.isObject()) throw new ApiException(400, "INVALID_REQUEST");
        long known = FIELDS.stream().filter(input::has).count();
        if (known != input.size()) throw new ApiException(400, "INVALID_REQUEST");
        if (create ? known != FIELDS.size() : known == 0) throw invalid();
        return new Changes(text(input.get("title"), 200, true), text(input.get("summary"), 500, false),
                text(input.get("content"), 50000, true), flag(input.get("pinned")), flag(input.get("published")));
    }

    private static String text(JsonNode value, int max, boolean nonBlank) {
        if (value == null) return null;
        if (!value.isTextual()) throw invalid();
        String text = value.asString();
        if ((nonBlank && text.isBlank()) || text.codePointCount(0, text.length()) > max) throw invalid();
        return text;
    }

    private static Boolean flag(JsonNode value) {
        if (value == null) return null;
        if (!value.isBoolean()) throw invalid();
        return value.asBoolean();
    }

    private static ApiException invalid() {
        return new ApiException(422, "VALIDATION_ERROR");
    }
}
