package kr.nospo.terms.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.terms.application.TermStore;
import kr.nospo.terms.contract.Terms.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class JdbcTerms implements TermStore {
    private final JdbcTemplate jdbc;

    public JdbcTerms(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock() {
        jdbc.query(
                    "SELECT term_id FROM term ORDER BY term_id FOR UPDATE",
                    (rs, n) -> rs.getBytes(1));
    }

    public List<Version> current(Instant now, boolean lock) {
        if (lock) lock();
        return jdbc.query(
                "SELECT v.*,t.type FROM term_version v JOIN term t ON t.term_id=v.term_id WHERE"
                        + " v.publication_status='PUBLISHED' AND v.effective_at<=? AND NOT EXISTS"
                        + " (SELECT 1 FROM term_version newer WHERE newer.term_id=v.term_id AND"
                        + " newer.publication_status='PUBLISHED' AND newer.effective_at<=? AND"
                        + " newer.effective_at>v.effective_at) ORDER BY t.type",
                (rs, n) ->
                        new Version(
                                uuid(rs.getBytes("term_version_id")),
                                uuid(rs.getBytes("term_id")),
                                rs.getString("type"),
                                rs.getString("version"),
                                rs.getString("title"),
                                rs.getBoolean("required"),
                                rs.getTimestamp("effective_at").toInstant(),
                                rs.getBoolean("requires_reconsent")),
                at(now),
                at(now));
    }

    public List<Version> effective(Instant now) {
        return jdbc.query(
                "SELECT v.*,t.type FROM term_version v JOIN term t ON t.term_id=v.term_id"
                        + " WHERE v.publication_status='PUBLISHED' AND v.effective_at<=?"
                        + " ORDER BY t.type,v.effective_at,v.term_version_id",
                (rs,n) -> new Version(
                        uuid(rs.getBytes("term_version_id")),uuid(rs.getBytes("term_id")),
                        rs.getString("type"),rs.getString("version"),rs.getString("title"),
                        rs.getBoolean("required"),rs.getTimestamp("effective_at").toInstant(),
                        rs.getBoolean("requires_reconsent")), at(now));
    }

    public Optional<Detail> detail(UUID version, Instant now) {
        return jdbc
                .query(
                        "SELECT v.*,t.type FROM term_version v JOIN term t ON t.term_id=v.term_id"
                            + " WHERE v.term_version_id=? AND v.publication_status='PUBLISHED' AND"
                            + " v.effective_at<=?",
                        (rs, n) ->
                                new Detail(
                                        uuid(rs.getBytes("term_version_id")),
                                        uuid(rs.getBytes("term_id")),
                                        rs.getString("type"),
                                        rs.getString("version"),
                                        rs.getString("title"),
                                        rs.getBoolean("required"),
                                        rs.getTimestamp("effective_at").toInstant(),
                                        rs.getBoolean("requires_reconsent"),
                                        rs.getString("content")),
                        id(version),
                        at(now))
                .stream()
                .findFirst();
    }

    public void save(UUID member, Consent consent, Instant now) {
        jdbc.update(
                "INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at,event_order)"
                        + " SELECT ?,?,?,?,?,COALESCE(MAX(event_order),0)+1 FROM member_consent WHERE member_id=?",
                id(UUID.randomUUID()),
                id(member),
                id(consent.termVersionId()),
                consent.agreed(),
                at(now),
                id(member));
    }
}
