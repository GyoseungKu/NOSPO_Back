package kr.nospo.support;

import static kr.nospo.support.SqlIds.at;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;

@Component
public class DatabaseGuard {
    private final JdbcTemplate jdbc;
    private final Secrets secrets;
    private final TransactionTemplate independent;

    public DatabaseGuard(JdbcTemplate jdbc, Secrets secrets, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Missing-key DELETEs at MySQL's default REPEATABLE_READ take gap locks.
        // Different rate scopes can then deadlock when both insert their first counter.
        // The guard row already serializes the same scope; range locks are unnecessary.
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // The upsert acquires an exclusive row lock, including when no previous row existed.
    public void lock(String scope, Instant now) {
        jdbc.update(
                "INSERT INTO auth_guard(scope_hash,expires_at) VALUES (?,?) ON DUPLICATE KEY UPDATE"
                        + " expires_at=VALUES(expires_at)",
                secrets.fingerprint(scope),
                at(now.plus(Duration.ofDays(2))));
    }

    public void rateLimit(String scope, int limit, Duration window, Instant now) {
        independent.executeWithoutResult(
                tx -> {
                    // Always acquire the scope guard before touching its counter.
                    lock("rate:" + scope, now);
                    byte[] key = secrets.fingerprint(scope);
                    jdbc.update(
                            "DELETE FROM auth_rate_limit WHERE scope_hash=? AND expires_at<=?",
                            key,
                            at(now));
                    var counts =
                            jdbc.query(
                                    "SELECT request_count,expires_at FROM auth_rate_limit WHERE"
                                            + " scope_hash=?",
                                    (rs, n) ->
                                            new Counter(
                                                    rs.getInt(1), rs.getTimestamp(2).toInstant()),
                                    key);
                    if (counts.isEmpty())
                        jdbc.update(
                                "INSERT INTO auth_rate_limit(scope_hash,request_count,expires_at)"
                                        + " VALUES (?,1,?)",
                                key,
                                at(now.plus(window)));
                    else {
                        var count = counts.getFirst();
                        if (count.value() >= limit)
                            throw new ApiException(
                                    429,
                                    "RATE_LIMITED",
                                    Math.max(1, Duration.between(now, count.expiry()).toSeconds()));
                        jdbc.update(
                                "UPDATE auth_rate_limit SET request_count=request_count+1 WHERE"
                                        + " scope_hash=?",
                                key);
                    }
                });
    }

    public void cleanup(Instant now) {
        jdbc.update("DELETE FROM auth_rate_limit WHERE expires_at<=?", at(now));
        jdbc.update("DELETE FROM auth_guard WHERE expires_at<=?", at(now));
    }

    private record Counter(int value, Instant expiry) {}
}
