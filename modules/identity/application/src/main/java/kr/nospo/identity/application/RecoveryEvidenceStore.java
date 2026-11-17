package kr.nospo.identity.application;

import kr.nospo.identity.contract.RecoveryIdentity.Evidence;
import java.time.Instant;
import java.util.*;

public interface RecoveryEvidenceStore {
    record Target(UUID member, UUID operation, Instant deadline) {}
    record Challenge(UUID id, Target target, Instant expiresAt, Instant verifiedAt, Instant invalidatedAt) {}
    Optional<Target> target(String kind, String identifier);
    void lock(Target target, Instant now);
    void request(Target target, UUID challenge, String email, byte[] hash, Instant now);
    Optional<Challenge> challenge(byte[] hash);
    void verify(UUID challenge, Instant now);
    void proof(Target target, String method, byte[] hash, Instant now, Instant expiresAt);
    Evidence inspect(byte[] hash);
    void consume(byte[] hash, Evidence evidence, Instant now);
}
