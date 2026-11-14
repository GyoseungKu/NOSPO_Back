package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.identity.application.IdentityStore;
import kr.nospo.support.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcIdentityStore implements IdentityStore {
    private final JdbcTemplate jdbc;

    public JdbcIdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant time(ResultSet r, String field) throws SQLException {
        var t = r.getTimestamp(field);
        return t == null ? null : t.toInstant();
    }

    private Challenge challengeRow(ResultSet r, int n) throws SQLException {
        return new Challenge(
                uuid(r.getBytes("challenge_id")),
                r.getString("target_email_key"),
                time(r, "expires_at"),
                time(r, "resend_available_at"),
                time(r, "verified_at"),
                time(r, "invalidated_at"));
    }

    public Optional<Challenge> challenge(byte[] hash) {
        return jdbc
                .query(
                        "SELECT * FROM auth_challenge WHERE secret_digest=? AND purpose='SIGNUP'",
                        this::challengeRow,
                        hash)
                .stream()
                .findFirst();
    }

    public Optional<Challenge> challenge(UUID key) {
        return jdbc
                .query(
                        "SELECT * FROM auth_challenge WHERE challenge_id=?",
                        this::challengeRow,
                        id(key))
                .stream()
                .findFirst();
    }

    public Optional<Instant> resendAt(String email) {
        return jdbc
                .query(
                        "SELECT MAX(resend_available_at) FROM auth_challenge WHERE"
                                + " target_email_key=? AND purpose='SIGNUP'",
                        (r, n) -> {
                            var t = r.getTimestamp(1);
                            return t == null ? null : t.toInstant();
                        },
                        email)
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    public boolean emailExists(String email) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM account WHERE email_key=?", Integer.class, email)
                > 0;
    }

    public void invalidate(String email, Instant now) {
        jdbc.update(
                "UPDATE auth_challenge SET invalidated_at=? WHERE target_email_key=? AND"
                        + " purpose='SIGNUP' AND invalidated_at IS NULL",
                at(now),
                email);
        jdbc.update(
                "UPDATE auth_proof SET invalidated_at=? WHERE target_email_key=? AND"
                        + " purpose='SIGNUP' AND consumed_at IS NULL AND invalidated_at IS NULL",
                at(now),
                email);
        jdbc.update(
                "UPDATE email_delivery SET"
                    + " status='CANCELLED',payload_ciphertext=NULL,payload_key_id=NULL,cancellation_requested_at=?,finished_at=?,lease_token=NULL,lease_until=NULL,updated_at=?"
                    + " WHERE challenge_id IN (SELECT challenge_id FROM auth_challenge WHERE"
                    + " target_email_key=? AND purpose='SIGNUP') AND status IN"
                    + " ('PENDING','PROCESSING')",
                at(now),
                at(now),
                at(now),
                email);
    }

    public void createChallenge(UUID key, String email, byte[] hash, Instant now) {
        jdbc.update(
                "INSERT INTO"
                    + " auth_challenge(challenge_id,purpose,target_email,target_email_key,secret_digest,attempt_count,created_at,expires_at,resend_available_at)"
                    + " VALUES (?,'SIGNUP',?,?,?,0,?,?,?)",
                id(key),
                email,
                email,
                hash,
                at(now),
                at(now.plusSeconds(600)),
                at(now.plusSeconds(60)));
    }

    public void createDelivery(UUID key, UUID challenge, byte[] payload, Instant now) {
        jdbc.update(
                "INSERT INTO"
                    + " email_delivery(delivery_id,challenge_id,status,payload_ciphertext,payload_key_id,payload_expires_at,attempt_count,next_attempt_at,created_at,updated_at)"
                    + " VALUES (?,?,'PENDING',?,'v1',?,0,?,?,?)",
                id(key),
                id(challenge),
                payload,
                at(now.plusSeconds(600)),
                at(now),
                at(now),
                at(now));
    }

    public void verify(UUID challenge, UUID proof, byte[] hash, String email, Instant now) {
        jdbc.update(
                "UPDATE auth_challenge SET verified_at=? WHERE challenge_id=?",
                at(now),
                id(challenge));
        jdbc.update(
                "INSERT INTO"
                    + " auth_proof(proof_id,challenge_id,purpose,proof_hash,target_email,target_email_key,created_at,expires_at)"
                    + " VALUES (?,?,'SIGNUP',?,?,?,?,?)",
                id(proof),
                id(challenge),
                hash,
                email,
                email,
                at(now),
                at(now.plusSeconds(600)));
    }

    public Optional<Proof> proof(byte[] hash) {
        return jdbc
                .query(
                        "SELECT * FROM auth_proof WHERE proof_hash=? AND purpose='SIGNUP'",
                        (r, n) ->
                                new Proof(
                                        uuid(r.getBytes("proof_id")),
                                        uuid(r.getBytes("challenge_id")),
                                        r.getString("target_email_key"),
                                        time(r, "expires_at"),
                                        time(r, "consumed_at"),
                                        time(r, "invalidated_at")),
                        hash)
                .stream()
                .findFirst();
    }

    public void consume(UUID proof, Instant now) {
        if (jdbc.update(
                        "UPDATE auth_proof SET consumed_at=? WHERE proof_id=? AND consumed_at IS"
                                + " NULL AND invalidated_at IS NULL AND expires_at>?",
                        at(now),
                        id(proof),
                        at(now))
                != 1) throw new ApiException(409, "PROOF_ALREADY_USED");
    }

    public void createAccount(UUID member, String email, String password, Instant now) {
        createAccountWithoutPassword(member, email, now);
        jdbc.update(
                "INSERT INTO password_credential(member_id,password_hash,password_changed_at) VALUES (?,?,?)",
                id(member), password, at(now));
    }

    public void createAccountWithoutPassword(UUID member, String email, Instant now) {
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " account(member_id,email,email_key,email_verified_at,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,?)",
                    id(member),
                    email,
                    email,
                    at(now),
                    at(now),
                    at(now));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
        }
    }

    public void assignOwner(String email, UUID member) {
        jdbc.update(
                "UPDATE auth_challenge SET member_id=? WHERE target_email_key=? AND member_id IS"
                        + " NULL",
                id(member),
                email);
        jdbc.update(
                "UPDATE auth_proof SET member_id=? WHERE target_email_key=? AND member_id IS NULL"
                        + " AND purpose='SIGNUP'",
                id(member),
                email);
        jdbc.update(
                "UPDATE email_delivery SET member_id=? WHERE challenge_id IN (SELECT challenge_id"
                        + " FROM auth_challenge WHERE member_id=?) AND member_id IS NULL",
                id(member),
                id(member));
    }

    public void createSession(UUID member, UUID session, UUID refresh, byte[] hash, Instant now) {
        jdbc.update(
                "INSERT INTO auth_session(session_id,member_id,created_at,expires_at) VALUES"
                        + " (?,?,?,?)",
                id(session),
                id(member),
                at(now),
                at(now.plusSeconds(1209600)));
        jdbc.update(
                "INSERT INTO"
                    + " refresh_token(refresh_token_id,session_id,token_hash,issued_at,expires_at)"
                    + " VALUES (?,?,?,?,?)",
                id(refresh),
                id(session),
                hash,
                at(now),
                at(now.plusSeconds(1209600)));
    }

    public boolean sessionActive(UUID member, UUID session, Instant now) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM auth_session WHERE session_id=? AND member_id=? AND"
                                + " revoked_at IS NULL AND expires_at>?",
                        Integer.class,
                        id(session),
                        id(member),
                        at(now))
                == 1;
    }

    public String email(UUID member) {
        return jdbc.queryForObject(
                "SELECT email FROM account WHERE member_id=?", String.class, id(member));
    }

    public List<UUID> dueDeliveries(Instant now) {
        return jdbc.query(
                "SELECT delivery_id FROM email_delivery WHERE (status='PENDING' AND"
                    + " next_attempt_at<=?) OR (status='PROCESSING' AND lease_until<=?) ORDER BY"
                    + " created_at LIMIT 20",
                (r, n) -> uuid(r.getBytes(1)),
                at(now),
                at(now));
    }

    public Optional<Delivery> claim(UUID key, UUID lease, Instant now) {
        int count =
                jdbc.update(
                        "UPDATE email_delivery SET"
                            + " status='PROCESSING',lease_token=?,lease_until=?,attempt_count=attempt_count+1,updated_at=?"
                            + " WHERE delivery_id=? AND ((status='PENDING' AND next_attempt_at<=?)"
                            + " OR (status='PROCESSING' AND lease_until<=?))",
                        id(lease),
                        at(now.plusSeconds(120)),
                        at(now),
                        id(key),
                        at(now),
                        at(now));
        if (count == 0) return Optional.empty();
        return jdbc
                .query(
                        "SELECT * FROM email_delivery WHERE delivery_id=? AND lease_token=?",
                        (r, n) ->
                                new Delivery(
                                        key,
                                        uuid(r.getBytes("challenge_id")),
                                        r.getBytes("payload_ciphertext"),
                                        r.getInt("attempt_count"),
                                        lease,
                                        time(r, "payload_expires_at")),
                        id(key),
                        id(lease))
                .stream()
                .findFirst();
    }

    public boolean deliverable(Delivery job, Instant now) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM email_delivery d JOIN auth_challenge c ON"
                            + " c.challenge_id=d.challenge_id LEFT JOIN member m ON"
                            + " m.member_id=d.member_id WHERE d.delivery_id=? AND d.lease_token=?"
                            + " AND d.status='PROCESSING' AND d.cancellation_requested_at IS NULL"
                            + " AND d.payload_expires_at>? AND c.expires_at>? AND c.invalidated_at"
                            + " IS NULL AND c.verified_at IS NULL AND (d.member_id IS NULL OR"
                            + " m.status='ACTIVE')",
                        Integer.class,
                        id(job.id()),
                        id(job.lease()),
                        at(now),
                        at(now))
                == 1;
    }

    public void finish(Delivery job, String status, String error, Instant next, Instant now) {
        boolean terminal = !status.equals("PENDING");
        jdbc.update(
                "UPDATE email_delivery SET"
                    + " status=?,last_error_code=?,next_attempt_at=?,accepted_at=?,finished_at=?,payload_ciphertext=CASE"
                    + " WHEN ? THEN NULL ELSE payload_ciphertext END,payload_key_id=CASE WHEN ?"
                    + " THEN NULL ELSE payload_key_id"
                    + " END,lease_token=NULL,lease_until=NULL,updated_at=? WHERE delivery_id=? AND"
                    + " lease_token=? AND status='PROCESSING'",
                status,
                error,
                at(next),
                status.equals("ACCEPTED") ? at(now) : null,
                terminal ? at(now) : null,
                terminal,
                terminal,
                at(now),
                id(job.id()),
                id(job.lease()));
    }

    public void cleanup(Instant now) {
        jdbc.update(
                "UPDATE email_delivery SET"
                    + " status='CANCELLED',payload_ciphertext=NULL,payload_key_id=NULL,finished_at=?,lease_token=NULL,lease_until=NULL,updated_at=?"
                    + " WHERE status IN ('PENDING','PROCESSING') AND payload_expires_at<=?",
                at(now),
                at(now),
                at(now));
        jdbc.update(
                "DELETE FROM email_delivery WHERE status IN ('ACCEPTED','FAILED','CANCELLED') AND"
                        + " payload_expires_at<=?",
                at(now));
        jdbc.update("DELETE FROM auth_proof WHERE expires_at<=? AND NOT EXISTS"
                + " (SELECT 1 FROM social_signup_context c WHERE c.proof_id=auth_proof.proof_id)", at(now));
        jdbc.update(
                "DELETE FROM auth_challenge WHERE expires_at<=? AND NOT EXISTS (SELECT 1 FROM"
                        + " auth_proof p WHERE p.challenge_id=auth_challenge.challenge_id) AND NOT"
                        + " EXISTS (SELECT 1 FROM email_delivery d WHERE"
                        + " d.challenge_id=auth_challenge.challenge_id)",
                at(now));
    }
}
