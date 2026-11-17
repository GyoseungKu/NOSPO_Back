package kr.nospo.identity.contract;

import java.time.Instant;
import java.util.*;

/** Evidence is bound to a retained withdrawal operation, never just an email address. */
public interface RecoveryIdentity {
    record Evidence(UUID member, UUID operation, String method) {}
    record Issued(String recoveryProof, Instant expiresAt) {
        @Override public String toString() { return "RecoveryProof[REDACTED]"; }
    }
    Evidence inspect(String proof);
    void consume(String proof, Evidence evidence, Instant now);
    Optional<Issued> kakao(String subject, Instant now);
}
