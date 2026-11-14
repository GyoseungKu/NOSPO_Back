package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface EmailLoginStore {
    record Proof(UUID id, UUID member, UUID session, String email, Instant expiresAt,
                 Instant consumedAt, Instant invalidatedAt) {}
    record Saved(byte[] fingerprint, byte[] response) {}

    Optional<VerificationStore.Challenge> challenge(byte[] digest);
    Optional<Proof> proof(byte[] digest);
    void invalidate(UUID member, Instant now);
    void ownDelivery(UUID delivery, UUID member);
    void insertCredential(UUID member, String passwordHash, Instant now);
    Optional<Saved> saved(UUID member, UUID key, Instant now);
    void save(UUID member, UUID key, byte[] fingerprint, byte[] response, Instant now);
}
