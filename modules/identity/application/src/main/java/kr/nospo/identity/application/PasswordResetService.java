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
public class PasswordResetService {
    public record Issued(UUID verificationId, Instant expiresAt, Instant resendAvailableAt) {}

    public record Verified(String resetProof, Instant expiresAt) {}

    private final VerificationStore store;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final IdentityStore deliveries;
    private final SignupIdentity identity;
    private final TransactionTemplate tx;

    public PasswordResetService(
            VerificationStore store,
            Secrets secrets,
            DatabaseGuard guard,
            Clock clock,
            IdentityStore deliveries,
            SignupIdentity identity,
            PlatformTransactionManager manager) {
        this.store = store;
        this.secrets = secrets;
        this.guard = guard;
        this.clock = clock;
        this.deliveries = deliveries;
        this.identity = identity;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Issued request(String input, String ip) {
        String email = EmailAddress.normalize(input);
        Instant now = clock.instant();
        guard.rateLimit("reset:ip:" + ip, 30, Duration.ofHours(1), now);
        guard.rateLimit("reset:email:" + email, 5, Duration.ofHours(1), now);
        return tx.execute(
                s -> {
                    guard.lock("reset:" + email, now);
                    if (store.coolingDown("PASSWORD_RESET", email, null, now))
                        throw new ApiException(429, "RATE_LIMITED", 60);
                    store.invalidate("PASSWORD_RESET", email, null, now);
                    UUID verification = UUID.randomUUID();
                    var member = store.member(email);
                    if (member.isPresent()) {
                        store.lockMember(member.get());
                        member = store.member(email);
                    }
                    String code =
                            String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
                    store.createChallenge(
                            verification,
                            member.orElse(null),
                            null,
                            "PASSWORD_RESET",
                            email,
                            secrets.fingerprint(verification + ":" + code),
                            now);
                    if (member.isPresent()) {
                        UUID delivery = UUID.randomUUID();
                        deliveries.createDelivery(
                                delivery,
                                verification,
                                secrets.encrypt(
                                        (email + "\nRESET:" + code)
                                                .getBytes(StandardCharsets.UTF_8),
                                        "mail:" + delivery),
                                now);
                    }
                    return new Issued(verification, now.plusSeconds(600), now.plusSeconds(60));
                });
    }

    public Verified verify(UUID verification, String code) {
        Verified result =
                tx.execute(
                        s -> {
                            var found = store.lockChallenge(verification, "PASSWORD_RESET");
                            if (found.isEmpty()) return null;
                            var c = found.get();
                            Instant now = clock.instant();
                            if (c.attempts() >= 5
                                    || !c.expiry().isAfter(now)
                                    || c.verified() != null
                                    || c.invalidated() != null) return null;
                            store.attempt(c.id());
                            if (c.member() == null
                                    || !MessageDigest.isEqual(
                                            c.digest(),
                                            secrets.fingerprint(verification + ":" + code)))
                                return null;
                            String proof = secrets.token();
                            store.verified(c.id(), now);
                            store.createProof(
                                    UUID.randomUUID(),
                                    c.id(),
                                    c.member(),
                                    null,
                                    "PASSWORD_RESET",
                                    c.email(),
                                    secrets.hash(proof),
                                    now,
                                    600);
                            return new Verified(proof, now.plusSeconds(600));
                        });
        if (result == null) throw new ApiException(400, "INVALID_VERIFICATION");
        return result;
    }

    public void complete(String proof, String password, String ip) {
        Instant now = clock.instant();
        guard.rateLimit("reset-complete:" + ip, 20, Duration.ofMinutes(15), now);
        byte[] digest = secrets.hash(proof);
        var initial =
                store.proof(digest, "PASSWORD_RESET", now, false)
                        .orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
        String hash = identity.passwordHash(password);
        tx.executeWithoutResult(
                s -> {
                    store.lockMember(initial.member());
                    var p =
                            store.proof(digest, "PASSWORD_RESET", clock.instant(), true)
                                    .orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
                    if (store.member(p.email()).filter(p.member()::equals).isEmpty())
                        throw new ApiException(400, "INVALID_PROOF");
                    store.changePassword(p.member(), hash, clock.instant());
                    store.consume(p.id(), clock.instant());
                    store.invalidateCredentials(p.member(), clock.instant());
                });
    }
}
