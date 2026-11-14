package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import kr.nospo.identity.application.*;
import kr.nospo.lifecycle.application.SignupService;
import kr.nospo.support.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Account linking/reauthentication plus the inherited email and Kakao regression suite. */
class KakaoAccountTests extends KakaoOAuthTests {
    @Autowired KakaoAccountService actions;
    @MockitoSpyBean KakaoAccountStore accountStore;
    record Account(SignupService.Completed signup, UUID member, UUID session) {}
    @BeforeEach
    void authenticatedUrl() {
        when(kakao.reauthenticationUrl(anyString(), anyString())).thenAnswer(i ->
                "https://kauth.kakao.com/oauth/authorize?state=" + i.getArgument(0));
    }
    Account emailAccount(String email, String nickname) {
        var completed = signup.signup(UUID.randomUUID(), command(proof(email), nickname));
        return new Account(completed, completed.data().member().memberId(),
                UUID.fromString(decoder.decode(completed.data().accessToken()).getClaimAsString("sid")));
    }
    String passwordProof(Account account, String purpose) {
        return changes.reauthenticate(account.member(), account.session(), " valid password ", purpose).reauthProof();
    }
    String exchangeFor(KakaoOAuthService.Started started) {
        var response = oauth.callback(started.authorizationUrl().split("state=")[1], started.cookie(),
                "provider-code", null, settings.redirectUri());
        assertThat(response.error()).isNull(); return response.exchangeCode();
    }
    KakaoOAuthService.Exchanged finish(KakaoOAuthService.Started started, Account account) {
        return oauth.exchange(exchangeFor(started), account.member(), account.session(), "account-test");
    }
    String linkReady(Account account, String subject) {
        user(subject, "external@example.com", true);
        var started = actions.authorizeLink(account.member(), account.session(), passwordProof(account, "LINK_KAKAO"));
        var result = finish(started, account);
        assertThat(result.data()).containsEntry("result", "LINK_READY");
        assertThat(result.refresh()).isNull();
        return (String) result.data().get("linkProof");
    }

    @Test
    void linkHttpFlowBindsProofsAndPreservesMemberCreditsAndSessions() throws Exception {
        var a = emailAccount("reader@example.com", "reader");
        String bearer = "Bearer " + a.signup().data().accessToken();
        var reauth = mvc.perform(post("/api/v1/me/reauth/password").header("Authorization", bearer)
                        .contentType("application/json").content("{\"password\":\" valid password \",\"purpose\":\"LINK_KAKAO\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String proof = mapper.readTree(reauth.getContentAsString()).path("data").path("reauthProof").asString();
        var authorization = mvc.perform(post("/api/v1/me/login-methods/kakao/authorization").header("Authorization", bearer)
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("reauthProof", proof))))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("nospoKakaoFlow", true))
                .andExpect(cookie().secure("nospoKakaoFlow", true)).andReturn().getResponse();
        String url = mapper.readTree(authorization.getContentAsString()).path("data").path("authorizationUrl").asString();
        user("12345", "different@example.com", true);
        var callback = mvc.perform(get(settings.redirectUri()).param("state", url.split("state=")[1])
                        .param("code", "provider-code").cookie(authorization.getCookie("nospoKakaoFlow")))
                .andExpect(status().isFound()).andReturn().getResponse();
        String code = callback.getHeader("Location").split("exchangeCode=")[1];
        var exchanged = mvc.perform(post("/api/v1/auth/kakao/exchange").header("Authorization", bearer)
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("exchangeCode", code))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("LINK_READY"))
                .andExpect(cookie().doesNotExist("refreshToken")).andReturn().getResponse();
        String link = mapper.readTree(exchanged.getContentAsString()).path("data").path("linkProof").asString();
        UUID key = UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of("linkProof", link));
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/v1/me/login-methods/kakao").header("Authorization", bearer)
                            .header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.loginMethods[1]").value("KAKAO"))
                    .andExpect(cookie().doesNotExist("refreshToken"));
        mvc.perform(get("/api/v1/me/login-methods").header("Authorization", bearer))
                .andExpect(jsonPath("$.data.canLinkKakao").value(false)).andExpect(jsonPath("$.data.canAddEmail").value(false));
        assertThat(signup.me(a.member()).email()).isEqualTo("reader@example.com");
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("social_identity")).isEqualTo(1);
        code(() -> actions.link(a.member(), a.session(), link, UUID.randomUUID()), "PROOF_ALREADY_USED");
        code(() -> actions.link(a.member(), a.session(), secrets.token(), key), "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void kakaoReauthHttpReturnsPurposeProofWithoutNewLogin() throws Exception {
        var a = emailAccount("reader@example.com", "reader");
        loginMethods.recordVerifiedKakao(a.member(), "12345");
        user("12345", "changed@example.com", true);
        var response = mvc.perform(post("/api/v1/me/reauth/kakao")
                        .header("Authorization", "Bearer " + a.signup().data().accessToken())
                        .contentType("application/json").content("{\"purpose\":\"CHANGE_EMAIL\"}"))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("nospoKakaoFlow", true))
                .andReturn().getResponse();
        String url = mapper.readTree(response.getContentAsString()).path("data").path("authorizationUrl").asString();
        var result = finish(new KakaoOAuthService.Started(url, response.getCookie("nospoKakaoFlow").getValue()), a);
        assertThat(result.data()).containsEntry("result", "REAUTH_SUCCESS").containsEntry("purpose", "CHANGE_EMAIL");
        String proof = (String) result.data().get("reauthProof");
        var stored = accountStore.proof(secrets.hash(proof), false).orElseThrow();
        assertThat(stored.member()).isEqualTo(a.member()); assertThat(stored.session()).isEqualTo(a.session());
        assertThat(stored.expiresAt()).isEqualTo(clock.instant().plusSeconds(300));
        // Existing email-change workflow accepts the Kakao reauthentication proof.
        changes.requestEmail(a.member(), a.session(), "new@example.com", proof);
        code(() -> changes.requestEmail(a.member(), a.session(), "next@example.com", proof), "INVALID_PROOF");
        assertThat(result.refresh()).isNull(); assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
    }

    @Test
    void kakaoOnlyMemberCanReauthenticateWithoutPassword() {
        String signupProof = signupProof("12345", "reader@example.com", true);
        var completed = kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(signupProof, "reader", null));
        var a = new Account(completed, completed.data().member().memberId(),
                UUID.fromString(decoder.decode(completed.data().accessToken()).getClaimAsString("sid")));
        user("12345", null, false);
        var result = finish(actions.reauthenticate(a.member(), a.session(), "WITHDRAWAL"), a);
        assertThat(result.data()).containsEntry("result", "REAUTH_SUCCESS");
        assertThat(count("password_credential")).isZero(); assertThat(count("auth_session")).isEqualTo(1);
    }

    @Test
    void differentKakaoIdentityCannotReauthenticateOrSwitchLogin() {
        var a = emailAccount("first@example.com", "first");
        var b = emailAccount("second@example.com", "second");
        loginMethods.recordVerifiedKakao(a.member(), "12345"); loginMethods.recordVerifiedKakao(b.member(), "67890");
        user("67890", "first@example.com", true);
        String exchange = exchangeFor(actions.reauthenticate(a.member(), a.session(), "CHANGE_EMAIL"));
        code(() -> oauth.exchange(exchange, a.member(), a.session(), "test"), "INVALID_CREDENTIALS");
        assertThat(count("auth_session")).isEqualTo(2);
        assertThat(count("social_signup_context")).isZero();
    }

    @Test
    void proofPurposeOwnerAndSessionMismatchDoNotConsumeProof() {
        var a = emailAccount("first@example.com", "first");
        var b = emailAccount("second@example.com", "second");
        String wrong = passwordProof(a, "CHANGE_EMAIL");
        code(() -> actions.authorizeLink(a.member(), a.session(), wrong), "PROOF_PURPOSE_MISMATCH");
        String proof = passwordProof(a, "LINK_KAKAO");
        code(() -> actions.authorizeLink(b.member(), b.session(), proof), "INVALID_PROOF");
        var session = auth.login("first@example.com", " valid password ", "login");
        UUID sid = UUID.fromString(decoder.decode(session.accessToken()).getClaimAsString("sid"));
        code(() -> actions.authorizeLink(a.member(), sid, proof), "INVALID_PROOF");
        unconsumed(proof);
        actions.authorizeLink(a.member(), a.session(), proof);
        code(() -> actions.authorizeLink(a.member(), a.session(), proof), "PROOF_ALREADY_USED");
    }

    @Test
    void exchangeRequiresOriginalMemberSessionAndAuthentication() {
        var a = emailAccount("first@example.com", "first");
        var b = emailAccount("second@example.com", "second");
        user("12345", null, false);
        String exchange = exchangeFor(actions.authorizeLink(a.member(), a.session(), passwordProof(a, "LINK_KAKAO")));
        code(() -> oauth.exchange(exchange, null, null, "test"), "INVALID_CREDENTIALS");
        code(() -> oauth.exchange(exchange, b.member(), b.session(), "test"), "INVALID_PROOF");
        code(() -> oauth.exchange(exchange, a.member(), UUID.randomUUID(), "test"), "INVALID_PROOF");
        assertThat(oauth.exchange(exchange, a.member(), a.session(), "test").data()).containsEntry("result", "LINK_READY");
    }

    @Test
    void linkProofIsBoundToMemberSessionAndPurpose() {
        var a = emailAccount("first@example.com", "first");
        var b = emailAccount("second@example.com", "second");
        String link = linkReady(a, "12345");
        code(() -> actions.link(b.member(), b.session(), link, UUID.randomUUID()), "INVALID_PROOF");
        var other = auth.login("first@example.com", " valid password ", "login");
        UUID sid = UUID.fromString(decoder.decode(other.accessToken()).getClaimAsString("sid"));
        code(() -> actions.link(a.member(), sid, link, UUID.randomUUID()), "INVALID_PROOF");
        code(() -> actions.link(a.member(), a.session(), passwordProof(a, "LINK_KAKAO"), UUID.randomUUID()), "PROOF_PURPOSE_MISMATCH");
        unconsumed(link);
        assertThat(actions.link(a.member(), a.session(), link, UUID.randomUUID()).loginMethods()).contains("KAKAO");
    }

    @Test
    void expiredProofsAndFlowAreRejected() {
        var a = emailAccount("reader@example.com", "reader");
        String expired = passwordProof(a, "LINK_KAKAO"); clock.advance(300);
        code(() -> actions.authorizeLink(a.member(), a.session(), expired), "PROOF_EXPIRED");
        String link = linkReady(a, "12345"); clock.advance(300);
        code(() -> actions.link(a.member(), a.session(), link, UUID.randomUUID()), "PROOF_EXPIRED");
        var started = actions.authorizeLink(a.member(), a.session(), passwordProof(a, "LINK_KAKAO"));
        clock.advance(300);
        assertThat(oauth.callback(started.authorizationUrl().split("state=")[1], started.cookie(), "code", null,
                settings.redirectUri()).error()).isEqualTo("KAKAO_AUTH_FAILED");
        assertThat(count("social_identity")).isZero();
    }

    @Test
    void passwordChangeBlocksPendingLink() {
        var a = emailAccount("reader@example.com", "reader");
        String link = linkReady(a, "12345");
        changes.password(a.member(), a.session(), " valid password ", "new valid password");
        code(() -> actions.link(a.member(), a.session(), link, UUID.randomUUID()), "INVALID_CREDENTIALS");
        assertThat(count("social_identity")).isZero();
    }

    @Test
    void logoutBlocksPendingReauthExchangeAndLinkCompletion() {
        var a = emailAccount("first@example.com", "first");
        loginMethods.recordVerifiedKakao(a.member(), "12345"); user("12345", null, false);
        String exchange = exchangeFor(actions.reauthenticate(a.member(), a.session(), "CHANGE_EMAIL"));
        auth.logout(a.session(), null);
        code(() -> oauth.exchange(exchange, a.member(), a.session(), "test"), "INVALID_CREDENTIALS");
        var b = emailAccount("second@example.com", "second");
        String link = linkReady(b, "67890"); auth.logout(b.session(), null);
        code(() -> actions.link(b.member(), b.session(), link, UUID.randomUUID()), "INVALID_CREDENTIALS");
        assertThat(count("social_identity")).isEqualTo(1);
    }

    @Test
    void parallelReauthExchangeIssuesOneProofAndNoNewSessions() throws Exception {
        var a = emailAccount("reader@example.com", "reader");
        loginMethods.recordVerifiedKakao(a.member(), "12345"); user("12345", null, false);
        String exchange = exchangeFor(actions.reauthenticate(a.member(), a.session(), "CHANGE_EMAIL"));
        Callable<String> call = () -> {
            try { oauth.exchange(exchange, a.member(), a.session(), "race"); return "OK"; }
            catch (ApiException e) { return e.code(); }
        };
        assertThat(race(call, call)).containsExactlyInAnyOrder("OK", "PROOF_ALREADY_USED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_proof WHERE purpose='CHANGE_EMAIL'"
                + " AND provider='KAKAO'", Integer.class)).isEqualTo(1);
        assertThat(count("auth_session")).isEqualTo(1);
    }

    @Test
    void invalidatedSourceProofCannotProduceLinkReady() {
        var a = emailAccount("reader@example.com", "reader");
        String proof = passwordProof(a, "LINK_KAKAO");
        var started = actions.authorizeLink(a.member(), a.session(), proof);
        user("12345", null, false);
        String exchange = exchangeFor(started);
        jdbc.update("UPDATE auth_proof SET invalidated_at=? WHERE proof_hash=?", at(clock.instant()), secrets.hash(proof));
        code(() -> oauth.exchange(exchange, a.member(), a.session(), "test"), "INVALID_PROOF");
    }

    @Test
    void otherMembersKakaoIsRejectedAtExchangeAndFinalWrite() {
        var a = emailAccount("first@example.com", "first");
        var b = emailAccount("second@example.com", "second");
        String ready = linkReady(a, "12345");
        loginMethods.recordVerifiedKakao(b.member(), "12345");
        code(() -> actions.link(a.member(), a.session(), ready, UUID.randomUUID()), "SOCIAL_ACCOUNT_ALREADY_LINKED");
        unconsumed(ready);
        user("12345", null, false);
        var started = actions.authorizeLink(a.member(), a.session(), passwordProof(a, "LINK_KAKAO"));
        String exchange = exchangeFor(started);
        code(() -> oauth.exchange(exchange, a.member(), a.session(), "test"), "SOCIAL_ACCOUNT_ALREADY_LINKED");
        assertThat(count("member")).isEqualTo(2); assertThat(count("credit_transaction")).isEqualTo(2);
    }

    @Test
    void alreadyLinkedMemberAndUnsupportedPurposesAreRejected() {
        var a = emailAccount("reader@example.com", "reader");
        code(() -> actions.reauthenticate(a.member(), a.session(), "CHANGE_EMAIL"), "KAKAO_LOGIN_NOT_ENABLED");
        code(() -> actions.reauthenticate(a.member(), a.session(), "LOGIN"), "VALIDATION_ERROR");
        code(() -> changes.reauthenticate(a.member(), a.session(), " valid password ", "LOGIN"), "VALIDATION_ERROR");
        loginMethods.recordVerifiedKakao(a.member(), "12345");
        String proof = passwordProof(a, "LINK_KAKAO");
        code(() -> actions.authorizeLink(a.member(), a.session(), proof), "SOCIAL_ACCOUNT_ALREADY_LINKED");
        unconsumed(proof);
    }

    @Test
    void linkWriteFailureRollsBackAndAllowsRetry() {
        var a = emailAccount("reader@example.com", "reader");
        String link = linkReady(a, "12345"); UUID key = UUID.randomUUID();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated failure"))
                .when(accountStore).save(any(UUID.class), any(UUID.class), any(byte[].class), any(byte[].class), any(Instant.class));
        assertThatThrownBy(() -> actions.link(a.member(), a.session(), link, key))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(count("social_identity")).isZero(); unconsumed(link);
        reset(accountStore);
        assertThat(actions.link(a.member(), a.session(), link, key).loginMethods()).contains("KAKAO");
    }

    List<String> race(Callable<String> firstCall, Callable<String> secondCall) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return firstCall.call(); });
            var second = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return secondCall.call(); });
            start.countDown(); return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
    String linkOutcome(Account a, String link, UUID key) {
        try { actions.link(a.member(), a.session(), link, key); return "OK"; }
        catch (ApiException e) { return e.code(); }
    }
    @Test
    void parallelSameLinkKeyReplaysOneResult() throws Exception {
        var a = emailAccount("reader@example.com", "reader"); String link = linkReady(a, "12345"); UUID key = UUID.randomUUID();
        assertThat(race(() -> linkOutcome(a, link, key), () -> linkOutcome(a, link, key))).containsExactly("OK", "OK");
        assertThat(count("social_identity")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE request_path='/api/v1/me/login-methods/kakao'",
                Integer.class)).isEqualTo(1);
    }
    @Test
    void parallelDifferentKeysConsumeLinkProofOnce() throws Exception {
        var a = emailAccount("reader@example.com", "reader"); String link = linkReady(a, "12345");
        assertThat(race(() -> linkOutcome(a, link, UUID.randomUUID()), () -> linkOutcome(a, link, UUID.randomUUID())))
                .containsExactlyInAnyOrder("OK", "PROOF_ALREADY_USED");
        assertThat(count("social_identity")).isEqualTo(1);
    }
    @Test
    void parallelMembersCannotClaimSameKakao() throws Exception {
        var a = emailAccount("first@example.com", "first"); var b = emailAccount("second@example.com", "second");
        String first = linkReady(a, "12345"), second = linkReady(b, "12345");
        assertThat(race(() -> linkOutcome(a, first, UUID.randomUUID()), () -> linkOutcome(b, second, UUID.randomUUID())))
                .containsExactlyInAnyOrder("OK", "SOCIAL_ACCOUNT_ALREADY_LINKED");
        assertThat(count("social_identity")).isEqualTo(1);
        assertThat(count("member")).isEqualTo(2); assertThat(count("credit_transaction")).isEqualTo(2);
    }
    @Test
    void parallelDifferentKakaosForOneMemberHaveOneWinner() throws Exception {
        var a = emailAccount("reader@example.com", "reader");
        String first = linkReady(a, "12345"), second = linkReady(a, "67890");
        assertThat(race(() -> linkOutcome(a, first, UUID.randomUUID()), () -> linkOutcome(a, second, UUID.randomUUID())))
                .containsExactlyInAnyOrder("OK", "SOCIAL_ACCOUNT_ALREADY_LINKED");
    }
    @Test
    void parallelLinkStartsConsumeReauthProofOnce() throws Exception {
        var a = emailAccount("reader@example.com", "reader"); String proof = passwordProof(a, "LINK_KAKAO");
        Callable<String> call = () -> {
            try { actions.authorizeLink(a.member(), a.session(), proof); return "OK"; }
            catch (ApiException e) { return e.code(); }
        };
        assertThat(race(call, call)).containsExactlyInAnyOrder("OK", "PROOF_ALREADY_USED");
    }
    @Test
    void reauthExchangeCodeIsSingleUseAndExpires() {
        var a = emailAccount("reader@example.com", "reader"); loginMethods.recordVerifiedKakao(a.member(), "12345");
        user("12345", null, false);
        String code = exchangeFor(actions.reauthenticate(a.member(), a.session(), "LINK_KAKAO"));
        oauth.exchange(code, a.member(), a.session(), "test");
        code(() -> oauth.exchange(code, a.member(), a.session(), "test"), "PROOF_ALREADY_USED");
        String expired = exchangeFor(actions.reauthenticate(a.member(), a.session(), "CHANGE_EMAIL"));
        clock.advance(60);
        code(() -> oauth.exchange(expired, a.member(), a.session(), "test"), "PROOF_EXPIRED");
    }
    @Test
    void cleanupPreservesReferencedReauthAndRestoredUniqueRejectsDuplicates() {
        var a = emailAccount("reader@example.com", "reader"); String proof = passwordProof(a, "LINK_KAKAO");
        clock.advance(60); actions.authorizeLink(a.member(), a.session(), proof); clock.advance(241);
        delivery.cleanup(); // Expired source proof is retained until its flow is gone.
        assertThat(accountStore.proof(secrets.hash(proof), false)).isPresent();
        clock.advance(60); oauth.cleanup(); delivery.cleanup();
        assertThat(accountStore.proof(secrets.hash(proof), false)).isEmpty();
        String first = signupProof("12345", "new@example.com", true);
        String second = signupProof("67890", "other@example.com", true);
        byte[] flow = jdbc.queryForObject("SELECT c.flow_id FROM social_signup_context c JOIN auth_proof p"
                + " ON p.proof_id=c.proof_id WHERE p.proof_hash=?", byte[].class, secrets.hash(first));
        assertThatThrownBy(() -> jdbc.update("UPDATE social_signup_context SET flow_id=? WHERE proof_id IN"
                        + " (SELECT proof_id FROM auth_proof WHERE proof_hash=?)", flow, secrets.hash(second)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test
    void accountEndpointsRequireAuthenticationOriginAndIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/me/reauth/kakao").contentType("application/json").content("{\"purpose\":\"CHANGE_EMAIL\"}"))
                .andExpect(status().isUnauthorized());
        var a = emailAccount("reader@example.com", "reader");
        String bearer = "Bearer " + a.signup().data().accessToken();
        String body = mapper.writeValueAsString(Map.of("linkProof", secrets.token()));
        mvc.perform(post("/api/v1/me/login-methods/kakao").header("Authorization", bearer)
                        .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/login-methods/kakao").header("Authorization", bearer)
                        .header("Origin", "https://evil.example").header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
}
