package kr.nospo.lifecycle.application;

import kr.nospo.credit.contract.SignupCredits;
import kr.nospo.identity.contract.*;
import kr.nospo.member.contract.Members;
import kr.nospo.support.*;
import kr.nospo.terms.contract.Terms;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Composes module contracts; no identity/member/terms persistence dependencies. */
@Service
public class KakaoSignupService {
    public record Command(String signupProof, String nickname, String phoneNumber,
                          String emailProof, List<Terms.Consent> consents) {
        @Override public String toString() { return "KakaoSignupCommand[REDACTED]"; }
    }

    private static final String PATH = "/api/v1/auth/signup/kakao";
    private final KakaoSignupIdentity kakao;
    private final SignupIdentity identity;
    private final LoginMethods methods;
    private final Members members;
    private final Terms terms;
    private final SignupCredits credits;
    private final SignupRecords records;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public KakaoSignupService(KakaoSignupIdentity kakao, SignupIdentity identity, LoginMethods methods,
            Members members, Terms terms, SignupCredits credits, SignupRecords records, Secrets secrets,
            DatabaseGuard guard, Clock clock, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.kakao = kakao;
        this.identity = identity;
        this.methods = methods;
        this.members = members;
        this.terms = terms;
        this.credits = credits;
        this.records = records;
        this.secrets = secrets;
        this.guard = guard;
        this.clock = clock;
        this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public SignupService.Completed signup(UUID key, Command command) {
        if (key == null || command == null || command.signupProof() == null
                || !command.signupProof().matches("[A-Za-z0-9_-]{43}") || command.consents() == null
                || (command.emailProof() != null && !command.emailProof().matches("[A-Za-z0-9_-]{43}")))
            throw new ApiException(422, "VALIDATION_ERROR");
        String scope = "signup:kakao:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(secrets.hash(command.signupProof()));
        byte[] fingerprint = secrets.fingerprint(mapper.writeValueAsString(command));
        guard.rateLimit("signup-proof:" + scope, 10, Duration.ofMinutes(15), clock.instant());

        return tx.execute(status -> {
            guard.lock(scope + ":" + key, clock.instant());
            Instant now = clock.instant();
            var previous = records.find(scope, PATH, key, now);
            // Success replay is deliberately checked before consumed/expired proof or context lookup.
            if (previous.isPresent()) {
                if (!MessageDigest.isEqual(previous.get().fingerprint(), fingerprint))
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                var completed = mapper.readValue(secrets.decrypt(previous.get().response(), scope + ":" + key),
                        SignupService.Completed.class);
                members.get(completed.data().member().memberId());
                if (!completed.refreshExpiresAt().minusSeconds(1209600 - 900).isAfter(now))
                    throw new ApiException(409, "SIGNUP_ALREADY_COMPLETED_LOGIN_REQUIRED");
                return completed;
            }

            var verified = kakao.verifyForSignup(command.signupProof(), command.emailProof());
            now = clock.instant();
            var member = members.create(command.nickname(), command.phoneNumber(), now);
            terms.recordSignup(member.memberId(), command.consents(), now);
            kakao.createAccount(member.memberId(), verified, now);
            credits.grant(member.memberId(), now);
            var tokens = identity.openSession(member.memberId(), now);
            var result = new SignupResult(tokens.accessToken(), "Bearer", 900,
                    new SignupResult.Member(member.memberId(), member.nickname(), verified.email(),
                            member.phoneNumber(), false, null, methods.get(member.memberId()).loginMethods(), now));
            var completed = new SignupService.Completed(result, tokens.refreshToken(), tokens.refreshExpiresAt());
            records.save(member.memberId(), scope, PATH, key, fingerprint,
                    secrets.encrypt(mapper.writeValueAsBytes(completed), scope + ":" + key), now);
            return completed;
        });
    }
}
