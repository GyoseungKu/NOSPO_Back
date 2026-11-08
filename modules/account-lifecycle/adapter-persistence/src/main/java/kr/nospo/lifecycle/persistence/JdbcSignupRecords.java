package kr.nospo.lifecycle.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.lifecycle.application.SignupRecords;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class JdbcSignupRecords implements SignupRecords {
    private final JdbcTemplate jdbc;

    public JdbcSignupRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Saved> find(String scope, UUID key, Instant now) {
        jdbc.update(
                "DELETE FROM idempotency_record WHERE scope_key=? AND idempotency_key=? AND"
                        + " expires_at<=?",
                scope,
                id(key),
                at(now));
        return jdbc
                .query(
                        "SELECT request_fingerprint,response_ciphertext FROM idempotency_record"
                                + " WHERE scope_key=? AND http_method='POST' AND"
                                + " request_path='/api/v1/auth/signup' AND idempotency_key=? AND"
                                + " status='COMPLETED'",
                        (r, n) -> new Saved(r.getBytes(1), r.getBytes(2)),
                        scope,
                        id(key))
                .stream()
                .findFirst();
    }

    public void save(
            UUID member, String scope, UUID key, byte[] fingerprint, byte[] response, Instant now) {
        jdbc.update(
                "INSERT INTO"
                    + " idempotency_record(idempotency_id,member_id,scope_key,http_method,request_path,idempotency_key,request_fingerprint,status,response_status,response_ciphertext,created_at,expires_at)"
                    + " VALUES (?,?,?,'POST','/api/v1/auth/signup',?,?,'COMPLETED',201,?,?,?)",
                id(UUID.randomUUID()),
                id(member),
                scope,
                id(key),
                fingerprint,
                response,
                at(now),
                at(now.plusSeconds(86400)));
    }

    public void cleanup(Instant now) {
        jdbc.update("DELETE FROM idempotency_record WHERE expires_at<=?", at(now));
    }
}
