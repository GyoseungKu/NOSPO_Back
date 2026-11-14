package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;
import kr.nospo.identity.application.KakaoSignupStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcKakaoSignupStore implements KakaoSignupStore {
    private final JdbcTemplate db;

    public JdbcKakaoSignupStore(JdbcTemplate db) { this.db = db; }

    private Instant time(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public Optional<Proof> proof(byte[] hash, boolean lock) {
        return db.query("SELECT p.*,c.flow_id,c.payload_ciphertext FROM auth_proof p"
                        + " LEFT JOIN social_signup_context c ON c.proof_id=p.proof_id"
                        + " WHERE p.proof_hash=? AND p.purpose='KAKAO_SIGNUP' AND p.provider='KAKAO'"
                        + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Proof(uuid(r.getBytes("proof_id")),
                        r.getBytes("flow_id") == null ? null : uuid(r.getBytes("flow_id")),
                        r.getString("provider_subject"), r.getBytes("payload_ciphertext"),
                        time(r, "expires_at"), time(r, "consumed_at"), time(r, "invalidated_at")), hash)
                .stream().findFirst();
    }

    public void complete(UUID proof, UUID flow, UUID member, Instant now) {
        db.update("UPDATE auth_proof SET member_id=? WHERE proof_id=?", id(member), id(proof));
        // Used OAuth material is removed rather than linked by an email string.
        db.update("DELETE FROM social_signup_context WHERE proof_id=?", id(proof));
        db.update("DELETE FROM oauth_exchange WHERE flow_id=?", id(flow));
        db.update("DELETE FROM oauth_flow WHERE flow_id=? AND NOT EXISTS"
                + " (SELECT 1 FROM social_signup_context c WHERE c.flow_id=oauth_flow.flow_id)", id(flow));
    }
}
