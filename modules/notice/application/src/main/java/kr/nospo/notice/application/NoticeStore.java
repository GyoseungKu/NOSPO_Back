package kr.nospo.notice.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.notice.contract.AdminNotice;
import kr.nospo.notice.contract.NoticeDetail;
import kr.nospo.notice.contract.NoticeSort;
import kr.nospo.notice.contract.NoticeSummary;

public interface NoticeStore {
    Optional<NoticeDetail> findPublished(UUID noticeId, Instant asOf);
    List<NoticeSummary> findPublished(Instant asOf, Position after, int limit, NoticeSort sort);
    List<AdminRow> findAll(AdminPosition after, int limit, NoticeSort sort);
    Optional<AdminNotice> findAdmin(UUID noticeId);

    void insert(UUID noticeId, Changes input, Instant now);
    void update(UUID noticeId, Changes input, Instant now);
    boolean markDeleted(UUID noticeId, Instant now);

    record Changes(String title, String summary, String content, Boolean pinned, Boolean published) {}
    record AdminPosition(Instant createdAt, UUID noticeId) {}
    record AdminRow(AdminNotice notice, AdminPosition position) {}
    record Position(boolean pinned, Instant publishedAt, UUID noticeId) {}
}
