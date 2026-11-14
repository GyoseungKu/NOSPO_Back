package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface VerificationStore {
    record Challenge(
            UUID id,
            UUID member,
            UUID session,
            String email,
            byte[] digest,
            int attempts,
            Instant expiry,
            Instant verified,
            Instant invalidated) {}

    record Proof(UUID id, UUID member, UUID session, String email) {}

    void lockMember(UUID member);

    boolean activeSession(UUID member, UUID session, Instant now);

    Optional<UUID> member(String email);

    boolean emailExists(String email);

    String password(UUID member);

    void changePassword(UUID member, String hash, Instant now);

    void changeEmail(UUID member, String email, Instant now);

    void invalidateCredentials(UUID member, Instant now);

    void invalidate(String purpose, String email, UUID member, Instant now);

    boolean coolingDown(String purpose, String email, UUID member, Instant now);

    void createChallenge(
            UUID id,
            UUID member,
            UUID session,
            String purpose,
            String email,
            byte[] digest,
            Instant now);

    Optional<Challenge> lockChallenge(UUID id, String purpose);

    void attempt(UUID id);

    void verified(UUID id, Instant now);

    void createProof(
            UUID id,
            UUID challenge,
            UUID member,
            UUID session,
            String purpose,
            String email,
            byte[] digest,
            Instant now,
            int seconds);

    Optional<Proof> proof(byte[] digest, String purpose, Instant now, boolean lock);

    void consume(UUID proof, Instant now);
}
