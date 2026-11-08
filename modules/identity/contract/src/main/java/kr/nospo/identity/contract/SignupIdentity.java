package kr.nospo.identity.contract;

import java.time.Instant;
import java.util.UUID;

public interface SignupIdentity {
    record Verified(UUID proofId, UUID challengeId, String email) {}

    record Tokens(String accessToken, String refreshToken, Instant refreshExpiresAt) {}

    Verified verifyForSignup(String proof, Instant now);

    void createAccount(UUID member, Verified proof, String passwordHash, Instant now);

    String passwordHash(String password);

    Tokens openSession(UUID member, Instant now);

    boolean sessionActive(UUID member, UUID session, Instant now);

    String email(UUID member);
}
