package kr.nospo.notice.contract;

import java.time.Instant;
import java.util.UUID;

public record NoticeDetail(
        UUID noticeId, String title, String summary, boolean pinned,
        Instant publishedAt, Instant updatedAt, String content) {}
