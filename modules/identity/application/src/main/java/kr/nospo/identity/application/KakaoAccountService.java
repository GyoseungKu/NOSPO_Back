package kr.nospo.identity.application;

import kr.nospo.identity.contract.LoginMethods;
import kr.nospo.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class KakaoAccountService {
    public record Linked(List<String> loginMethods) {
        public Linked { loginMethods = List.copyOf(loginMethods); }
    }
    private final KakaoAccountStore store;
    private final VerificationStore verification;
    private final LoginMethodStore loginStore;
    private final LoginMethods methods;
    private final KakaoOAuthStore oauth;
    private final KakaoClient client;
    private final KakaoOAuthSettings settings;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public KakaoAccountService(KakaoAccountStore store, VerificationStore verification, LoginMethodStore loginStore,
            LoginMethods methods, KakaoOAuthStore oauth, KakaoClient client, KakaoOAuthSettings settings,
            Secrets secrets, DatabaseGuard guard, Clock clock, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.store = store; this.verification = verification; this.loginStore = loginStore; this.methods = methods;
        this.oauth = oauth; this.client = client; this.settings = settings; this.secrets = secrets;
        this.guard = guard; this.clock = clock; this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private void lock(UUID member, UUID session) {
        if (member == null || session == null) throw new ApiException(401, "INVALID_CREDENTIALS");
        loginStore.lockActiveMember(member);
        if (!store.lockSession(member, session, clock.instant()))
            throw new ApiException(401, "INVALID_CREDENTIALS");
    }

    private void purpose(String purpose) {
        if (purpose == null || !Set.of("CHANGE_EMAIL", "ADD_EMAIL_LOGIN", "LINK_KAKAO", "WITHDRAWAL").contains(purpose))
            throw new ApiException(422, "VALIDATION_ERROR");
    }

    private byte[] hash(String proof) {
        if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) throw new ApiException(400, "INVALID_PROOF");
        return secrets.hash(proof);
    }

    private KakaoAccountStore.Proof owned(String proof, String purpose, UUID member, UUID session) {
        var p = store.proof(hash(proof), true).orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
        if (!member.equals(p.member()) || !session.equals(p.session())) throw new ApiException(400, "INVALID_PROOF");
        if (!purpose.equals(p.purpose())) throw new ApiException(422, "PROOF_PURPOSE_MISMATCH");
        if (p.consumedAt() != null) throw new ApiException(409, "PROOF_ALREADY_USED");
        if (p.invalidatedAt() != null || !p.expiresAt().isAfter(clock.instant())) throw new ApiException(410, "PROOF_EXPIRED");
        return p;
    }

    public KakaoOAuthService.Started reauthenticate(UUID member, UUID session, String purpose) {
        purpose(purpose);
        guard.rateLimit("kakao-reauth:" + member, 10, Duration.ofMinutes(15), clock.instant());
        return start(member, session, "REAUTH", purpose, null);
    }

    public KakaoOAuthService.Started authorizeLink(UUID member, UUID session, String reauthProof) {
        guard.rateLimit("kakao-link-start:" + member, 10, Duration.ofMinutes(15), clock.instant());
        return start(member, session, "LINK", "LINK_KAKAO", reauthProof);
    }

    private KakaoOAuthService.Started start(UUID member, UUID session, String type, String purpose, String reauth) {
        String state = secrets.token(), cookie = secrets.token();
        String url = client.reauthenticationUrl(state, settings.redirectUri());
        return tx.execute(s -> {
            lock(member, session);
            UUID proofId = null;
            if (type.equals("REAUTH")) {
                if (!methods.get(member).loginMethods().contains("KAKAO"))
                    throw new ApiException(409, "KAKAO_LOGIN_NOT_ENABLED");
            } else {
                var proof = owned(reauth, "LINK_KAKAO", member, session);
                if (!methods.get(member).canLinkKakao()) throw new ApiException(409, "SOCIAL_ACCOUNT_ALREADY_LINKED");
                proofId = proof.id();
                verification.consume(proofId, clock.instant());
            }
            oauth.createAuthenticatedFlow(UUID.randomUUID(), secrets.hash(state), secrets.hash(cookie), member, session,
                    settings.returnPath(null), type, purpose, proofId, clock.instant());
            return new KakaoOAuthService.Started(url, cookie);
        });
    }

    /** Called inside the common result exchange transaction; never opens a new login session. */
    @Transactional(propagation = Propagation.MANDATORY)
    public KakaoOAuthService.Exchanged exchange(KakaoOAuthStore.Flow flow, KakaoClient.User user, UUID member, UUID session) {
        if (!Objects.equals(member, flow.member()) || !Objects.equals(session, flow.session()))
            throw new ApiException(400, "INVALID_PROOF");
        lock(member, session);
        String token = secrets.token();
        Instant now = clock.instant();
        String result, proofPurpose, proofField;
        if (flow.type().equals("REAUTH")) {
            purpose(flow.purpose());
            if (loginStore.kakaoMember(user.subject()).filter(member::equals).isEmpty())
                throw new ApiException(401, "INVALID_CREDENTIALS");
            result = "REAUTH_SUCCESS"; proofPurpose = flow.purpose(); proofField = "reauthProof";
        } else if (flow.type().equals("LINK")) {
            if (flow.reauthProof() == null || !"LINK_KAKAO".equals(flow.purpose()))
                throw new ApiException(400, "INVALID_PROOF");
            var source = store.proof(flow.reauthProof()).orElseThrow(() -> new ApiException(400, "INVALID_PROOF"));
            if (!member.equals(source.member()) || !session.equals(source.session())
                    || !"LINK_KAKAO".equals(source.purpose()) || source.consumedAt() == null
                    || source.invalidatedAt() != null) throw new ApiException(400, "INVALID_PROOF");
            // The source proof was consumed at start. Password changes invalidate the flow's
            // session and all pending proofs; session locking serializes final writes with revocation.
            if (!methods.get(member).canLinkKakao() || loginStore.kakaoMember(user.subject()).isPresent())
                throw new ApiException(409, "SOCIAL_ACCOUNT_ALREADY_LINKED");
            result = "LINK_READY"; proofPurpose = "KAKAO_LINK"; proofField = "linkProof";
        } else throw new ApiException(400, "INVALID_PROOF");
        store.createProof(UUID.randomUUID(), member, session, proofPurpose, user.subject(), secrets.hash(token), now);
        Map<String, Object> data = new HashMap<>(Map.of("result", result, proofField, token, "expiresAt", now.plusSeconds(300)));
        if (result.equals("REAUTH_SUCCESS")) data.put("purpose", flow.purpose());
        return new KakaoOAuthService.Exchanged(Map.copyOf(data), null, null);
    }

    public Linked link(UUID member, UUID session, String linkProof, UUID key) {
        if (member == null || session == null) throw new ApiException(401, "INVALID_CREDENTIALS");
        if (key == null) throw new ApiException(422, "VALIDATION_ERROR");
        hash(linkProof);
        guard.rateLimit("kakao-link-complete:" + member, 20, Duration.ofMinutes(15), clock.instant());
        // Fixed-format UUID/token fields give a stable fingerprint across JVM restarts.
        byte[] fingerprint = secrets.fingerprint("kakao-link:" + session + ":" + linkProof);
        String context = "kakao-link:" + member + ":" + key;
        return tx.execute(s -> {
            lock(member, session); // Also serializes concurrent idempotency keys for one member.
            var previous = store.saved(member, key, clock.instant());
            if (previous.isPresent()) {
                if (!MessageDigest.isEqual(previous.get().fingerprint(), fingerprint))
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                return mapper.readValue(secrets.decrypt(previous.get().response(), context), Linked.class);
            }
            var proof = owned(linkProof, "KAKAO_LINK", member, session);
            if (!"KAKAO".equals(proof.provider()) || proof.subject() == null)
                throw new ApiException(400, "INVALID_PROOF");
            guard.lock("kakao-signup:subject:" + proof.subject(), clock.instant());
            if (!methods.get(member).canLinkKakao() || loginStore.kakaoMember(proof.subject()).isPresent())
                throw new ApiException(409, "SOCIAL_ACCOUNT_ALREADY_LINKED");
            loginStore.insertKakao(member, proof.subject(), clock.instant());
            verification.consume(proof.id(), clock.instant());
            var result = new Linked(methods.get(member).loginMethods());
            store.save(member, key, fingerprint, secrets.encrypt(mapper.writeValueAsBytes(result), context), clock.instant());
            return result;
        });
    }
}
