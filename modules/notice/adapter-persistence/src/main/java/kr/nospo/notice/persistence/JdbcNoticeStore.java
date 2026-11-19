package kr.nospo.notice.persistence;

import static kr.nospo.support.SqlIds.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.notice.application.NoticeStore;
import kr.nospo.notice.contract.AdminNotice;
import kr.nospo.notice.contract.NoticeDetail;
import kr.nospo.notice.contract.NoticeSort;
import kr.nospo.notice.contract.NoticeSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNoticeStore implements NoticeStore {
    private static final String SUMMARY = "notice_id, title, summary, pinned, published_at, updated_at";
    private static final String LIVE = "deleted_at IS NULL";
    private static final String PUBLIC = " FROM notice WHERE " + LIVE + " AND published = TRUE AND published_at <= ?";
    private static final String ADMIN = "SELECT " + SUMMARY + ", content, published, created_at FROM notice WHERE " + LIVE;

    private final JdbcTemplate db;

    public JdbcNoticeStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public List<NoticeSummary> findPublished(Instant asOf, Position after, int limit, NoticeSort sort) {
        String query = "SELECT " + SUMMARY + PUBLIC;
        String direction = direction(sort);
        String order = " ORDER BY pinned DESC, published_at " + direction + ", notice_id " + direction + " LIMIT ?";
        if (after == null) {
            return db.query(query + order, JdbcNoticeStore::summary, utc(asOf), limit);
        }
        var publishedAt = utc(after.publishedAt());
        String comparison = comparison(sort);
        String boundary = """
                 AND (pinned < ?
                   OR (pinned = ? AND (published_at %1$s ? OR (published_at = ? AND notice_id %1$s ?))))
                """.formatted(comparison);
        return db.query(query + boundary + order, JdbcNoticeStore::summary,
                utc(asOf), after.pinned(), after.pinned(), publishedAt, publishedAt, id(after.noticeId()), limit);
    }

    @Override
    public Optional<NoticeDetail> findPublished(UUID noticeId, Instant asOf) {
        return db.query("SELECT " + SUMMARY + ", content" + PUBLIC + " AND notice_id = ?", (row, index) -> {
            var item = summary(row, index);
            return new NoticeDetail(item.noticeId(), item.title(), item.summary(), item.pinned(),
                    item.publishedAt(), item.updatedAt(), row.getString("content"));
        }, utc(asOf), id(noticeId)).stream().findFirst();
    }

    @Override
    public List<AdminRow> findAll(AdminPosition after, int limit, NoticeSort sort) {
        String direction = direction(sort);
        String order = " ORDER BY created_at " + direction + ", notice_id " + direction + " LIMIT ?";
        if (after == null) {
            return db.query(ADMIN + order, JdbcNoticeStore::adminRow, limit);
        }
        var createdAt = utc(after.createdAt());
        String boundary = " AND (created_at %1$s ? OR (created_at = ? AND notice_id %1$s ?))"
                .formatted(comparison(sort));
        return db.query(ADMIN + boundary + order,
                JdbcNoticeStore::adminRow, createdAt, createdAt, id(after.noticeId()), limit);
    }

    @Override
    public Optional<AdminNotice> findAdmin(UUID noticeId) {
        return db.query(ADMIN + " AND notice_id = ?", (row, index) -> admin(row), id(noticeId))
                .stream().findFirst();
    }

    @Override
    public void insert(UUID noticeId, Changes input, Instant now) {
        var time = utc(now);
        db.update("""
                INSERT INTO notice (notice_id, title, summary, content, pinned, published,
                                    published_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id(noticeId), input.title(), input.summary(), input.content(), input.pinned(),
                input.published(), Boolean.TRUE.equals(input.published()) ? time : null, time, time);
    }

    @Override
    public void update(UUID noticeId, Changes input, Instant now) {
        var time = utc(now);
        db.update("""
                UPDATE notice SET title = COALESCE(?, title), summary = COALESCE(?, summary),
                    content = COALESCE(?, content), pinned = COALESCE(?, pinned),
                    published = COALESCE(?, published),
                    published_at = CASE WHEN ? = TRUE THEN COALESCE(published_at, ?) ELSE published_at END,
                    updated_at = GREATEST(updated_at, ?)
                WHERE notice_id = ? AND deleted_at IS NULL
                """, input.title(), input.summary(), input.content(), input.pinned(),
                input.published(), input.published(), time, time, id(noticeId));
    }

    @Override
    public boolean markDeleted(UUID noticeId, Instant now) {
        return db.update("UPDATE notice SET deleted_at = ? WHERE notice_id = ? AND deleted_at IS NULL",
                utc(now), id(noticeId)) == 1;
    }

    private static String direction(NoticeSort sort) {
        return sort == NoticeSort.OLDEST ? "ASC" : "DESC";
    }

    private static String comparison(NoticeSort sort) {
        return sort == NoticeSort.OLDEST ? ">" : "<";
    }

    private static AdminRow adminRow(ResultSet row, int index) throws SQLException {
        var notice = admin(row);
        return new AdminRow(notice, new AdminPosition(
                row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), notice.noticeId()));
    }

    private static AdminNotice admin(ResultSet row) throws SQLException {
        var publishedAt = row.getObject("published_at", LocalDateTime.class);
        return new AdminNotice(uuid(row.getBytes("notice_id")), row.getString("title"), row.getString("summary"),
                row.getBoolean("pinned"), publishedAt == null ? null : publishedAt.toInstant(ZoneOffset.UTC),
                row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getString("content"), row.getBoolean("published"));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static NoticeSummary summary(ResultSet row, int index) throws SQLException {
        return new NoticeSummary(uuid(row.getBytes("notice_id")), row.getString("title"), row.getString("summary"),
                row.getBoolean("pinned"), row.getObject("published_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
