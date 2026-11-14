package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface KakaoAccountStore {
    record Proof(UUID id, UUID member, UUID session, String purpose, String provider, String subject,
                 Instant expiresAt, Instant consumedAt, Instant invalidatedAt) {}
    record Saved(byte[] fingerprint, byte[] response) {}

    boolean lockSession(UUID member, UUID session, Instant now);
    Optional<Proof> proof(byte[] hash, boolean lock);
    Optional<Proof> proof(UUID id);
    void createProof(UUID id, UUID member, UUID session, String purpose, String subject, byte[] hash, Instant now);
    Optional<Saved> saved(UUID member, UUID key, Instant now);
    void save(UUID member, UUID key, byte[] fingerprint, byte[] response, Instant now);
}
