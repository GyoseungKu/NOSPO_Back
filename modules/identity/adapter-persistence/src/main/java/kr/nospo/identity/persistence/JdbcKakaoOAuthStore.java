package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;
import kr.nospo.identity.application.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcKakaoOAuthStore implements KakaoOAuthStore {
    private final JdbcTemplate db;
    public JdbcKakaoOAuthStore(JdbcTemplate db) { this.db = db; }
    private byte[] bytes(UUID id) { return id == null ? null : id(id); }
    private UUID key(byte[] value) { return value == null ? null : uuid(value); }
    private Instant time(ResultSet r, String name) throws SQLException {
        var t = r.getTimestamp(name); return t == null ? null : t.toInstant();
    }
    private Flow flow(ResultSet r) throws SQLException {
        return new Flow(uuid(r.getBytes("flow_id")), r.getBytes("cookie_hash"),
                key(r.getBytes("member_id")), key(r.getBytes("session_id")), r.getString("return_path"),
                time(r, "flow_expiry"), time(r, "flow_consumed"), r.getString("flow_type"),
                r.getString("purpose"), key(r.getBytes("reauth_proof_id")));
    }
    public void createFlow(UUID id, byte[] state, byte[] cookie, UUID member, UUID session,
                           String path, Instant now) {
        db.update("INSERT INTO oauth_flow(flow_id,state_hash,cookie_hash,member_id,session_id,"
                        + "return_path,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                id(id), state, cookie, bytes(member), bytes(session), path, at(now), at(now.plusSeconds(300)));
    }
    public Optional<Flow> lockFlow(byte[] state) {
        return db.query("SELECT *,expires_at AS flow_expiry,consumed_at AS flow_consumed"
                        + " FROM oauth_flow WHERE state_hash=? FOR UPDATE", (r, n) -> flow(r), state)
                .stream().findFirst();
    }
    public void createAuthenticatedFlow(UUID id, byte[] state, byte[] cookie, UUID member, UUID session,
            String path, String type, String purpose, UUID reauthProof, Instant now) {
        db.update("INSERT INTO oauth_flow(flow_id,state_hash,cookie_hash,member_id,session_id,return_path,"
                        + "flow_type,purpose,reauth_proof_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id(id), state, cookie, id(member), id(session), path, type, purpose, bytes(reauthProof),
                at(now), at(now.plusSeconds(300)));
    }
    public void consumeFlow(UUID id, Instant now) {
        db.update("UPDATE oauth_flow SET consumed_at=? WHERE flow_id=?", at(now), id(id));
    }
    public void createExchange(UUID id, UUID flow, byte[] code, byte[] payload, Instant now) {
        db.update("INSERT INTO oauth_exchange(exchange_id,flow_id,code_hash,payload_ciphertext,expires_at)"
                        + " VALUES (?,?,?,?,?)", id(id), id(flow), code, payload, at(now.plusSeconds(60)));
    }
    public Optional<Exchange> lockExchange(byte[] code) {
        return db.query("SELECT e.*,f.cookie_hash,f.member_id,f.session_id,f.return_path,"
                        + "f.flow_type,f.purpose,f.reauth_proof_id,"
                        + "f.expires_at AS flow_expiry,f.consumed_at AS flow_consumed"
                        + " FROM oauth_exchange e JOIN oauth_flow f ON f.flow_id=e.flow_id"
                        + " WHERE e.code_hash=? FOR UPDATE",
                (r, n) -> new Exchange(uuid(r.getBytes("exchange_id")), flow(r),
                        r.getBytes("payload_ciphertext"), time(r, "expires_at"), time(r, "consumed_at")), code)
                .stream().findFirst();
    }
    public void consumeExchange(UUID id, Instant now) {
        db.update("UPDATE oauth_exchange SET consumed_at=?,payload_ciphertext=NULL WHERE exchange_id=?",
                at(now), id(id));
    }
    public void createSignup(UUID proof, UUID flow, byte[] hash, KakaoClient.User user,
                             byte[] context, Instant now) {
        db.update("INSERT INTO auth_proof(proof_id,purpose,proof_hash,target_email,target_email_key,"
                        + "provider,provider_subject,created_at,expires_at) VALUES (?,'KAKAO_SIGNUP',?,?,?,'KAKAO',?,?,?)",
                id(proof), hash, user.email(), user.email(), user.subject(), at(now), at(now.plusSeconds(900)));
        db.update("INSERT INTO social_signup_context(proof_id,flow_id,payload_ciphertext) VALUES (?,?,?)",
                id(proof), id(flow), context);
    }
    public void cleanup(Instant now) {
        db.update("DELETE FROM social_signup_context WHERE proof_id IN (SELECT proof_id FROM auth_proof"
                + " WHERE expires_at<=? OR consumed_at IS NOT NULL OR invalidated_at IS NOT NULL)", at(now));
        db.update("DELETE FROM oauth_exchange WHERE expires_at<=?", at(now));
        db.update("DELETE FROM oauth_flow WHERE expires_at<=? AND NOT EXISTS"
                + " (SELECT 1 FROM oauth_exchange e WHERE e.flow_id=oauth_flow.flow_id) AND NOT EXISTS"
                + " (SELECT 1 FROM social_signup_context c WHERE c.flow_id=oauth_flow.flow_id)", at(now));
    }
}
