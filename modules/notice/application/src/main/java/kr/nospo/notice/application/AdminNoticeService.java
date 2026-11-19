package kr.nospo.notice.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.nospo.notice.application.NoticeStore.Changes;
import kr.nospo.notice.contract.AdminNotice;
import kr.nospo.notice.contract.NoticeSort;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminNoticeService {
    private final NoticeStore store;
    private final AdminNoticeCursor cursors;
    private final Clock clock;

    AdminNoticeService(NoticeStore store, AdminNoticeCursor cursors, Clock clock) {
        this.store = store;
        this.cursors = cursors;
        this.clock = clock;
    }

    public record Page(List<AdminNotice> items, String nextCursor, boolean hasNext) {}

    public Page list(String cursor, int limit, NoticeSort sort) {
        if (limit < 1 || limit > 100) throw new ApiException(422, "VALIDATION_ERROR");
        var rows = store.findAll(cursor == null ? null : cursors.decode(cursor, sort), limit + 1, sort);
        boolean hasNext = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new Page(page.stream().map(NoticeStore.AdminRow::notice).toList(),
                hasNext ? cursors.encode(page.getLast().position(), sort) : null, hasNext);
    }

    public AdminNotice detail(UUID noticeId) {
        return store.findAdmin(noticeId).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    }

    @Transactional
    public AdminNotice create(Changes input) {
        UUID noticeId = UUID.randomUUID();
        store.insert(noticeId, input, now());
        return detail(noticeId);
    }

    @Transactional
    public AdminNotice patch(UUID noticeId, Changes input) {
        store.update(noticeId, input, now());
        return detail(noticeId);
    }

    @Transactional
    public void delete(UUID noticeId) {
        if (!store.markDeleted(noticeId, now())) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
