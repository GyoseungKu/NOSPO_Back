package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface IdentityStore {
    record Challenge(
            UUID id,
            String email,
            Instant expiresAt,
            Instant resendAt,
            Instant verifiedAt,
            Instant invalidatedAt) {}

    record Proof(
            UUID id,
            UUID challengeId,
            String email,
            Instant expiresAt,
            Instant consumedAt,
            Instant invalidatedAt) {}

    record Delivery(
            UUID id,
            UUID challengeId,
            byte[] payload,
            int attempts,
            UUID lease,
            Instant expiresAt) {}

    Optional<Challenge> challenge(byte[] hash);

    Optional<Challenge> challenge(UUID id);

    Optional<Instant> resendAt(String email);

    boolean emailExists(String email);

    void invalidate(String email, Instant now);

    void createChallenge(UUID id, String email, byte[] hash, Instant now);

    void createDelivery(UUID id, UUID challenge, byte[] payload, Instant now);

    void verify(UUID challenge, UUID proof, byte[] hash, String email, Instant now);

    Optional<Proof> proof(byte[] hash);

    void consume(UUID proof, Instant now);

    void createAccount(UUID member, String email, String passwordHash, Instant now);

    void createAccountWithoutPassword(UUID member, String email, Instant now);

    void assignOwner(String email, UUID member);

    void createSession(UUID member, UUID session, UUID refresh, byte[] hash, Instant now);

    boolean sessionActive(UUID member, UUID session, Instant now);

    String email(UUID member);

    List<UUID> dueDeliveries(Instant now);

    Optional<Delivery> claim(UUID id, UUID lease, Instant now);

    boolean deliverable(Delivery job, Instant now);

    void finish(Delivery job, String status, String error, Instant next, Instant now);

    void cleanup(Instant now);
}
