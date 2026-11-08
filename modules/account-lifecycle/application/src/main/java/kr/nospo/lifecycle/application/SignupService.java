package kr.nospo.lifecycle.application;

import kr.nospo.credit.contract.SignupCredits;
import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.member.contract.Members;
import kr.nospo.support.*;
import kr.nospo.terms.contract.Terms;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class SignupService {
    public record Command(
            String nickname,
            String emailProof,
            String password,
            String phoneNumber,
            List<Terms.Consent> consents) {}

    public record Completed(SignupResult data, String refreshToken, Instant refreshExpiresAt) {}

    private final SignupIdentity identity;
    private final Members members;
    private final Terms terms;
    private final SignupCredits credits;
    private final SignupRecords records;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public SignupService(
            SignupIdentity identity,
            Members members,
            Terms terms,
            SignupCredits credits,
            SignupRecords records,
            Secrets secrets,
            DatabaseGuard guard,
            Clock clock,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.identity = identity;
        this.members = members;
        this.terms = terms;
        this.credits = credits;
        this.records = records;
        this.secrets = secrets;
        this.guard = guard;
        this.clock = clock;
        this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public boolean nicknameAvailable(String name) {
        return members.nicknameAvailable(name);
    }

    public Completed signup(UUID key, Command command) {
        if (command.emailProof() == null
                || !command.emailProof().matches("[A-Za-z0-9_-]{43}")
                || command.consents() == null) throw new ApiException(422, "VALIDATION_ERROR");
        String scope =
                "signup:"
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(secrets.hash(command.emailProof()));
        byte[] fingerprint = secrets.fingerprint(mapper.writeValueAsString(command));
        // Password hashing is intentionally outside the DB transaction/locks.
        String passwordHash = identity.passwordHash(command.password());
        return tx.execute(
                status -> {
                    Instant now = clock.instant();
                    guard.lock(scope + ":" + key, now);
                    var previous = records.find(scope, key, now);
                    if (previous.isPresent()) {
                        if (!MessageDigest.isEqual(previous.get().fingerprint(), fingerprint))
                            throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                        var completed =
                                mapper.readValue(
                                        secrets.decrypt(
                                                previous.get().response(), scope + ":" + key),
                                        Completed.class);
                        members.get(completed.data().member().memberId());
                        return completed;
                    }
                    var proof = identity.verifyForSignup(command.emailProof(), now);
                    var member = members.create(command.nickname(), command.phoneNumber(), now);
                    terms.recordSignup(member.memberId(), command.consents(), now);
                    identity.createAccount(member.memberId(), proof, passwordHash, now);
                    credits.grant(member.memberId(), now);
                    var tokens = identity.openSession(member.memberId(), now);
                    var result =
                            new SignupResult(
                                    tokens.accessToken(),
                                    "Bearer",
                                    900,
                                    new SignupResult.Member(
                                            member.memberId(),
                                            member.nickname(),
                                            proof.email(),
                                            member.phoneNumber(),
                                            false,
                                            null,
                                            List.of("EMAIL"),
                                            now));
                    var completed =
                            new Completed(result, tokens.refreshToken(), tokens.refreshExpiresAt());
                    records.save(
                            member.memberId(),
                            scope,
                            key,
                            fingerprint,
                            secrets.encrypt(mapper.writeValueAsBytes(completed), scope + ":" + key),
                            now);
                    return completed;
                });
    }

    public SignupResult.Member me(UUID memberId) {
        var member = members.get(memberId);
        return new SignupResult.Member(
                member.memberId(),
                member.nickname(),
                identity.email(memberId),
                member.phoneNumber(),
                false,
                null,
                List.of("EMAIL"),
                member.createdAt());
    }
}
