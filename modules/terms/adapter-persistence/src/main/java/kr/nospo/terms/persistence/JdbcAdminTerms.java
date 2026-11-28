package kr.nospo.terms.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.terms.application.AdminTerms;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static kr.nospo.support.SqlIds.*;
import static kr.nospo.terms.application.AdminTerms.*;

@Repository
class JdbcAdminTerms implements AdminTerms.Store {
    private static final String COLUMNS = """
            SELECT v.term_version_id,v.term_id,t.type,v.version,v.title,v.required,v.effective_at,
                   v.requires_reconsent,v.publication_status,v.created_at
            """;
    private static final String FROM = " FROM term_version v JOIN term t ON t.term_id=v.term_id ";
    private final JdbcTemplate db;

    JdbcAdminTerms(JdbcTemplate db) { this.db = db; }

    public Optional<String> termType(UUID termId) {
        return db.query("SELECT type FROM term WHERE term_id=?", (r, n) -> r.getString(1), id(termId))
                .stream().findFirst();
    }

    public void insertTerm(UUID termId, String type, Instant now) {
        db.update("INSERT INTO term(term_id,type,created_at) VALUES (?,?,?)", id(termId), type, at(now));
    }

    public void insertVersion(UUID versionId, TermVersionCreate input, Instant now) {
        db.update("""
                INSERT INTO term_version(term_version_id,term_id,version,title,content,required,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, id(versionId), id(input.termId()), input.version(), input.title(), input.content(),
                input.required(), at(now));
    }

    public Optional<TermVersionDetail> adminDetail(UUID versionId) {
        return db.query(COLUMNS + ",v.content" + FROM + "WHERE v.term_version_id=?", (row, n) -> {
            var view = view(row);
            return new TermVersionDetail(view.termVersionId(), view.termId(), view.type(), view.version(), view.title(),
                    view.required(), view.effectiveAt(), view.requiresReconsent(), view.publicationStatus(),
                    row.getString("content"));
        }, id(versionId)).stream().findFirst();
    }

    public List<Row> adminVersions(UUID termId, Cursor after, int limit) {
        String sql = COLUMNS + FROM + "WHERE 1=1";
        var args = new ArrayList<Object>();
        if (termId != null) {
            sql += " AND v.term_id=?";
            args.add(id(termId));
        }
        if (after != null) {
            sql += " AND (v.created_at<? OR (v.created_at=? AND v.term_version_id<?))";
            args.add(at(after.createdAt()));
            args.add(at(after.createdAt()));
            args.add(id(after.versionId()));
        }
        args.add(limit);
        return db.query(sql + " ORDER BY v.created_at DESC,v.term_version_id DESC LIMIT ?",
                (row, n) -> new Row(view(row), row.getTimestamp("created_at").toInstant()), args.toArray());
    }

    public boolean schedule(UUID versionId, TermPublication publication, Instant now) {
        return db.update("""
                UPDATE term_version SET publication_status='PUBLISHED',effective_at=?,requires_reconsent=?
                WHERE term_version_id=? AND (effective_at IS NULL OR effective_at>?)
                """, at(publication.effectiveAt()), publication.requiresReconsent(), id(versionId), at(now)) == 1;
    }

    public void audit(UUID version, UUID actor, String action, String reason, Instant now) {
        db.update("""
                INSERT INTO term_admin_audit(audit_id,term_version_id,actor_id,action,reason,occurred_at)
                VALUES (?,?,?,?,?,?)
                """, id(UUID.randomUUID()), id(version), id(actor), action, reason, at(now));
    }

    private static TermVersionView view(ResultSet row) throws SQLException {
        var effective = row.getTimestamp("effective_at");
        return new TermVersionView(uuid(row.getBytes("term_version_id")), uuid(row.getBytes("term_id")), row.getString("type"),
                row.getString("version"), row.getString("title"), row.getBoolean("required"),
                effective == null ? null : effective.toInstant(), row.getBoolean("requires_reconsent"),
                row.getString("publication_status"));
    }
}
