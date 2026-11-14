package kr.nospo.identity.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Identity-owned proof validation and account creation, composed by account-lifecycle. */
public interface KakaoSignupIdentity {
    record Context(String email, boolean emailVerified, String nicknameSuggestion,
                   List<String> requiredFields, Instant expiresAt) {
        public Context { requiredFields = List.copyOf(requiredFields); }
    }

    record Verified(UUID proofId, UUID flowId, String subject, String email,
                    SignupIdentity.Verified emailProof) {}

    Context context(String signupProof);

    /** Must run in the caller's signup transaction; locks are retained through account creation. */
    Verified verifyForSignup(String signupProof, String emailProof);

    void createAccount(UUID member, Verified verified, Instant now);
}
