package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.identity.application.*;
import kr.nospo.support.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcEmailLoginStore implements EmailLoginStore {
    private static final String PATH = "/api/v1/me/login-methods/email";
    private final JdbcTemplate db;
    public JdbcEmailLoginStore(JdbcTemplate db) { this.db = db; }
    private Instant time(ResultSet r, String field) throws SQLException {
        var t = r.getTimestamp(field); return t == null ? null : t.toInstant();
    }
    public Optional<VerificationStore.Challenge> challenge(byte[] digest) {
        return db.query("SELECT * FROM auth_challenge WHERE secret_digest=? AND purpose='EMAIL_LOGIN' FOR UPDATE",
                (r, n) -> new VerificationStore.Challenge(uuid(r.getBytes("challenge_id")), uuid(r.getBytes("member_id")),
                        uuid(r.getBytes("session_id")), r.getString("target_email_key"), r.getBytes("secret_digest"),
                        r.getInt("attempt_count"), time(r, "expires_at"), time(r, "verified_at"), time(r, "invalidated_at")),
                digest).stream().findFirst();
    }
    public Optional<Proof> proof(byte[] digest) {
        return db.query("SELECT * FROM auth_proof WHERE proof_hash=? AND purpose='EMAIL_LOGIN' FOR UPDATE",
                (r, n) -> new Proof(uuid(r.getBytes("proof_id")), uuid(r.getBytes("member_id")),
                        uuid(r.getBytes("session_id")), r.getString("target_email_key"), time(r, "expires_at"),
                        time(r, "consumed_at"), time(r, "invalidated_at")), digest).stream().findFirst();
    }
    public void invalidate(UUID member, Instant now) {
        db.update("UPDATE auth_challenge SET invalidated_at=? WHERE member_id=? AND purpose='EMAIL_LOGIN'",
                at(now), id(member));
        db.update("UPDATE auth_proof SET invalidated_at=? WHERE member_id=? AND purpose='EMAIL_LOGIN' AND consumed_at IS NULL",
                at(now), id(member));
        db.update("UPDATE email_delivery SET status='CANCELLED',payload_ciphertext=NULL,payload_key_id=NULL,"
                        + "cancellation_requested_at=?,finished_at=?,lease_token=NULL,lease_until=NULL,updated_at=?"
                        + " WHERE member_id=? AND status IN ('PENDING','PROCESSING') AND challenge_id IN"
                        + " (SELECT challenge_id FROM auth_challenge WHERE member_id=? AND purpose='EMAIL_LOGIN')",
                at(now), at(now), at(now), id(member), id(member));
    }
    public void ownDelivery(UUID delivery, UUID member) {
        db.update("UPDATE email_delivery SET member_id=? WHERE delivery_id=?", id(member), id(delivery));
    }
    public void insertCredential(UUID member, String hash, Instant now) {
        try {
            db.update("INSERT INTO password_credential(member_id,password_hash,password_changed_at) VALUES (?,?,?)",
                    id(member), hash, at(now));
        } catch (DuplicateKeyException e) { throw new ApiException(409, "EMAIL_LOGIN_ALREADY_ENABLED"); }
    }
    private String scope(UUID member) { return "email-login:" + member; }
    public Optional<Saved> saved(UUID member, UUID key, Instant now) {
        db.update("DELETE FROM idempotency_record WHERE scope_key=? AND http_method='POST' AND request_path=?"
                + " AND idempotency_key=? AND expires_at<=?", scope(member), PATH, id(key), at(now));
        return db.query("SELECT request_fingerprint,response_ciphertext FROM idempotency_record WHERE scope_key=?"
                        + " AND http_method='POST' AND request_path=? AND idempotency_key=? AND status='COMPLETED'",
                (r, n) -> new Saved(r.getBytes(1), r.getBytes(2)), scope(member), PATH, id(key)).stream().findFirst();
    }
    public void save(UUID member, UUID key, byte[] fingerprint, byte[] response, Instant now) {
        db.update("INSERT INTO idempotency_record(idempotency_id,member_id,scope_key,http_method,request_path,"
                        + "idempotency_key,request_fingerprint,status,response_status,response_ciphertext,created_at,expires_at)"
                        + " VALUES (?,?,?,'POST',?,?,?,'COMPLETED',200,?,?,?)",
                id(UUID.randomUUID()), id(member), scope(member), PATH, id(key), fingerprint, response,
                at(now), at(now.plusSeconds(86400)));
    }
}
