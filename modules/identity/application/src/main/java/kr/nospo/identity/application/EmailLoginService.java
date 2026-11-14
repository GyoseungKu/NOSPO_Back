package kr.nospo.identity.application;

import kr.nospo.identity.contract.*;
import kr.nospo.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class EmailLoginService {
    public record Confirmed(String emailLoginProof, Instant expiresAt) {
        @Override public String toString() { return "EmailLoginConfirmed[REDACTED]"; }
    }
    public record Enabled(List<String> loginMethods) {
        public Enabled { loginMethods = List.copyOf(loginMethods); }
    }
    private final EmailLoginStore store;
    private final VerificationStore verification;
    private final KakaoAccountStore reauth;
    private final LoginMethodStore loginStore;
    private final LoginMethods methods;
    private final SignupIdentity identity;
    private final IdentityStore deliveries;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public EmailLoginService(EmailLoginStore store, VerificationStore verification, KakaoAccountStore reauth,
            LoginMethodStore loginStore, LoginMethods methods, SignupIdentity identity, IdentityStore deliveries,
            Secrets secrets, DatabaseGuard guard, Clock clock, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.store = store; this.verification = verification; this.reauth = reauth; this.loginStore = loginStore;
        this.methods = methods; this.identity = identity; this.deliveries = deliveries; this.secrets = secrets;
        this.guard = guard; this.clock = clock; this.mapper = mapper;
        tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private void lock(UUID member, UUID session) {
        if (member == null || session == null) throw new ApiException(401, "INVALID_CREDENTIALS");
        verification.lockMember(member);
        if (!reauth.lockSession(member, session, clock.instant())) throw new ApiException(401, "INVALID_CREDENTIALS");
    }
    private void eligible(UUID member) {
        var status = methods.get(member);
        if (status.loginMethods().contains("EMAIL")) throw new ApiException(409, "EMAIL_LOGIN_ALREADY_ENABLED");
        if (!status.loginMethods().contains("KAKAO")) throw new ApiException(409, "KAKAO_LOGIN_NOT_ENABLED");
    }
    private byte[] hash(String token, String error) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ApiException(400, error);
        return secrets.hash(token);
    }
    public EmailVerificationService.Issued request(UUID member, UUID session, String reauthProof, String ip) {
        byte[] hash = hash(reauthProof, "INVALID_PROOF");
        Instant now = clock.instant();
        guard.rateLimit("email-login:ip:" + ip, 30, Duration.ofHours(1), now);
        guard.rateLimit("email-login:member:" + member, 5, Duration.ofHours(1), now);
        return tx.execute(s -> {
            lock(member, session); eligible(member);
            var proof = reauth.proof(hash, true).orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
            if (!member.equals(proof.member()) || !session.equals(proof.session())) throw new ApiException(400, "INVALID_PROOF");
            if (!"ADD_EMAIL_LOGIN".equals(proof.purpose())) throw new ApiException(422, "PROOF_PURPOSE_MISMATCH");
            if (!"KAKAO".equals(proof.provider()) || proof.subject() == null
                    || loginStore.kakaoMember(proof.subject()).filter(member::equals).isEmpty())
                throw new ApiException(400, "INVALID_PROOF");
            if (proof.consumedAt() != null) throw new ApiException(409, "PROOF_ALREADY_USED");
            if (proof.invalidatedAt() != null || !proof.expiresAt().isAfter(clock.instant())) throw new ApiException(410, "PROOF_EXPIRED");
            String email = identity.email(member);
            guard.rateLimit("email-login:email:" + email, 5, Duration.ofHours(1), clock.instant());
            guard.lock("email-login:target:" + email, clock.instant());
            if (verification.coolingDown("EMAIL_LOGIN", email, member, clock.instant()))
                throw new ApiException(429, "RATE_LIMITED", 60);
            store.invalidate(member, clock.instant());
            verification.consume(proof.id(), clock.instant());
            String token = secrets.token(); UUID challenge = UUID.randomUUID(), delivery = UUID.randomUUID();
            Instant issued = clock.instant();
            verification.createChallenge(challenge, member, session, "EMAIL_LOGIN", email, secrets.hash(token), issued);
            deliveries.createDelivery(delivery, challenge, secrets.encrypt(
                    (email + "\nLOGIN:" + token).getBytes(StandardCharsets.UTF_8), "mail:" + delivery), issued);
            store.ownDelivery(delivery, member);
            return new EmailVerificationService.Issued(challenge, issued.plusSeconds(600), issued.plusSeconds(60));
        });
    }
    public Confirmed confirm(UUID member, UUID session, String token) {
        byte[] digest = hash(token, "INVALID_VERIFICATION");
        guard.rateLimit("email-login-confirm:" + member, 30, Duration.ofMinutes(15), clock.instant());
        return tx.execute(s -> {
            lock(member, session); eligible(member);
            var challenge = store.challenge(digest).orElseThrow(() -> new ApiException(400, "INVALID_VERIFICATION"));
            if (!member.equals(challenge.member()) || !session.equals(challenge.session())) throw new ApiException(400, "INVALID_VERIFICATION");
            if (challenge.invalidated() != null) throw new ApiException(410, "VERIFICATION_EXPIRED");
            if (!identity.email(member).equals(challenge.email())) throw new ApiException(400, "INVALID_VERIFICATION");
            if (challenge.verified() != null) throw new ApiException(409, "CHALLENGE_ALREADY_VERIFIED");
            if (!challenge.expiry().isAfter(clock.instant())) throw new ApiException(410, "VERIFICATION_EXPIRED");
            Instant now = clock.instant(); String proof = secrets.token();
            verification.verified(challenge.id(), now);
            verification.createProof(UUID.randomUUID(), challenge.id(), member, session, "EMAIL_LOGIN",
                    challenge.email(), secrets.hash(proof), now, 600);
            return new Confirmed(proof, now.plusSeconds(600));
        });
    }
    public Enabled enable(UUID member, UUID session, String emailLoginProof, String password, UUID key) {
        if (member == null || session == null) throw new ApiException(401, "INVALID_CREDENTIALS");
        if (key == null) throw new ApiException(422, "VALIDATION_ERROR");
        byte[] digest = hash(emailLoginProof, "INVALID_PROOF");
        guard.rateLimit("email-login-enable:" + member, 20, Duration.ofMinutes(15), clock.instant());
        // Expensive Argon2id hashing stays outside DB locks; all authorization is rechecked below.
        String passwordHash = identity.passwordHash(password);
        byte[] fingerprint = secrets.fingerprint("email-login:" + session + ":" + emailLoginProof + ":" + password);
        String context = "email-login:" + member + ":" + key;
        return tx.execute(s -> {
            lock(member, session);
            var saved = store.saved(member, key, clock.instant());
            if (saved.isPresent()) {
                if (!MessageDigest.isEqual(saved.get().fingerprint(), fingerprint)) throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                return mapper.readValue(secrets.decrypt(saved.get().response(), context), Enabled.class);
            }
            eligible(member);
            var proof = store.proof(digest).orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
            if (!member.equals(proof.member()) || !session.equals(proof.session())) throw new ApiException(400, "INVALID_PROOF");
            if (proof.consumedAt() != null) throw new ApiException(409, "PROOF_ALREADY_USED");
            if (proof.invalidatedAt() != null || !proof.expiresAt().isAfter(clock.instant())) throw new ApiException(410, "PROOF_EXPIRED");
            if (!identity.email(member).equals(proof.email())) throw new ApiException(400, "INVALID_PROOF");
            store.insertCredential(member, passwordHash, clock.instant());
            verification.consume(proof.id(), clock.instant());
            store.invalidate(member, clock.instant());
            var result = new Enabled(methods.get(member).loginMethods());
            store.save(member, key, fingerprint, secrets.encrypt(mapper.writeValueAsBytes(result), context), clock.instant());
            return result;
        });
    }
}
