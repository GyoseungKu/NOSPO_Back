package kr.nospo.identity.application;

import java.time.Instant;
import java.util.UUID;

public interface IdentityCrypto {
    String hashPassword(String password);

    String accessToken(UUID member, UUID session, Instant issuedAt);
}
