package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface KakaoOAuthStore {
    record Flow(UUID id, byte[] cookieHash, UUID member, UUID session, String returnPath,
                Instant expiresAt, Instant consumedAt, String type, String purpose, UUID reauthProof) {}
    record Exchange(UUID id, Flow flow, byte[] payload, Instant expiresAt, Instant consumedAt) {}

    void createFlow(UUID id, byte[] state, byte[] cookie, UUID member, UUID session,
                    String returnPath, Instant now);
    void createAuthenticatedFlow(UUID id, byte[] state, byte[] cookie, UUID member, UUID session,
                                 String returnPath, String type, String purpose, UUID reauthProof, Instant now);
    Optional<Flow> lockFlow(byte[] state);
    void consumeFlow(UUID id, Instant now);
    void createExchange(UUID id, UUID flow, byte[] code, byte[] payload, Instant now);
    Optional<Exchange> lockExchange(byte[] code);
    void consumeExchange(UUID id, Instant now);
    void createSignup(UUID proof, UUID flow, byte[] hash, KakaoClient.User user,
                      byte[] context, Instant now);
    void cleanup(Instant now);
}
