package kr.nospo.wiki.persistence;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import kr.nospo.wiki.application.WikiBookAccess.Position;
import kr.nospo.wiki.application.WikiBookAccess.Unit;
import kr.nospo.wiki.application.WikiStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import static kr.nospo.support.SqlIds.*;

@Repository
public class JdbcWikiStore implements WikiStore {
    static final RowMapper<Draft> DRAFT = (row, index) -> new Draft(
            uuid(row.getBytes("draft_id")), uuid(row.getBytes("member_id")), uuid(row.getBytes("book_id")),
            Mode.valueOf(row.getString("mode")), DraftStatus.valueOf(row.getString("status")),
            new Position(Unit.valueOf(row.getString("position_unit")), row.getInt("position_value")),
            uuid(row.getBytes("wiki_id")), row.getObject("base_wiki_version", Integer.class),
            uuid(row.getBytes("parent_submission_id")), row.getLong("revision"), row.getString("snapshot"),
            row.getTimestamp("updated_at").toInstant());

    private static final RowMapper<PublishedVersion> VERSION = (row, index) -> new PublishedVersion(
            uuid(row.getBytes("wiki_id")), uuid(row.getBytes("book_id")), row.getInt("version"),
            row.getString("snapshot"), row.getTimestamp("published_at").toInstant());

    private final JdbcTemplate db;

    public JdbcWikiStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void insertDraft(Draft draft) {
        db.update("""
                INSERT INTO wiki_draft (draft_id,member_id,book_id,mode,status,position_unit,position_value,
                    wiki_id,base_wiki_version,parent_submission_id,revision,snapshot,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id(draft.id()), id(draft.memberId()), id(draft.bookId()), draft.mode().name(),
                draft.status().name(), draft.position().unit().name(), draft.position().value(),
                id(draft.wikiId()), draft.baseWikiVersion(), id(draft.parentSubmissionId()), draft.revision(),
                draft.snapshot(), at(draft.updatedAt()));
    }

    @Override
    public void initializeDraftSnapshot(UUID draft, String snapshot) {
        db.update("UPDATE wiki_draft SET snapshot=? WHERE draft_id=? AND status='DRAFT' AND revision=0",
                snapshot, id(draft));
    }

    @Override
    public Optional<Draft> findDraft(UUID memberId, UUID draftId) {
        return db.query("SELECT * FROM wiki_draft WHERE member_id=? AND draft_id=?",
                DRAFT, id(memberId), id(draftId)).stream().findFirst();
    }

    @Override
    public List<Draft> drafts(UUID memberId, UUID bookId, DraftCursor after, int limit) {
        String sql = "SELECT * FROM wiki_draft WHERE member_id=? AND status='DRAFT'";
        var args = new ArrayList<Object>();
        args.add(id(memberId));
        if (bookId != null) {
            sql += " AND book_id=?";
            args.add(id(bookId));
        }
        if (after != null) {
            sql += " AND (updated_at<? OR (updated_at=? AND draft_id<?))";
            args.add(at(after.updatedAt()));
            args.add(at(after.updatedAt()));
            args.add(id(after.id()));
        }
        args.add(limit);
        return db.query(sql + " ORDER BY updated_at DESC,draft_id DESC LIMIT ?", DRAFT, args.toArray());
    }

    @Override
    public boolean saveDraft(Draft draft, long expectedRevision) {
        return db.update("""
                UPDATE wiki_draft SET position_value=?,snapshot=?,revision=revision+1,updated_at=?
                WHERE member_id=? AND draft_id=? AND status='DRAFT' AND revision=?
                """, draft.position().value(), draft.snapshot(), at(draft.updatedAt()),
                id(draft.memberId()), id(draft.id()), expectedRevision) == 1;
    }

    @Override
    public boolean deleteDraft(UUID memberId, UUID draftId, long expectedRevision) {
        return db.update("""
                DELETE FROM wiki_draft WHERE member_id=? AND draft_id=? AND status='DRAFT' AND revision=?
                """, id(memberId), id(draftId), expectedRevision) == 1;
    }

    @Override
    public Optional<PublishedVersion> findVersion(UUID bookId, UUID wikiId, int version) {
        return db.query("""
                SELECT w.wiki_id,w.book_id,v.version,v.snapshot,v.published_at
                FROM wiki w JOIN wiki_version v ON v.wiki_id=w.wiki_id
                WHERE w.book_id=? AND w.wiki_id=? AND v.version=?
                """, VERSION, id(bookId), id(wikiId), version).stream().findFirst();
    }

    @Override
    public void insertSubmission(Submission submission) {
        db.update("""
                INSERT INTO wiki_submission (submission_id,draft_id,status,snapshot,submitted_at,decided_at)
                VALUES (?,?,?,?,?,?)
                """, id(submission.id()), id(submission.draftId()), submission.status().name(),
                submission.snapshot(), at(submission.submittedAt()), at(submission.decidedAt()));
    }

    @Override
    public Optional<Submission> findSubmission(UUID submissionId) {
        return db.query("SELECT * FROM wiki_submission WHERE submission_id=?", (row, index) -> {
            var decidedAt = row.getTimestamp("decided_at");
            return new Submission(uuid(row.getBytes("submission_id")), uuid(row.getBytes("draft_id")),
                    ReviewStatus.valueOf(row.getString("status")), row.getString("snapshot"),
                    row.getTimestamp("submitted_at").toInstant(), decidedAt == null ? null : decidedAt.toInstant());
        }, id(submissionId)).stream().findFirst();
    }

    @Override
    public void insertWiki(UUID wikiId, UUID bookId) {
        db.update("INSERT INTO wiki (wiki_id,book_id) VALUES (?,?)", id(wikiId), id(bookId));
    }

    @Override
    public void insertVersion(PublishedVersion version) {
        int inserted = db.update("""
                INSERT INTO wiki_version (wiki_id,version,snapshot,published_at)
                SELECT wiki_id,?,?,? FROM wiki WHERE wiki_id=? AND book_id=?
                """, version.version(), version.snapshot(), at(version.publishedAt()),
                id(version.wikiId()), id(version.bookId()));
        if (inserted != 1) throw new IllegalArgumentException("Wiki does not belong to this edition");
    }

    @Override
    public Optional<PublishedVersion> latestVersion(UUID bookId) {
        return db.query("""
                SELECT w.wiki_id,w.book_id,v.version,v.snapshot,v.published_at
                FROM wiki w JOIN wiki_version v ON v.wiki_id=w.wiki_id
                WHERE w.book_id=? ORDER BY v.version DESC LIMIT 1
                """, VERSION,
                id(bookId)).stream().findFirst();
    }
}
