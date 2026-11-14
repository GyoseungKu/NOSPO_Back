package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface KakaoSignupStore {
    record Proof(UUID id, UUID flowId, String subject, byte[] context,
                 Instant expiresAt, Instant consumedAt, Instant invalidatedAt) {}

    Optional<Proof> proof(byte[] hash, boolean lock);

    void complete(UUID proof, UUID flow, UUID member, Instant now);
}
