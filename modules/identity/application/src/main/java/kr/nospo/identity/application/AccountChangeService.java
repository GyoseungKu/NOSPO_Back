package kr.nospo.identity.application;

import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.identity.domain.EmailAddress;
import kr.nospo.support.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class AccountChangeService {
    public record ProofResult(String reauthProof, Instant expiresAt) {}

    public record EmailProof(String emailChangeProof, Instant expiresAt) {}

    private final VerificationStore store;
    private final EmailLoginStore emailLogin;
    private final IdentityCrypto crypto;
    private final SignupIdentity identity;
    private final IdentityStore deliveries;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AccountChangeService(
            VerificationStore store,
            EmailLoginStore emailLogin,
            IdentityCrypto crypto,
            SignupIdentity identity,
            IdentityStore deliveries,
            Secrets secrets,
            DatabaseGuard guard,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.emailLogin = emailLogin;
        this.crypto = crypto;
        this.identity = identity;
        this.deliveries = deliveries;
        this.secrets = secrets;
        this.guard = guard;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private void lock(UUID member, UUID session) {
        store.lockMember(member);
        if (!store.activeSession(member, session, clock.instant()))
            throw new ApiException(401, "INVALID_CREDENTIALS");
    }

    private String checkPassword(UUID member, String password) {
        guard.rateLimit("reauth:" + member, 10, Duration.ofMinutes(15), clock.instant());
        String hash = store.password(member);
        if (!crypto.matchesPassword(password, hash))
            throw new ApiException(401, "INVALID_CREDENTIALS");
        return hash;
    }

    public ProofResult reauthenticate(UUID member, UUID session, String password, String purpose) {
        if (!Set.of("CHANGE_EMAIL", "WITHDRAWAL", "LINK_KAKAO").contains(purpose))
            throw new ApiException(422, "VALIDATION_ERROR");
        String hash = checkPassword(member, password);
        return tx.execute(
                s -> {
                    lock(member, session);
                    if (!hash.equals(store.password(member)))
                        throw new ApiException(401, "INVALID_CREDENTIALS");
                    Instant now = clock.instant();
                    String proof = secrets.token();
                    store.createProof(
                            UUID.randomUUID(),
                            null,
                            member,
                            session,
                            purpose,
                            null,
                            secrets.hash(proof),
                            now,
                            300);
                    return new ProofResult(proof, now.plusSeconds(300));
                });
    }

    public void password(UUID member, UUID session, String current, String replacement) {
        String previous = checkPassword(member, current), next = identity.passwordHash(replacement);
        tx.executeWithoutResult(
                s -> {
                    lock(member, session);
                    if (!previous.equals(store.password(member)))
                        throw new ApiException(401, "INVALID_CREDENTIALS");
                    Instant now = clock.instant();
                    store.changePassword(member, next, now);
                    store.invalidateCredentials(member, now);
                });
    }

    private VerificationStore.Proof owned(String token, String purpose, UUID member, UUID session) {
        var proof =
                store.proof(secrets.hash(token), purpose, clock.instant(), true)
                        .orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
        if (!member.equals(proof.member()) || !session.equals(proof.session()))
            throw new ApiException(400, "INVALID_PROOF");
        return proof;
    }

    public PasswordResetService.Issued requestEmail(
            UUID member, UUID session, String input, String reauth) {
        String email = EmailAddress.normalize(input);
        Instant now = clock.instant();
        guard.rateLimit("change-email:member:" + member, 5, Duration.ofHours(1), now);
        guard.rateLimit("change-email:target:" + email, 5, Duration.ofHours(1), now);
        return tx.execute(
                s -> {
                    lock(member, session);
                    var proof = owned(reauth, "CHANGE_EMAIL", member, session);
                    if (store.emailExists(email))
                        throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
                    if (store.coolingDown("EMAIL_CHANGE", email, member, now))
                        throw new ApiException(429, "RATE_LIMITED", 60);
                    store.invalidate("EMAIL_CHANGE", email, member, now);
                    store.consume(proof.id(), now);
                    UUID verification = UUID.randomUUID(), delivery = UUID.randomUUID();
                    String code =
                            String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
                    store.createChallenge(
                            verification,
                            member,
                            session,
                            "EMAIL_CHANGE",
                            email,
                            secrets.fingerprint(verification + ":" + code),
                            now);
                    deliveries.createDelivery(
                            delivery,
                            verification,
                            secrets.encrypt(
                                    (email + "\nCHANGE:" + code).getBytes(StandardCharsets.UTF_8),
                                    "mail:" + delivery),
                            now);
                    return new PasswordResetService.Issued(
                            verification, now.plusSeconds(600), now.plusSeconds(60));
                });
    }

    public EmailProof verifyEmail(UUID member, UUID session, UUID verification, String code) {
        EmailProof result =
                tx.execute(
                        s -> {
                            lock(member, session);
                            var found = store.lockChallenge(verification, "EMAIL_CHANGE");
                            if (found.isEmpty()) return null;
                            var c = found.get();
                            Instant now = clock.instant();
                            if (!member.equals(c.member())
                                    || !session.equals(c.session())
                                    || c.attempts() >= 5
                                    || !c.expiry().isAfter(now)
                                    || c.invalidated() != null
                                    || c.verified() != null) return null;
                            store.attempt(c.id());
                            if (!MessageDigest.isEqual(
                                    c.digest(), secrets.fingerprint(verification + ":" + code)))
                                return null;
                            String proof = secrets.token();
                            store.verified(c.id(), now);
                            store.createProof(
                                    UUID.randomUUID(),
                                    c.id(),
                                    member,
                                    session,
                                    "EMAIL_CHANGE",
                                    c.email(),
                                    secrets.hash(proof),
                                    now,
                                    600);
                            return new EmailProof(proof, now.plusSeconds(600));
                        });
        if (result == null) throw new ApiException(400, "INVALID_VERIFICATION");
        return result;
    }

    public String completeEmail(UUID member, UUID session, String token) {
        return tx.execute(
                s -> {
                    lock(member, session);
                    var proof = owned(token, "EMAIL_CHANGE", member, session);
                    Instant now = clock.instant();
                    if (store.emailExists(proof.email()))
                        throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
                    store.changeEmail(member, proof.email(), now);
                    emailLogin.invalidate(member, now);
                    store.consume(proof.id(), now);
                    // Recovery material issued to the old email must no longer change this account.
                    store.invalidate("PASSWORD_RESET", null, member, now);
                    store.invalidate("EMAIL_CHANGE", null, member, now);
                    return proof.email();
                });
    }
}
