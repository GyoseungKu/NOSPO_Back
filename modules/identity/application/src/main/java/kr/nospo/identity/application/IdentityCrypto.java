package kr.nospo.identity.application;

import java.time.Instant;
import java.util.UUID;

public interface IdentityCrypto {
    boolean matchesPassword(String password, String hash);

    String hashPassword(String password);

    String accessToken(UUID member, UUID session, Instant issuedAt);
}
