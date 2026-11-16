package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.identity.application.VerificationStore;
import kr.nospo.support.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class JdbcVerificationStore implements VerificationStore {
    private final JdbcTemplate db;
    private final WithdrawalReservations reservations;

    public JdbcVerificationStore(JdbcTemplate db, WithdrawalReservations reservations) {
        this.db = db;
        this.reservations = reservations;
    }

    private UUID key(byte[] bytes) {
        return bytes == null ? null : uuid(bytes);
    }

    private byte[] bytes(UUID key) {
        return key == null ? null : id(key);
    }

    private Instant time(java.sql.ResultSet r, String name) throws java.sql.SQLException {
        var t = r.getTimestamp(name);
        return t == null ? null : t.toInstant();
    }

    public void lockMember(UUID member) {
        var rows =
                db.query(
                        "SELECT member_id FROM member WHERE member_id=? AND status='ACTIVE' FOR"
                            + " UPDATE",
                        (r, n) -> r.getBytes(1),
                        id(member));
        if (rows.isEmpty()) throw new ApiException(401, "INVALID_CREDENTIALS");
        db.queryForObject(
                "SELECT member_id FROM account WHERE member_id=? FOR UPDATE",
                byte[].class,
                id(member));
    }

    public boolean activeSession(UUID member, UUID session, Instant now) {
        return db.queryForObject(
                        "SELECT COUNT(*) FROM auth_session WHERE member_id=? AND session_id=? AND"
                            + " revoked_at IS NULL AND expires_at>?",
                        Integer.class,
                        id(member),
                        id(session),
                        at(now))
                == 1;
    }

    public Optional<UUID> member(String email) {
        return db
                .query(
                        "SELECT a.member_id FROM account a JOIN password_credential p ON"
                            + " p.member_id=a.member_id JOIN member m ON a.member_id=m.member_id"
                            + " WHERE a.email_key=? AND m.status='ACTIVE'",
                        (r, n) -> uuid(r.getBytes(1)),
                        email)
                .stream()
                .findFirst();
    }

    public boolean emailExists(String email) {
        if (reservations.owner("EMAIL", email).isPresent()) return true;
        return db.queryForObject(
                        "SELECT COUNT(*) FROM account WHERE email_key=?", Integer.class, email)
                > 0;
    }

    public String password(UUID member) {
        return db
                .query(
                        "SELECT password_hash FROM password_credential WHERE member_id=?",
                        (r, n) -> r.getString(1),
                        id(member))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(409, "EMAIL_LOGIN_NOT_ENABLED"));
    }

    public void changePassword(UUID member, String hash, Instant now) {
        if (db.update(
                "UPDATE password_credential SET password_hash=?,password_changed_at=? WHERE"
                    + " member_id=?",
                hash,
                at(now),
                id(member)) != 1) throw new ApiException(409, "EMAIL_LOGIN_NOT_ENABLED");
    }

    public void changeEmail(UUID member, String email, Instant now) {
        reservations.lock("EMAIL", email, now);
        if (reservations.owner("EMAIL", email).isPresent()) throw new ApiException(409,"EMAIL_ALREADY_EXISTS");
        try {
            db.update(
                    "UPDATE account SET email=?,email_key=?,email_verified_at=?,updated_at=? WHERE"
                        + " member_id=?",
                    email,
                    email,
                    at(now),
                    at(now),
                    id(member));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
        }
    }

    public void invalidateCredentials(UUID member, Instant now) {
        db.update(
                "UPDATE auth_proof SET invalidated_at=? WHERE member_id=? AND consumed_at IS NULL",
                at(now),
                id(member));
        db.update(
                "UPDATE auth_challenge SET invalidated_at=? WHERE member_id=?",
                at(now),
                id(member));
        db.update(
                "UPDATE auth_session SET revoked_at=?,revoke_reason='PASSWORD_CHANGED' WHERE"
                    + " member_id=?",
                at(now),
                id(member));
    }

    private String scope(UUID member) {
        return member == null ? "target_email_key=?" : "member_id=?";
    }

    private Object value(String email, UUID member) {
        return member == null ? email : id(member);
    }

    public void invalidate(String purpose, String email, UUID member, Instant now) {
        for (String table : List.of("auth_challenge", "auth_proof"))
            db.update(
                    "UPDATE "
                            + table
                            + " SET invalidated_at=? WHERE purpose=? AND "
                            + scope(member),
                    at(now),
                    purpose,
                    value(email, member));
    }

    public boolean coolingDown(String purpose, String email, UUID member, Instant now) {
        return db.queryForObject(
                        "SELECT COUNT(*) FROM auth_challenge WHERE purpose=? AND "
                                + scope(member)
                                + " AND resend_available_at>?",
                        Integer.class,
                        purpose,
                        value(email, member),
                        at(now))
                > 0;
    }

    public void createChallenge(
            UUID key,
            UUID member,
            UUID session,
            String purpose,
            String email,
            byte[] digest,
            Instant now) {
        db.update(
                "INSERT INTO"
                    + " auth_challenge(challenge_id,member_id,session_id,purpose,target_email,target_email_key,secret_digest,attempt_count,created_at,expires_at,resend_available_at)"
                    + " VALUES (?,?,?,?,?,?,?,0,?,?,?)",
                id(key),
                bytes(member),
                bytes(session),
                purpose,
                email,
                email,
                digest,
                at(now),
                at(now.plusSeconds(600)),
                at(now.plusSeconds(60)));
    }

    public Optional<Challenge> lockChallenge(UUID key, String purpose) {
        return db
                .query(
                        "SELECT * FROM auth_challenge WHERE challenge_id=? AND purpose=? FOR"
                            + " UPDATE",
                        (r, n) ->
                                new Challenge(
                                        key,
                                        key(r.getBytes("member_id")),
                                        key(r.getBytes("session_id")),
                                        r.getString("target_email_key"),
                                        r.getBytes("secret_digest"),
                                        r.getInt("attempt_count"),
                                        time(r, "expires_at"),
                                        time(r, "verified_at"),
                                        time(r, "invalidated_at")),
                        id(key),
                        purpose)
                .stream()
                .findFirst();
    }

    public void attempt(UUID key) {
        db.update(
                "UPDATE auth_challenge SET attempt_count=attempt_count+1 WHERE challenge_id=?",
                id(key));
    }

    public void verified(UUID key, Instant now) {
        db.update("UPDATE auth_challenge SET verified_at=? WHERE challenge_id=?", at(now), id(key));
    }

    public void createProof(
            UUID key,
            UUID challenge,
            UUID member,
            UUID session,
            String purpose,
            String email,
            byte[] digest,
            Instant now,
            int seconds) {
        db.update(
                "INSERT INTO"
                    + " auth_proof(proof_id,challenge_id,member_id,session_id,purpose,proof_hash,target_email,target_email_key,created_at,expires_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                id(key),
                bytes(challenge),
                bytes(member),
                bytes(session),
                purpose,
                digest,
                email,
                email,
                at(now),
                at(now.plusSeconds(seconds)));
    }

    public Optional<Proof> proof(byte[] digest, String purpose, Instant now, boolean lock) {
        return db
                .query(
                        "SELECT proof_id,member_id,session_id,target_email_key FROM auth_proof"
                            + " WHERE proof_hash=? AND purpose=? AND consumed_at IS NULL AND"
                            + " invalidated_at IS NULL AND expires_at>?"
                                + (lock ? " FOR UPDATE" : ""),
                        (r, n) ->
                                new Proof(
                                        key(r.getBytes(1)),
                                        key(r.getBytes(2)),
                                        key(r.getBytes(3)),
                                        r.getString(4)),
                        digest,
                        purpose,
                        at(now))
                .stream()
                .findFirst();
    }

    public void consume(UUID key, Instant now) {
        if (db.update(
                        "UPDATE auth_proof SET consumed_at=? WHERE proof_id=? AND consumed_at IS"
                            + " NULL AND invalidated_at IS NULL AND expires_at>?",
                        at(now),
                        id(key),
                        at(now))
                != 1) throw new ApiException(400, "INVALID_PROOF");
    }
}
