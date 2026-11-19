package kr.nospo.notice.web;

import java.util.UUID;
import kr.nospo.notice.contract.NoticeSort;
import kr.nospo.support.ApiException;

final class NoticeRequest {
    private NoticeRequest() {}

    static int limit(String limit) {
        if (limit == null) return 20;
        if (!limit.matches("[+-]?[0-9]+")) throw new ApiException(400, "INVALID_REQUEST");
        try {
            return Integer.parseInt(limit);
        } catch (NumberFormatException outOfRange) {
            throw new ApiException(422, "VALIDATION_ERROR");
        }
    }

    static NoticeSort sort(String sort) {
        if (sort == null || "latest".equals(sort)) return NoticeSort.LATEST;
        if ("oldest".equals(sort)) return NoticeSort.OLDEST;
        throw new ApiException(400, "INVALID_REQUEST");
    }

    static UUID id(String noticeId) {
        UUID id;
        try {
            id = UUID.fromString(noticeId);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(400, "INVALID_REQUEST");
        }
        if (!id.toString().equalsIgnoreCase(noticeId)) throw new ApiException(400, "INVALID_REQUEST");
        return id;
    }
}
