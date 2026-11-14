package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.identity.application.AccountStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class JdbcAccountStore implements AccountStore {
    private final JdbcTemplate db;

    public JdbcAccountStore(JdbcTemplate db) {
        this.db = db;
    }

    private Instant time(java.sql.ResultSet r, int i) throws java.sql.SQLException {
        var t = r.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }

    public Optional<Credential> credential(String email) {
        return db
                .query(
                        "SELECT a.member_id,p.password_hash FROM account a JOIN password_credential"
                            + " p ON p.member_id=a.member_id JOIN member m ON"
                            + " m.member_id=a.member_id WHERE a.email_key=? AND m.status='ACTIVE'",
                        (r, n) -> new Credential(uuid(r.getBytes(1)), r.getString(2)),
                        email)
                .stream()
                .findFirst();
    }

    public String lockPassword(UUID member) {
        if (db.query(
                        "SELECT member_id FROM member WHERE member_id=? AND status='ACTIVE' FOR"
                                + " UPDATE",
                        (r, n) -> r.getBytes(1),
                        id(member))
                .isEmpty()) throw new kr.nospo.support.ApiException(401, "INVALID_CREDENTIALS");
        db.queryForObject(
                "SELECT member_id FROM account WHERE member_id=? FOR UPDATE",
                byte[].class,
                id(member));
        return db.queryForObject(
                "SELECT password_hash FROM password_credential WHERE member_id=?",
                String.class,
                id(member));
    }

    public Optional<Refresh> lockRefresh(byte[] digest) {
        return db
                .query(
                        "SELECT"
                            + " s.session_id,s.member_id,s.expires_at,s.revoked_at,r.refresh_token_id,r.used_at,r.revoked_at,r.expires_at"
                            + " FROM refresh_token r JOIN auth_session s ON"
                            + " s.session_id=r.session_id WHERE r.token_hash=? FOR UPDATE",
                        (r, n) ->
                                new Refresh(
                                        uuid(r.getBytes(1)),
                                        uuid(r.getBytes(2)),
                                        time(r, 3),
                                        time(r, 4),
                                        uuid(r.getBytes(5)),
                                        time(r, 6),
                                        time(r, 7),
                                        time(r, 8)),
                        digest)
                .stream()
                .findFirst();
    }

    public void rotate(Refresh old, UUID next, byte[] digest, Instant now) {
        db.update(
                "INSERT INTO"
                    + " refresh_token(refresh_token_id,session_id,token_hash,issued_at,expires_at)"
                    + " VALUES (?,?,?,?,?)",
                id(next),
                id(old.session()),
                digest,
                at(now),
                at(old.expiry()));
        db.update(
                "UPDATE refresh_token SET used_at=?,replaced_by_token_id=? WHERE"
                        + " refresh_token_id=?",
                at(now),
                id(next),
                id(old.token()));
    }

    public Optional<UUID> session(byte[] digest) {
        return db
                .query(
                        "SELECT session_id FROM refresh_token WHERE token_hash=?",
                        (r, n) -> uuid(r.getBytes(1)),
                        digest)
                .stream()
                .findFirst();
    }

    public void revoke(UUID session, Instant now) {
        db.update(
                "UPDATE auth_session SET revoked_at=?,revoke_reason='LOGOUT_OR_REUSE' WHERE"
                        + " session_id=?",
                at(now),
                id(session));
    }
}
