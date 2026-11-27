package kr.nospo.wiki.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.support.ApiException;
import kr.nospo.wiki.application.WikiStore.*;
import kr.nospo.wiki.application.WikiWorkflowStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import static kr.nospo.support.SqlIds.*;

@Repository
class JdbcWikiWorkflow implements WikiWorkflowStore {
    private static final String REVIEW = """
            SELECT d.*,s.submission_id,s.status AS review_status,s.snapshot AS submitted_snapshot,s.submitted_at,
                s.decided_at,s.revision AS review_revision,s.review_comment,s.reward_amount,s.reward_reason,
                s.reward_transaction_id,s.published_version
            FROM wiki_submission s JOIN wiki_draft d ON d.draft_id=s.draft_id WHERE 1=1
            """;
    private static final RowMapper<Review> REVIEW_ROW = (r, n) -> new Review(
            new Submission(uuid(r.getBytes("submission_id")), uuid(r.getBytes("draft_id")),
                    ReviewStatus.valueOf(r.getString("review_status")), r.getString("submitted_snapshot"),
                    r.getTimestamp("submitted_at").toInstant(),
                    r.getTimestamp("decided_at") == null ? null : r.getTimestamp("decided_at").toInstant()),
            JdbcWikiStore.DRAFT.mapRow(r, n), r.getLong("review_revision"), r.getString("review_comment"),
            r.getObject("reward_amount", Integer.class), r.getString("reward_reason"),
            uuid(r.getBytes("reward_transaction_id")), r.getObject("published_version", Integer.class));
    private final JdbcTemplate db;

    JdbcWikiWorkflow(JdbcTemplate db) { this.db = db; }

    public Optional<Review> review(UUID value, boolean lock) {
        return db.query(REVIEW + " AND s.submission_id=?" + (lock ? " FOR UPDATE" : ""),
                REVIEW_ROW, id(value)).stream().findFirst();
    }

    public List<Review> reviews(UUID member, UUID book, ReviewStatus status, DraftCursor cursor, int limit) {
        var sql = new StringBuilder(REVIEW);
        var args = new ArrayList<Object>();
        if (member != null) { sql.append(" AND d.member_id=?"); args.add(id(member)); }
        if (book != null) { sql.append(" AND d.book_id=?"); args.add(id(book)); }
        if (status != null) { sql.append(" AND s.status=?"); args.add(status.name()); }
        page(sql, args, "s.submitted_at", "s.submission_id", cursor, limit);
        return db.query(sql.toString(), REVIEW_ROW, args.toArray());
    }

    public boolean submitDraft(Draft draft) {
        return db.update("""
                UPDATE wiki_draft SET status='SUBMITTED',revision=revision+1
                WHERE draft_id=? AND status='DRAFT' AND revision=?
                """, id(draft.id()), draft.revision()) == 1;
    }

    public void decide(UUID submission, ReviewStatus status, Instant now, String comment, Integer amount,
                       String reason, UUID reward, Integer version) {
        db.update("""
                UPDATE wiki_submission SET status=?,decided_at=?,review_comment=?,reward_amount=?,reward_reason=?,
                    reward_transaction_id=?,published_version=?,revision=revision+1 WHERE submission_id=?
                """, status.name(), at(now), comment, amount, reason, id(reward), version, id(submission));
    }

    public void activity(Activity a) {
        db.update("""
                INSERT INTO wiki_activity(activity_id,member_id,book_id,draft_id,submission_id,action,actor_id,
                    comment,reward_amount,reward_reason,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id(a.id()), id(a.memberId()), id(a.bookId()), id(a.draftId()), id(a.submissionId()),
                a.action(), id(a.actorId()), a.comment(), a.rewardAmount(), a.rewardReason(), at(a.occurredAt()));
    }

    public List<Activity> activities(UUID member, UUID book, UUID submission, String action, Instant from, Instant to,
                                     DraftCursor cursor, int limit) {
        var sql = new StringBuilder("SELECT * FROM wiki_activity WHERE 1=1");
        var args = new ArrayList<Object>();
        if (member != null) { sql.append(" AND member_id=?"); args.add(id(member)); }
        if (book != null) { sql.append(" AND book_id=?"); args.add(id(book)); }
        if (submission != null) { sql.append(" AND submission_id=?"); args.add(id(submission)); }
        if (action != null) { sql.append(" AND action=?"); args.add(action); }
        if (from != null) { sql.append(" AND occurred_at>=?"); args.add(at(from)); }
        if (to != null) { sql.append(" AND occurred_at<=?"); args.add(at(to)); }
        page(sql, args, "occurred_at", "activity_id", cursor, limit);
        return db.query(sql.toString(), (r, n) -> new Activity(uuid(r.getBytes("activity_id")),
                uuid(r.getBytes("member_id")), uuid(r.getBytes("book_id")), uuid(r.getBytes("draft_id")),
                uuid(r.getBytes("submission_id")), r.getString("action"), uuid(r.getBytes("actor_id")),
                r.getString("comment"), r.getObject("reward_amount", Integer.class), r.getString("reward_reason"),
                r.getTimestamp("occurred_at").toInstant()), args.toArray());
    }

    public Optional<Replay> claim(UUID actor, String scope, UUID key, UUID draft, String fingerprint, Instant now) {
        db.update("DELETE FROM wiki_request WHERE actor_id=? AND scope=? AND request_key=? AND created_at<=?",
                id(actor), scope, id(key), at(now.minusSeconds(86400)));
        var previous = db.query("""
                SELECT fingerprint,response FROM wiki_request WHERE actor_id=? AND scope=? AND request_key=?
                """, (r, n) -> new Replay(r.getString(1), r.getString(2)), id(actor), scope, id(key)).stream().findFirst();
        if (previous.isEmpty()) db.update("""
                INSERT INTO wiki_request(actor_id,scope,request_key,draft_id,fingerprint,created_at) VALUES (?,?,?,?,?,?)
                """, id(actor), scope, id(key), id(draft), fingerprint, at(now));
        return previous;
    }

    public void replay(UUID actor, String scope, UUID key, String response) {
        db.update("UPDATE wiki_request SET response=? WHERE actor_id=? AND scope=? AND request_key=?",
                response, id(actor), scope, id(key));
    }

    public Optional<UUID> revisionDraft(UUID submission) {
        return db.query("SELECT draft_id FROM wiki_revision_draft WHERE submission_id=?",
                (r, n) -> uuid(r.getBytes(1)), id(submission)).stream().findFirst();
    }

    public void connectRevisionDraft(UUID submission, UUID draft) {
        db.update("INSERT INTO wiki_revision_draft(submission_id,draft_id) VALUES (?,?)", id(submission), id(draft));
    }

    public void lockMember(UUID member) {
        if (db.query("SELECT status FROM member WHERE member_id=? FOR UPDATE",
                (r, n) -> r.getString(1), id(member)).stream().noneMatch("ACTIVE"::equals))
            throw new ApiException(404, "RESOURCE_NOT_FOUND");
    }

    public boolean administrator(UUID member) {
        return db.query("SELECT role FROM member WHERE member_id=? AND status='ACTIVE'",
                (r, n) -> r.getString(1), id(member)).stream().anyMatch("ADMIN"::equals);
    }

    public long countDrafts(UUID member) {
        return db.queryForObject("SELECT COUNT(*) FROM wiki_draft WHERE member_id=? AND status='DRAFT'",
                Long.class, id(member));
    }

    public long countReviews(UUID member, ReviewStatus status) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM wiki_submission s JOIN wiki_draft d ON s.draft_id=d.draft_id
                WHERE d.member_id=? AND s.status=?
                """, Long.class, id(member), status.name());
    }

    public void insertAsset(Asset asset) {
        db.update("INSERT INTO wiki_asset(asset_id,book_id,draft_id,width,height,image_bytes) VALUES (?,?,?,?,?,?)",
                id(asset.id()), id(asset.bookId()), id(asset.draftId()), asset.width(), asset.height(), asset.bytes());
    }

    public Optional<Asset> asset(UUID asset) {
        return db.query("SELECT * FROM wiki_asset WHERE asset_id=?", (r, n) -> new Asset(uuid(r.getBytes("asset_id")),
                uuid(r.getBytes("book_id")), uuid(r.getBytes("draft_id")), r.getInt("width"), r.getInt("height"),
                r.getBytes("image_bytes")), id(asset)).stream().findFirst();
    }

    public List<String> ownedSnapshots(UUID member, UUID book) {
        return db.query("SELECT snapshot FROM wiki_draft WHERE member_id=? AND book_id=?",
                (r, n) -> r.getString(1), id(member), id(book));
    }

    public void removeAsset(UUID draft, UUID asset) {
        db.update("DELETE FROM wiki_asset WHERE asset_id=? AND draft_id=?", id(asset), id(draft));
    }

    public void publishAsset(UUID draft, UUID asset) {
        db.update("UPDATE wiki_asset SET draft_id=NULL WHERE asset_id=? AND draft_id=?", id(asset), id(draft));
    }

    private static void page(StringBuilder sql, List<Object> args, String time, String key, DraftCursor cursor, int limit) {
        if (cursor != null) {
            sql.append(" AND (").append(time).append("<? OR (").append(time).append("=? AND ").append(key).append("<?))");
            args.add(at(cursor.updatedAt()));
            args.add(at(cursor.updatedAt()));
            args.add(id(cursor.id()));
        }
        sql.append(" ORDER BY ").append(time).append(" DESC,").append(key).append(" DESC LIMIT ?");
        args.add(limit);
    }
}
