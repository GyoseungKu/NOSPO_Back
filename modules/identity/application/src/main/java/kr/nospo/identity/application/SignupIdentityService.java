package kr.nospo.identity.application;

import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.member.contract.Members;
import kr.nospo.support.*;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SignupIdentityService implements SignupIdentity {
    private final IdentityStore store;
    private final DatabaseGuard guard;
    private final Secrets secrets;
    private final IdentityCrypto crypto;
    private final Members members;

    public SignupIdentityService(
            IdentityStore store,
            DatabaseGuard guard,
            Secrets secrets,
            IdentityCrypto crypto,
            Members members) {
        this.store = store;
        this.guard = guard;
        this.secrets = secrets;
        this.crypto = crypto;
        this.members = members;
    }

    public Verified verifyForSignup(String token, Instant now) {
        EmailVerificationService.checkToken(token);
        byte[] hash = secrets.hash(token);
        var initial = store.proof(hash).orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
        guard.lock("signup:email:" + initial.email(), now);
        var proof = store.proof(hash).orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
        if (proof.invalidatedAt() != null || !proof.expiresAt().isAfter(now))
            throw new ApiException(410, "PROOF_EXPIRED");
        if (proof.consumedAt() != null) throw new ApiException(409, "PROOF_ALREADY_USED");
        if (store.emailExists(proof.email())) throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
        return new Verified(proof.id(), proof.challengeId(), proof.email());
    }

    public String passwordHash(String password) {
        if (password == null
                || password.codePointCount(0, password.length()) < 10
                || password.codePointCount(0, password.length()) > 128)
            throw new ApiException(422, "VALIDATION_ERROR");
        return crypto.hashPassword(password);
    }

    public void createAccount(UUID member, Verified proof, String passwordHash, Instant now) {
        store.createAccount(member, proof.email(), passwordHash, now);
        store.consume(proof.proofId(), now);
        store.assignOwner(proof.email(), member);
    }

    public Tokens openSession(UUID member, Instant now) {
        UUID session = UUID.randomUUID();
        String refresh = secrets.token();
        store.createSession(member, session, UUID.randomUUID(), secrets.hash(refresh), now);
        return new Tokens(
                crypto.accessToken(member, session, now), refresh, now.plusSeconds(1209600));
    }

    public boolean sessionActive(UUID member, UUID session, Instant now) {
        return members.active(member) && store.sessionActive(member, session, now);
    }

    public String email(UUID member) {
        return store.email(member);
    }
}
