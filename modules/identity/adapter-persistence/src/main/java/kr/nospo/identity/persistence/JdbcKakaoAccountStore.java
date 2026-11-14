package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.identity.application.KakaoAccountStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcKakaoAccountStore implements KakaoAccountStore {
    private static final String PATH = "/api/v1/me/login-methods/kakao";
    private final JdbcTemplate db;
    public JdbcKakaoAccountStore(JdbcTemplate db) { this.db = db; }
    private UUID key(byte[] value) { return value == null ? null : uuid(value); }
    private Instant time(ResultSet row, String field) throws SQLException {
        var value = row.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    public boolean lockSession(UUID member, UUID session, Instant now) {
        return !db.query("SELECT session_id FROM auth_session WHERE member_id=? AND session_id=?"
                        + " AND revoked_at IS NULL AND expires_at>? FOR UPDATE",
                (r, n) -> r.getBytes(1), id(member), id(session), at(now)).isEmpty();
    }
    public Optional<Proof> proof(byte[] hash, boolean lock) {
        return db.query("SELECT * FROM auth_proof WHERE proof_hash=?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Proof(uuid(r.getBytes("proof_id")), key(r.getBytes("member_id")),
                        key(r.getBytes("session_id")), r.getString("purpose"), r.getString("provider"),
                        r.getString("provider_subject"), time(r, "expires_at"), time(r, "consumed_at"),
                        time(r, "invalidated_at")), hash).stream().findFirst();
    }
    public void createProof(UUID proof, UUID member, UUID session, String purpose, String subject, byte[] hash, Instant now) {
        db.update("INSERT INTO auth_proof(proof_id,member_id,session_id,purpose,provider,provider_subject,"
                        + "proof_hash,created_at,expires_at) VALUES (?,?,?,?,'KAKAO',?,?,?,?)",
                id(proof), id(member), id(session), purpose, subject, hash, at(now), at(now.plusSeconds(300)));
    }
    public Optional<Proof> proof(UUID proof) {
        return db.query("SELECT * FROM auth_proof WHERE proof_id=? FOR UPDATE",
                (r, n) -> new Proof(uuid(r.getBytes("proof_id")), key(r.getBytes("member_id")),
                        key(r.getBytes("session_id")), r.getString("purpose"), r.getString("provider"),
                        r.getString("provider_subject"), time(r, "expires_at"), time(r, "consumed_at"),
                        time(r, "invalidated_at")), id(proof)).stream().findFirst();
    }
    private String scope(UUID member) { return "kakao-link:" + member; }
    public Optional<Saved> saved(UUID member, UUID key, Instant now) {
        db.update("DELETE FROM idempotency_record WHERE scope_key=? AND http_method='POST' AND request_path=?"
                + " AND idempotency_key=? AND expires_at<=?", scope(member), PATH, id(key), at(now));
        return db.query("SELECT request_fingerprint,response_ciphertext FROM idempotency_record"
                        + " WHERE scope_key=? AND http_method='POST' AND request_path=? AND idempotency_key=?"
                        + " AND status='COMPLETED'", (r, n) -> new Saved(r.getBytes(1), r.getBytes(2)),
                scope(member), PATH, id(key)).stream().findFirst();
    }
    public void save(UUID member, UUID key, byte[] fingerprint, byte[] response, Instant now) {
        db.update("INSERT INTO idempotency_record(idempotency_id,member_id,scope_key,http_method,request_path,"
                        + "idempotency_key,request_fingerprint,status,response_status,response_ciphertext,created_at,expires_at)"
                        + " VALUES (?,?,?,'POST',?,?,?,'COMPLETED',200,?,?,?)",
                id(UUID.randomUUID()), id(member), scope(member), PATH, id(key), fingerprint, response,
                at(now), at(now.plusSeconds(86400)));
    }
}
