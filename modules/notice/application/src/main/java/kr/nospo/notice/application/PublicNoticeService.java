package kr.nospo.notice.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.nospo.notice.contract.NoticeDetail;
import kr.nospo.notice.contract.NoticeSummary;
import kr.nospo.notice.contract.NoticeSort;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;

@Service
public class PublicNoticeService {
    private final NoticeStore store;
    private final NoticeCursor cursors;
    private final Clock clock;

    PublicNoticeService(NoticeStore store, NoticeCursor cursors, Clock clock) {
        this.store = store;
        this.cursors = cursors;
        this.clock = clock;
    }

    public record Page(List<NoticeSummary> items, String nextCursor, boolean hasNext) {}

    public Page list(String cursor, int limit, NoticeSort sort) {
        if (limit < 1 || limit > 100) throw new ApiException(422, "VALIDATION_ERROR");

        Instant now = now();
        var position = cursor == null ? new NoticeCursor.PagePosition(now, null) : cursors.decode(cursor, sort);
        Instant asOf = position.asOf().isBefore(now) ? position.asOf() : now;
        var rows = store.findPublished(asOf, position.last(), limit + 1, sort);
        if (rows.size() <= limit) return new Page(List.copyOf(rows), null, false);

        var items = List.copyOf(rows.subList(0, limit));
        NoticeSummary last = items.getLast();
        var boundary = new NoticeStore.Position(last.pinned(), last.publishedAt(), last.noticeId());
        return new Page(items, cursors.encode(new NoticeCursor.PagePosition(asOf, boundary), sort), true);
    }

    public NoticeDetail detail(UUID noticeId) {
        return store.findPublished(noticeId, now())
                .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
