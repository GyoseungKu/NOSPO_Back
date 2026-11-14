package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface AccountStore {
    record Credential(UUID member, String hash) {}

    record Refresh(
            UUID session,
            UUID member,
            Instant expiry,
            Instant revoked,
            UUID token,
            Instant used,
            Instant tokenRevoked,
            Instant tokenExpiry) {}

    Optional<Credential> credential(String email);

    String lockPassword(UUID member);

    Optional<Refresh> lockRefresh(byte[] digest);

    void rotate(Refresh old, UUID next, byte[] digest, Instant now);

    Optional<UUID> session(byte[] digest);

    void revoke(UUID session, Instant now);
}
