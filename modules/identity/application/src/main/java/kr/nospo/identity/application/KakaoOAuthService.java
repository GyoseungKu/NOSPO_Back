package kr.nospo.identity.application;

import kr.nospo.identity.contract.*;
import kr.nospo.identity.domain.EmailAddress;
import kr.nospo.member.contract.Members;
import kr.nospo.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class KakaoOAuthService {
    public record Started(String authorizationUrl, String cookie) {
        @Override public String toString() { return "Started[REDACTED]"; }
    }
    public record Callback(String exchangeCode, String error) {
        @Override public String toString() { return "Callback[REDACTED]"; }
    }
    public record SignupInfo(String email, boolean emailVerified, String nicknameSuggestion,
                             List<String> requiredFields, Instant expiresAt) {}
    public record Exchanged(Map<String, Object> data, String refresh, Instant refreshExpiresAt) {
        @Override public String toString() { return "Exchanged[REDACTED]"; }
    }

    private final KakaoOAuthStore store;
    private final KakaoClient client;
    private final KakaoOAuthSettings settings;
    private final Secrets secrets;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final SignupIdentity identity;
    private final LoginMethodStore loginStore;
    private final LoginMethods methods;
    private final IdentityStore accounts;
    private final Members members;
    private final DatabaseGuard guard;
    private final KakaoAccountService accountActions;
    private final RecoveryIdentity recovery;
    private final KakaoLinkAccess linkAccess;
    private final TransactionTemplate tx;

    public KakaoOAuthService(KakaoOAuthStore store, KakaoClient client, KakaoOAuthSettings settings,
            Secrets secrets, Clock clock, ObjectMapper mapper, SignupIdentity identity,
            LoginMethodStore loginStore, LoginMethods methods, IdentityStore accounts,
            Members members, DatabaseGuard guard, KakaoAccountService accountActions, RecoveryIdentity recovery, KakaoLinkAccess linkAccess,
            PlatformTransactionManager manager) {
        this.store = store; this.client = client; this.settings = settings; this.secrets = secrets;
        this.clock = clock; this.mapper = mapper; this.identity = identity;
        this.loginStore = loginStore; this.methods = methods; this.accounts = accounts;
        this.members = members; this.guard = guard;
        this.accountActions = accountActions;
        this.recovery = recovery;
        this.linkAccess = linkAccess;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Started authorize(String returnPath, UUID member, UUID session, String ip) {
        String path = settings.returnPath(returnPath);
        guard.rateLimit("kakao-start:" + ip, 30, Duration.ofMinutes(15), clock.instant());
        String state = secrets.token(), cookie = secrets.token();
        String url = client.authorizationUrl(state, settings.redirectUri());
        tx.executeWithoutResult(s -> {
            checkSession(member, session);
            store.createFlow(UUID.randomUUID(), secrets.hash(state), secrets.hash(cookie),
                    member, session, path, clock.instant());
        });
        return new Started(url, cookie);
    }

    private void checkSession(UUID member, UUID session) {
        if (member != null && (session == null || !identity.sessionActive(member, session, clock.instant())))
            throw new ApiException(401, "INVALID_CREDENTIALS");
    }

    private static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    public Callback callback(String state, String cookie, String code, String error, String requestUrl) {
        if (!settings.redirectUri().equals(requestUrl) || !token(state) || !token(cookie))
            return new Callback(null, "KAKAO_AUTH_FAILED");
        var flow = tx.execute(s -> {
            var found = store.lockFlow(secrets.hash(state));
            if (found.isEmpty()) return null;
            var f = found.get();
            if (f.consumedAt() != null || !f.expiresAt().isAfter(clock.instant())
                    || !MessageDigest.isEqual(f.cookieHash(), secrets.hash(cookie))) return null;
            store.consumeFlow(f.id(), clock.instant());
            return f;
        });
        if (flow == null) return new Callback(null, "KAKAO_AUTH_FAILED");
        if (error != null) return new Callback(null,
                "access_denied".equals(error) ? "KAKAO_AUTH_CANCELLED" : "KAKAO_AUTH_FAILED");
        if (code == null || code.isBlank() || code.length() > 2048)
            return new Callback(null, "KAKAO_AUTH_FAILED");
        try {
            checkSession(flow.member(), flow.session());
            // The claim above commits before network I/O: callback replay never repeats token exchange.
            var user = normalized(client.identify(code, settings.redirectUri()));
            String exchange = secrets.token();
            UUID id = UUID.randomUUID();
            tx.executeWithoutResult(s -> store.createExchange(id, flow.id(), secrets.hash(exchange),
                    secrets.encrypt(mapper.writeValueAsBytes(user), "oauth-exchange:" + id), clock.instant()));
            return new Callback(exchange, null);
        } catch (RuntimeException ignored) {
            // Never expose or log provider responses, tokens, authorization codes, or exception causes.
            return new Callback(null, "KAKAO_AUTH_FAILED");
        }
    }

    private KakaoClient.User normalized(KakaoClient.User user) {
        if (user == null || user.subject() == null || !user.subject().matches("[0-9]{1,128}"))
            throw new ApiException(400, "KAKAO_AUTH_FAILED");
        String email = null;
        if (user.emailVerified() && user.email() != null) {
            try { email = EmailAddress.normalize(user.email()); }
            catch (IllegalArgumentException ignored) { /* An unusable email requires separate email verification. */ }
        }
        String nickname = user.nickname();
        if (nickname != null && nickname.length() > 100) nickname = nickname.substring(0, 100);
        return new KakaoClient.User(user.subject(), email, email != null, nickname);
    }

    public Exchanged exchange(String code, UUID member, UUID session, String ip) {
        if (!token(code)) throw new ApiException(400, "INVALID_PROOF");
        guard.rateLimit("kakao-exchange:" + ip, 60, Duration.ofMinutes(15), clock.instant());
        return tx.execute(s -> {
            var e = store.lockExchange(secrets.hash(code))
                    .orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
            Instant now = clock.instant();
            if (e.consumedAt() != null) throw new ApiException(409, "PROOF_ALREADY_USED");
            if (!e.expiresAt().isAfter(now)) throw new ApiException(410, "PROOF_EXPIRED");
            if (!"LOGIN".equals(e.flow().type()) && member == null)
                throw new ApiException(401, "INVALID_CREDENTIALS");
            if (e.flow().member() != null
                    && (!e.flow().member().equals(member) || !e.flow().session().equals(session)))
                throw new ApiException(400, "INVALID_PROOF");
            checkSession(e.flow().member(), e.flow().session());
            if ("LINK".equals(e.flow().type())) linkAccess.check(member, session);
            var user = mapper.readValue(secrets.decrypt(e.payload(), "oauth-exchange:" + e.id()), KakaoClient.User.class);
            store.consumeExchange(e.id(), now);
            if (!"LOGIN".equals(e.flow().type()))
                return accountActions.exchange(e.flow(), user, member, session);
            var recoveryProof = recovery.kakao(user.subject(),now);
            if(recoveryProof.isPresent()) {
                var issued=recoveryProof.get();
                return new Exchanged(Map.of("result","RECOVERY_REQUIRED","recoveryProof",issued.recoveryProof(),
                        "expiresAt",issued.expiresAt(),"returnPath",e.flow().returnPath()),null,null);
            }
            var linked = loginStore.kakaoMember(user.subject());
            if (linked.isPresent()) {
                UUID id = linked.get();
                loginStore.lockActiveMember(id);
                var profile = members.get(id);
                var tokens = identity.openSession(id, now);
                var auth = new AuthResult(tokens.accessToken(), "Bearer", 900,
                        new AuthResult.Member(id, profile.nickname(), identity.email(id), profile.phoneNumber(),
                                false, profile.profileImageUrl(), methods.get(id).loginMethods(), profile.createdAt()));
                return new Exchanged(Map.of("result", "LOGIN_SUCCESS", "auth", auth,
                        "returnPath", e.flow().returnPath()), tokens.refreshToken(), tokens.refreshExpiresAt());
            }
            if (user.emailVerified() && accounts.emailExists(user.email()))
                return new Exchanged(Map.of("result", "EXISTING_ACCOUNT", "nextAction", "LOGIN_AND_LINK",
                        "returnPath", e.flow().returnPath()), null, null);
            String proof = secrets.token();
            UUID proofId = UUID.randomUUID();
            Instant expiry = now.plusSeconds(900);
            var fields = new ArrayList<>(List.of("nickname", "phoneNumber", "consents"));
            if (!user.emailVerified()) fields.add("emailProof");
            var info = new SignupInfo(user.email(), user.emailVerified(), user.nickname(), fields, expiry);
            store.createSignup(proofId, e.flow().id(), secrets.hash(proof), user,
                    secrets.encrypt(mapper.writeValueAsBytes(info), "social-signup:" + proofId), now);
            return new Exchanged(Map.of("result", "SIGNUP_REQUIRED", "signupProof", proof,
                    "expiresAt", expiry, "signupInfo", info, "returnPath", e.flow().returnPath()), null, null);
        });
    }

    public void cleanup() { tx.executeWithoutResult(s -> store.cleanup(clock.instant())); }
}
