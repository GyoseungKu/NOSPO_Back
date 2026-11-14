package kr.nospo.identity.application;

import kr.nospo.identity.contract.*;
import kr.nospo.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.UUID;

@Service
public class KakaoSignupIdentityService implements KakaoSignupIdentity {
    private final KakaoSignupStore store;
    private final IdentityStore accounts;
    private final SignupIdentity identity;
    private final LoginMethodStore methods;
    private final DatabaseGuard guard;
    private final Secrets secrets;
    private final ObjectMapper mapper;
    private final Clock clock;

    public KakaoSignupIdentityService(KakaoSignupStore store, IdentityStore accounts,
            SignupIdentity identity, LoginMethodStore methods, DatabaseGuard guard,
            Secrets secrets, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.identity = identity;
        this.methods = methods;
        this.guard = guard;
        this.secrets = secrets;
        this.mapper = mapper;
        this.clock = clock;
    }

    private byte[] hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new ApiException(400, "INVALID_PROOF");
        return secrets.hash(token);
    }

    private KakaoSignupStore.Proof proof(byte[] hash, boolean lock) {
        return store.proof(hash, lock).orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
    }

    private Context validContext(KakaoSignupStore.Proof proof) {
        if (proof.consumedAt() != null) throw new ApiException(409, "PROOF_ALREADY_USED");
        if (proof.invalidatedAt() != null || !proof.expiresAt().isAfter(clock.instant()))
            throw new ApiException(410, "PROOF_EXPIRED");
        if (proof.context() == null) throw new ApiException(400, "INVALID_PROOF");
        var saved = mapper.readValue(secrets.decrypt(proof.context(), "social-signup:" + proof.id()), Context.class);
        return new Context(saved.email(), saved.emailVerified(), saved.nicknameSuggestion(),
                saved.requiredFields(), proof.expiresAt());
    }

    @Transactional(readOnly = true)
    public Context context(String signupProof) {
        return validContext(proof(hash(signupProof), false));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Verified verifyForSignup(String signupProof, String emailProof) {
        byte[] hash = hash(signupProof);
        var initial = proof(hash, false);
        // Serializes different proofs for the same provider subject, including the first signup.
        guard.lock("kakao-signup:subject:" + initial.subject(), clock.instant());
        var proof = proof(hash, true);
        var context = validContext(proof);
        if (methods.kakaoMember(proof.subject()).isPresent())
            throw new ApiException(409, "SOCIAL_ACCOUNT_ALREADY_LINKED");

        SignupIdentity.Verified verifiedEmail = null;
        String email;
        if (context.emailVerified() && context.email() != null) {
            if (emailProof != null) throw new ApiException(422, "VALIDATION_ERROR");
            email = context.email();
            guard.lock("signup:email:" + email, clock.instant());
        } else {
            if (emailProof == null) throw new ApiException(422, "VALIDATION_ERROR");
            verifiedEmail = identity.verifyForSignup(emailProof, clock.instant());
            email = verifiedEmail.email();
        }
        if (accounts.emailExists(email)) throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
        return new Verified(proof.id(), proof.flowId(), proof.subject(), email, verifiedEmail);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createAccount(UUID member, Verified verified, Instant now) {
        accounts.createAccountWithoutPassword(member, verified.email(), now);
        methods.insertKakao(member, verified.subject(), now);
        // Recheck expiry when actually consuming, after any waits on nickname/terms/email locks.
        Instant consumedAt = clock.instant();
        accounts.consume(verified.proofId(), consumedAt);
        if (verified.emailProof() != null) accounts.consume(verified.emailProof().proofId(), consumedAt);
        accounts.assignOwner(verified.email(), member);
        store.complete(verified.proofId(), verified.flowId(), member, consumedAt);
    }
}
