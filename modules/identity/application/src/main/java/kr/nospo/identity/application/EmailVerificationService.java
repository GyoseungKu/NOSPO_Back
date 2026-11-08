package kr.nospo.identity.application;

import kr.nospo.identity.domain.EmailAddress;
import kr.nospo.support.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class EmailVerificationService {
    public record Issued(UUID verificationId, Instant expiresAt, Instant resendAvailableAt) {}

    public record Confirmed(String emailProof, String email, Instant expiresAt) {}

    private final IdentityStore store;
    private final DatabaseGuard guard;
    private final Secrets secrets;
    private final Clock clock;
    private final TransactionTemplate tx;

    public EmailVerificationService(
            IdentityStore store,
            DatabaseGuard guard,
            Secrets secrets,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.guard = guard;
        this.secrets = secrets;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public boolean available(String email) {
        return !store.emailExists(EmailAddress.normalize(email));
    }

    public Issued request(String input, String ip) {
        String email = EmailAddress.normalize(input);
        Instant now = clock.instant();
        guard.rateLimit("signup:ip:" + ip, 30, Duration.ofHours(1), now);
        guard.rateLimit("signup:email:" + email, 5, Duration.ofHours(1), now);
        return tx.execute(
                status -> {
                    guard.lock("signup:email:" + email, now);
                    if (store.emailExists(email))
                        throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
                    store.resendAt(email)
                            .filter(t -> t.isAfter(now))
                            .ifPresent(
                                    t -> {
                                        throw new ApiException(
                                                429,
                                                "RATE_LIMITED",
                                                Math.max(1, Duration.between(now, t).toSeconds()));
                                    });
                    store.invalidate(email, now);
                    UUID challenge = UUID.randomUUID(), delivery = UUID.randomUUID();
                    String token = secrets.token();
                    store.createChallenge(challenge, email, secrets.hash(token), now);
                    store.createDelivery(
                            delivery,
                            challenge,
                            secrets.encrypt(
                                    (email + "\n" + token).getBytes(StandardCharsets.UTF_8),
                                    "mail:" + delivery),
                            now);
                    return new Issued(challenge, now.plusSeconds(600), now.plusSeconds(60));
                });
    }

    public Confirmed confirm(String token) {
        checkToken(token);
        byte[] hash = secrets.hash(token);
        return tx.execute(
                status -> {
                    var initial =
                            store.challenge(hash)
                                    .orElseThrow(
                                            () -> new ApiException(400, "INVALID_VERIFICATION"));
                    Instant now = clock.instant();
                    guard.lock("signup:email:" + initial.email(), now);
                    var challenge =
                            store.challenge(hash)
                                    .orElseThrow(
                                            () -> new ApiException(400, "INVALID_VERIFICATION"));
                    if (challenge.invalidatedAt() != null)
                        throw new ApiException(410, "VERIFICATION_EXPIRED");
                    if (challenge.verifiedAt() != null)
                        throw new ApiException(409, "CHALLENGE_ALREADY_VERIFIED");
                    if (!challenge.expiresAt().isAfter(now))
                        throw new ApiException(410, "VERIFICATION_EXPIRED");
                    String proof = secrets.token();
                    store.verify(
                            challenge.id(),
                            UUID.randomUUID(),
                            secrets.hash(proof),
                            challenge.email(),
                            now);
                    return new Confirmed(proof, challenge.email(), now.plusSeconds(600));
                });
    }

    public static void checkToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new ApiException(400, "INVALID_VERIFICATION");
    }
}
