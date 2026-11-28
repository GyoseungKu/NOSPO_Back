package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import kr.nospo.identity.application.*;
import kr.nospo.identity.contract.KakaoSignupIdentity;
import kr.nospo.lifecycle.application.KakaoSignupService;
import kr.nospo.lifecycle.application.SignupService;
import kr.nospo.lifecycle.application.SignupRecords;
import kr.nospo.support.ApiException;
import kr.nospo.terms.contract.Terms;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.*;

/** Inherits email regression tests and fixtures; Kakao network boundary is mocked. */
class KakaoOAuthTests extends EmailSignupTests {
    @Autowired KakaoOAuthService oauth;
    @Autowired KakaoOAuthSettings settings;
    @MockitoBean KakaoClient kakao;
    @Autowired KakaoSignupIdentity kakaoSignupIdentity;
    @Autowired KakaoSignupService kakaoSignup;
    @MockitoSpyBean SignupRecords signupRecords;

    String signupProof(String subject, String email, boolean verified) {
        user(subject, email, verified);
        return (String) oauth.exchange(callback(begin()), null, null, "test").data().get("signupProof");
    }

    KakaoSignupService.Command kakaoCommand(String proof, String nickname, String emailProof) {
        return new KakaoSignupService.Command(proof, nickname, "010-1234-5678", emailProof, List.copyOf(consents));
    }

    void unconsumed(String proof) {
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM auth_proof WHERE proof_hash=?",
                java.sql.Timestamp.class, secrets.hash(proof))).isNull();
    }

    void noSignupRows() {
        for (String table : List.of("member", "account", "social_identity", "member_consent", "credit_wallet",
                "credit_transaction", "auth_session", "refresh_token", "idempotency_record"))
            assertThat(count(table)).as(table).isZero();
    }

    @Test
    void restoresSignupContextWithoutConsumingAfterOAuthParentsExpire() throws Exception {
        String proof = signupProof("98765", "new@example.com", true);
        clock.advance(301);
        oauth.cleanup(); delivery.cleanup();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/auth/signup/kakao/context").contentType("application/json")
                            .content(mapper.writeValueAsString(Map.of("signupProof", proof))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value("new@example.com"))
                    .andExpect(jsonPath("$.data.emailVerified").value(true))
                    .andExpect(jsonPath("$.data.requiredFields[0]").value("nickname"))
                    .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                    .andExpect(cookie().doesNotExist("refreshToken"));
        }
        unconsumed(proof);
        assertThat(count("oauth_exchange")).isZero();
        assertThat(kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(proof, "newreader", null))
                .data().member().email()).isEqualTo("new@example.com");
    }

    @Test
    void kakaoSignupHttpIsAtomicIdempotentAndAutomaticallyAuthenticated() throws Exception {
        String proof = signupProof("98765", "new@example.com", true);
        var command = kakaoCommand(proof, "newreader", null);
        UUID key = UUID.randomUUID();
        var input = Map.of("signupProof", proof, "nickname", "newreader", "phoneNumber", "010-1234-5678",
                "consents", consents);
        var first = mvc.perform(post("/api/v1/auth/signup/kakao").header("Idempotency-Key", key)
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.member.loginMethods.length()").value(1))
                .andExpect(jsonPath("$.data.member.loginMethods[0]").value("KAKAO"))
                .andExpect(jsonPath("$.data.member.phoneNumber").value("+821012345678"))
                .andExpect(jsonPath("$.data.member.phoneVerified").value(false))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andExpect(cookie().secure("refreshToken", true))
                .andExpect(cookie().path("refreshToken", "/api/v1/auth"))
                .andReturn().getResponse();
        var completed = kakaoSignup.signup(key, command);
        assertThat(completed.data().accessToken()).isEqualTo(mapper.readTree(first.getContentAsString())
                .path("data").path("accessToken").asString());
        assertThat(first.getCookie("refreshToken").getValue()).isEqualTo(completed.refreshToken());
        assertThat(decoder.decode(completed.data().accessToken()).getSubject())
                .isEqualTo(completed.data().member().memberId().toString());
        mvc.perform(get("/api/v1/me/login-methods").header("Authorization", "Bearer " + completed.data().accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canAddEmail").value(true))
                .andExpect(jsonPath("$.data.canLinkKakao").value(false));
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("account")).isEqualTo(1);
        assertThat(count("social_identity")).isEqualTo(1);
        assertThat(count("password_credential")).isZero();
        assertThat(count("member_consent")).isEqualTo(consents.size());
        assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet", Integer.class)).isEqualTo(5);
        assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("refresh_token")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_path FROM idempotency_record", String.class))
                .isEqualTo("/api/v1/auth/signup/kakao");
        assertThat(jdbc.queryForObject("SELECT member_id FROM auth_proof WHERE proof_hash=?", byte[].class,
                secrets.hash(proof))).containsExactly(id(completed.data().member().memberId()));
        assertThat(count("social_signup_context")).isZero();
        assertThat(count("oauth_exchange")).isZero();
        assertThat(count("oauth_flow")).isZero();
        code(() -> kakaoSignupIdentity.context(proof), "PROOF_ALREADY_USED");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), command), "PROOF_ALREADY_USED");
        code(() -> kakaoSignup.signup(key, kakaoCommand(proof, "changedname", null)), "IDEMPOTENCY_CONFLICT");
        code(() -> auth.login("new@example.com", "any password", "test"), "INVALID_CREDENTIALS");
        assertThat(auth.refresh(completed.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void kakaoSignupWithoutUsableEmailRequiresAndConsumesSeparateEmailProof() {
        String social = signupProof("98765", null, false);
        assertThat(kakaoSignupIdentity.context(social).requiredFields()).contains("emailProof");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "newreader", null)),
                "VALIDATION_ERROR");
        noSignupRows(); unconsumed(social);
        String email = proof("verified@example.com");
        var result = kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "newreader", email));
        assertThat(result.data().member().email()).isEqualTo("verified@example.com");
        assertThat(result.data().member().loginMethods()).containsExactly("KAKAO");
        assertThat(store.proof(secrets.hash(email)).orElseThrow().consumedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT member_id FROM auth_challenge WHERE purpose='SIGNUP'", byte[].class))
                .containsExactly(id(result.data().member().memberId()));
        assertThat(jdbc.queryForObject("SELECT member_id FROM email_delivery", byte[].class))
                .containsExactly(id(result.data().member().memberId()));
        assertThat(count("password_credential")).isZero();
    }

    @Test
    void kakaoSignupRejectsWrongPurposeAndUnnecessaryEmailProof() {
        String social = signupProof("98765", "new@example.com", true);
        String email = proof("other@example.com");
        code(() -> kakaoSignupIdentity.context(email), "INVALID_PROOF");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(email, "newreader", null)), "INVALID_PROOF");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "newreader", email)), "VALIDATION_ERROR");
        String missingEmail = signupProof("98766", null, false);
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(missingEmail, "newreader", social)), "INVALID_PROOF");
        noSignupRows(); unconsumed(social); unconsumed(email);
    }

    @Test
    void expiredAndInvalidatedSocialProofsCannotRestoreOrSignup() {
        String expired = signupProof("98765", "new@example.com", true);
        clock.advance(900);
        code(() -> kakaoSignupIdentity.context(expired), "PROOF_EXPIRED");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(expired, "newreader", null)), "PROOF_EXPIRED");
        String invalid = signupProof("98766", "other@example.com", true);
        jdbc.update("UPDATE auth_proof SET invalidated_at=? WHERE proof_hash=?", at(clock.instant()), secrets.hash(invalid));
        code(() -> kakaoSignupIdentity.context(invalid), "PROOF_EXPIRED");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(invalid, "newreader", null)), "PROOF_EXPIRED");
        noSignupRows();
    }

    @Test
    void revokedOrExpiredEmailProofDoesNotConsumeSocialProof() {
        String social = signupProof("98765", null, false);
        String email = proof("verified@example.com");
        clock.advance(60);
        verification.request("verified@example.com", "test");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "newreader", email)), "PROOF_EXPIRED");
        noSignupRows(); unconsumed(social);
        String nextEmail = proof("next@example.com");
        clock.advance(600);
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "newreader", nextEmail)), "PROOF_EXPIRED");
        noSignupRows(); unconsumed(social);
    }

    @Test
    void duplicateProviderWithDifferentSignupProofCannotCreateSecondMember() {
        String first = signupProof("98765", "first@example.com", true);
        String second = signupProof("98765", "second@example.com", true);
        kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(first, "firstreader", null));
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(second, "secondreader", null)),
                "SOCIAL_ACCOUNT_ALREADY_LINKED");
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
        unconsumed(second);
    }

    @Test
    void emailAccountCreatedAfterOAuthExchangeIsNeverMerged() {
        String social = signupProof("98765", "reader@example.com", true);
        var original = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "existing"));
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "newreader", null)), "EMAIL_ALREADY_EXISTS");
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("social_identity")).isZero();
        assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(signup.me(original.data().member().memberId()).loginMethods()).containsExactly("EMAIL");
        unconsumed(social);
    }

    @Test
    void nicknamePhoneAndTermsFailuresRollBackAndAllowCorrectedRetry() {
        String social = signupProof("98765", "new@example.com", true);
        for (var invalid : List.of(new KakaoSignupService.Command(social, "!", "01012345678", null, consents),
                new KakaoSignupService.Command(social, "newreader", "not-a-phone", null, consents)))
            assertThatThrownBy(() -> kakaoSignup.signup(UUID.randomUUID(), invalid)).isInstanceOf(IllegalArgumentException.class);
        code(() -> kakaoSignup.signup(UUID.randomUUID(), new KakaoSignupService.Command(social, "newreader",
                "01012345678", null, List.of())), "REQUIRED_CONSENT_MISSING");
        var refused = consents.stream().map(c -> new Terms.Consent(c.termVersionId(), false)).toList();
        code(() -> kakaoSignup.signup(UUID.randomUUID(), new KakaoSignupService.Command(social, "newreader",
                "01012345678", null, refused)), "REQUIRED_CONSENT_MISSING");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), new KakaoSignupService.Command(social, "newreader",
                "01012345678", null, List.of(new Terms.Consent(UUID.randomUUID(), true)))), "TERMS_VERSION_CHANGED");
        noSignupRows(); unconsumed(social);
        kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "newreader", null));
        assertThat(count("member")).isEqualTo(1);
    }

    @Test
    void duplicateNicknameRollsBackSocialAndEmailProofConsumption() {
        signup.signup(UUID.randomUUID(), command(proof("existing@example.com"), "SameName"));
        String social = signupProof("98765", null, false);
        String email = proof("new@example.com");
        code(() -> kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "samename", email)), "NICKNAME_ALREADY_EXISTS");
        unconsumed(social); unconsumed(email);
        assertThat(count("social_identity")).isZero();
        kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(social, "different", email));
        assertThat(count("member")).isEqualTo(2);
    }

    @Test
    void lateIdempotencyWriteFailureRollsBackEverySignupEffect() {
        String social = signupProof("98765", null, false);
        String email = proof("new@example.com");
        UUID key = UUID.randomUUID();
        var command = kakaoCommand(social, "newreader", email);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated storage failure")).when(signupRecords)
                .save(any(UUID.class), anyString(), eq("/api/v1/auth/signup/kakao"), any(UUID.class),
                        any(byte[].class), any(byte[].class), any(java.time.Instant.class));
        assertThatThrownBy(() -> kakaoSignup.signup(key, command))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        noSignupRows(); unconsumed(social); unconsumed(email);
        assertThat(count("social_signup_context")).isEqualTo(1);
        assertThat(count("oauth_exchange")).isEqualTo(1);
        reset(signupRecords);
        assertThat(kakaoSignup.signup(key, command).data().member().loginMethods()).containsExactly("KAKAO");
    }

    @Test
    void successfulRetryPrecedesProofCleanupButExpiredAccessRequiresLogin() {
        String social = signupProof("98765", "new@example.com", true);
        UUID key = UUID.randomUUID();
        var command = kakaoCommand(social, "newreader", null);
        var first = kakaoSignup.signup(key, command);
        clock.advance(60);
        oauth.cleanup(); delivery.cleanup();
        assertThat(kakaoSignup.signup(key, command)).isEqualTo(first);
        clock.advance(840);
        oauth.cleanup(); delivery.cleanup();
        assertThat(count("auth_proof")).isZero();
        code(() -> kakaoSignup.signup(key, command), "SIGNUP_ALREADY_COMPLETED_LOGIN_REQUIRED");
        assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
    }

    @Test
    void parallelSameKakaoSignupKeyReplaysExactlyOneSuccess() throws Exception {
        String social = signupProof("98765", "new@example.com", true);
        UUID key = UUID.randomUUID();
        var command = kakaoCommand(social, "newreader", null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<SignupService.Completed> call = () -> { start.await(10, TimeUnit.SECONDS); return kakaoSignup.signup(key, command); };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
    }

    void raceSignup(Callable<String> firstCall, Callable<String> secondCall, String conflict) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return firstCall.call(); });
            var second = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return secondCall.call(); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", conflict);
        }
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
    }

    String signupOutcome(KakaoSignupService.Command command) {
        try { kakaoSignup.signup(UUID.randomUUID(), command); return "OK"; }
        catch (ApiException ex) { return ex.code(); }
    }

    @Test
    void parallelDifferentKeysForSameProofConsumeOnce() throws Exception {
        String social = signupProof("98765", "new@example.com", true);
        var command = kakaoCommand(social, "newreader", null);
        raceSignup(() -> signupOutcome(command), () -> signupOutcome(command), "PROOF_ALREADY_USED");
    }

    @Test
    void parallelProofsForSameProviderCreateOnlyOneMember() throws Exception {
        String first = signupProof("98765", "first@example.com", true);
        String second = signupProof("98765", "second@example.com", true);
        raceSignup(() -> signupOutcome(kakaoCommand(first, "firstreader", null)),
                () -> signupOutcome(kakaoCommand(second, "secondreader", null)), "SOCIAL_ACCOUNT_ALREADY_LINKED");
    }

    @Test
    void parallelDifferentProvidersWithSameEmailNeverMerge() throws Exception {
        String first = signupProof("98765", "same@example.com", true);
        String second = signupProof("98766", "same@example.com", true);
        raceSignup(() -> signupOutcome(kakaoCommand(first, "firstreader", null)),
                () -> signupOutcome(kakaoCommand(second, "secondreader", null)), "EMAIL_ALREADY_EXISTS");
        assertThat(count("social_identity")).isEqualTo(1);
    }

    @Test
    void parallelEmailAndKakaoSignupForSameEmailNeverMerge() throws Exception {
        String social = signupProof("98765", "same@example.com", true);
        String email = proof("same@example.com");
        raceSignup(() -> signupOutcome(kakaoCommand(social, "socialreader", null)), () -> {
            try { signup.signup(UUID.randomUUID(), command(email, "emailreader")); return "OK"; }
            catch (ApiException ex) { return ex.code(); }
        }, "EMAIL_ALREADY_EXISTS");
    }

    @Test
    void signupHttpRejectsMissingKeyMalformedProofOriginAndInvalidInputs() throws Exception {
        String social = signupProof("98765", "new@example.com", true);
        String json = mapper.writeValueAsString(Map.of("signupProof", social, "nickname", "newreader",
                "phoneNumber", "01012345678", "consents", consents));
        mvc.perform(post("/api/v1/auth/signup/kakao").contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/signup/kakao").header("Idempotency-Key", UUID.randomUUID())
                        .header("Origin", "https://evil.example").contentType("application/json").content(json))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/signup/kakao/context").contentType("application/json")
                        .content("{\"signupProof\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/auth/signup/kakao/context").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("signupProof", secrets.token()))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/signup/kakao").header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content(json.replace("newreader", "!")))
                .andExpect(status().isUnprocessableEntity());
        noSignupRows(); unconsumed(social);
    }

    record Flow(String state, String cookie) {}
    Flow begin() {
        when(kakao.authorizationUrl(anyString(), anyString())).thenAnswer(i ->
                "https://kauth.kakao.com/oauth/authorize?state=" + i.getArgument(0));
        var started = oauth.authorize("/shelf", null, null, "test");
        return new Flow(started.authorizationUrl().split("state=")[1], started.cookie());
    }
    String callback(Flow f) {
        var result = oauth.callback(f.state(), f.cookie(), "provider-code", null, settings.redirectUri());
        assertThat(result.error()).isNull();
        assertThat(result.exchangeCode()).hasSize(43);
        return result.exchangeCode();
    }
    void user(String subject, String email, boolean verified) {
        doReturn(new KakaoClient.User(subject, email, verified, "reader"))
                .when(kakao).identify(anyString(), anyString());
    }

    @Test
    void linkedKakaoLogsInBySubjectWithoutChangingServiceEmail() throws Exception {
        var existing = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        UUID member = existing.data().member().memberId();
        loginMethods.recordVerifiedKakao(member, "12345");
        user("12345", "changed@kakao.example", true);
        String code = callback(begin());
        mvc.perform(post("/api/v1/auth/kakao/exchange").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("exchangeCode", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.data.auth.member.email").value("reader@example.com"))
                .andExpect(jsonPath("$.data.auth.member.loginMethods[1]").value("KAKAO"))
                .andExpect(jsonPath("$.data.returnPath").value("/shelf"))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andExpect(cookie().secure("refreshToken", true));
        assertThat(count("auth_session")).isEqualTo(2);
        assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload_ciphertext FROM oauth_exchange", byte[].class)).isNull();
        code(() -> oauth.exchange(code, null, null, "test"), "PROOF_ALREADY_USED");
    }

    @Test
    void kakaoOnlyMemberUsesExistingSessionAndJwtValidation() {
        var existing = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        UUID member = existing.data().member().memberId();
        loginMethods.recordVerifiedKakao(member, "12345");
        jdbc.update("DELETE FROM password_credential WHERE member_id=?", id(member));
        user("12345", null, false);
        var result = oauth.exchange(callback(begin()), null, null, "test");
        var authResult = (kr.nospo.identity.contract.AuthResult) result.data().get("auth");
        assertThat(authResult.member().loginMethods()).containsExactly("KAKAO");
        assertThat(decoder.decode(authResult.accessToken()).getSubject()).isEqualTo(member.toString());
        assertThat(auth.refresh(result.refresh()).accessToken()).isNotBlank();
        auth.logout(null, result.refresh());
        assertThatThrownBy(() -> decoder.decode(authResult.accessToken())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void identicalVerifiedEmailRequiresExplicitLoginAndLink() {
        signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        user("98765", "READER@example.com", true);
        var result = oauth.exchange(callback(begin()), null, null, "test");
        assertThat(result.data()).containsEntry("result", "EXISTING_ACCOUNT")
                .containsEntry("nextAction", "LOGIN_AND_LINK");
        assertThat(result.refresh()).isNull();
        assertThat(count("social_identity")).isZero();
        assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("social_signup_context")).isZero();
    }

    @Test
    void signupRequiredPreservesContextBeyondExchangeAndFlowExpiry() {
        user("98765", "new@example.com", true);
        var result = oauth.exchange(callback(begin()), null, null, "test");
        assertThat(result.data()).containsEntry("result", "SIGNUP_REQUIRED");
        String proof = (String) result.data().get("signupProof");
        var info = (KakaoOAuthService.SignupInfo) result.data().get("signupInfo");
        assertThat(info.email()).isEqualTo("new@example.com");
        assertThat(info.requiredFields()).doesNotContain("emailProof");
        assertThat(result.refresh()).isNull();
        assertThat(count("member")).isZero();
        assertThat(count("credit_transaction")).isZero();
        assertThat(jdbc.queryForObject("SELECT proof_hash FROM auth_proof WHERE purpose='KAKAO_SIGNUP'", byte[].class))
                .containsExactly(secrets.hash(proof));
        clock.advance(301);
        oauth.cleanup(); delivery.cleanup();
        assertThat(count("oauth_exchange")).isZero();
        assertThat(count("oauth_flow")).isEqualTo(1);
        assertThat(count("social_signup_context")).isEqualTo(1);
        clock.advance(600);
        // Either scheduler order must be safe with RESTRICT references.
        delivery.cleanup(); oauth.cleanup(); delivery.cleanup();
        assertThat(count("social_signup_context")).isZero();
        assertThat(count("oauth_flow")).isZero();
        assertThat(count("auth_proof")).isZero();
    }

    @Test
    void unverifiedOrMissingEmailRequiresEmailProofAndNeverSelectsExistingAccount() {
        signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        user("98765", "reader@example.com", false);
        var result = oauth.exchange(callback(begin()), null, null, "test");
        assertThat(result.data()).containsEntry("result", "SIGNUP_REQUIRED");
        var info = (KakaoOAuthService.SignupInfo) result.data().get("signupInfo");
        assertThat(info.email()).isNull();
        assertThat(info.emailVerified()).isFalse();
        assertThat(info.requiredFields()).contains("emailProof");
    }

    @Test
    void newConsentFlowUsesVerifiedEmailWithoutChangingPreviouslyIssuedSignupProof() {
        String before = signupProof("98765", null, false);
        String after = signupProof("98765", "new@example.com", true);
        var oldContext = kakaoSignupIdentity.context(before);
        assertThat(oldContext.email()).isNull();
        assertThat(oldContext.emailVerified()).isFalse();
        assertThat(oldContext.requiredFields()).contains("emailProof");
        var newContext = kakaoSignupIdentity.context(after);
        assertThat(newContext.email()).isEqualTo("new@example.com");
        assertThat(newContext.emailVerified()).isTrue();
        assertThat(newContext.requiredFields()).doesNotContain("emailProof");
        assertThat(count("member")).isZero();
        assertThat(count("social_identity")).isZero();
    }

    @Test
    void stateCookieRedirectAndCallbackReplayAreValidatedBeforeProviderCalls() {
        user("98765", null, false);
        var f = begin();
        assertThat(oauth.callback(f.state(), secrets.token(), "code", null, settings.redirectUri()).error())
                .isEqualTo("KAKAO_AUTH_FAILED");
        assertThat(oauth.callback(secrets.token(), f.cookie(), "code", null, settings.redirectUri()).error())
                .isEqualTo("KAKAO_AUTH_FAILED");
        assertThat(oauth.callback(f.state(), f.cookie(), "code", null, "https://evil.example/callback").error())
                .isEqualTo("KAKAO_AUTH_FAILED");
        verify(kakao, never()).identify(anyString(), anyString());
        callback(f);
        assertThat(oauth.callback(f.state(), f.cookie(), "code", null, settings.redirectUri()).error())
                .isEqualTo("KAKAO_AUTH_FAILED");
        verify(kakao, times(1)).identify(anyString(), anyString());
    }

    @Test
    void callbackAndExchangeExpireAndConsumeOnce() {
        user("98765", null, false);
        var expired = begin();
        clock.advance(300);
        assertThat(oauth.callback(expired.state(), expired.cookie(), "code", null, settings.redirectUri()).error())
                .isEqualTo("KAKAO_AUTH_FAILED");
        verify(kakao, never()).identify(anyString(), anyString());
        String code = callback(begin());
        clock.advance(60);
        code(() -> oauth.exchange(code, null, null, "test"), "PROOF_EXPIRED");
        assertThat(count("social_signup_context")).isZero();
    }

    @Test
    void providerCancelFailureAndMalformedIdentityNeverExposeDetails() throws Exception {
        var f = begin();
        mvc.perform(get(settings.redirectUri()).param("state", f.state()).param("error", "access_denied")
                        .param("error_description", "sensitive-provider-detail")
                        .cookie(new Cookie("nospoKakaoFlow", f.cookie())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", settings.frontendCallback() + "?error=KAKAO_AUTH_CANCELLED"))
                .andExpect(cookie().maxAge("nospoKakaoFlow", 0));
        verify(kakao, never()).identify(anyString(), anyString());
        f = begin();
        when(kakao.identify(anyString(), anyString())).thenThrow(new RuntimeException("client-secret provider-token"));
        assertThat(oauth.callback(f.state(), f.cookie(), "code", null, settings.redirectUri()).error())
                .isEqualTo("KAKAO_AUTH_FAILED");
        user("not-a-kakao-id", null, false);
        f = begin();
        assertThat(oauth.callback(f.state(), f.cookie(), "code", null, settings.redirectUri()).error())
                .isEqualTo("KAKAO_AUTH_FAILED");
        assertThat(count("oauth_exchange")).isZero();
    }

    @Test
    void browserHttpContractRejectsOpenRedirectsAndDuplicateParameters() throws Exception {
        user("98765", null, false);
        when(kakao.authorizationUrl(anyString(), anyString())).thenAnswer(i ->
                "https://kauth.kakao.com/oauth/authorize?state=" + i.getArgument(0));
        for (String path : List.of("https://evil.example", "//evil.example", "/%2f%2fevil", "/../evil", "/unknown"))
            mvc.perform(get("/api/v1/auth/kakao/authorize").param("returnPath", path))
                    .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/auth/kakao/authorize").param("redirect_uri", "https://evil.example"))
                .andExpect(status().isUnprocessableEntity());
        var response = mvc.perform(get("/api/v1/auth/kakao/authorize").param("returnPath", "/shelf"))
                .andExpect(status().isFound()).andExpect(cookie().httpOnly("nospoKakaoFlow", true))
                .andExpect(cookie().secure("nospoKakaoFlow", true))
                .andExpect(cookie().path("nospoKakaoFlow", "/api/v1/auth/kakao/callback"))
                .andReturn().getResponse();
        String state = response.getHeader("Location").split("state=")[1];
        String flowCookie = response.getCookie("nospoKakaoFlow").getValue();
        mvc.perform(get(settings.redirectUri()).param("state", state, state).param("code", "code")
                        .cookie(new Cookie("nospoKakaoFlow", flowCookie)))
                .andExpect(header().string("Location", settings.frontendCallback() + "?error=KAKAO_AUTH_FAILED"));
        var callbackResponse = mvc.perform(get(settings.redirectUri()).param("state", state).param("code", "provider-code")
                        .cookie(new Cookie("nospoKakaoFlow", flowCookie)))
                .andExpect(status().isFound()).andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse();
        String location = callbackResponse.getHeader("Location");
        assertThat(location).doesNotContain("provider-code", "signupProof", "accessToken");
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("exchangeCode");
        assertThat(code).isNotNull();
        mvc.perform(post("/api/v1/auth/kakao/exchange").header("Origin", "https://evil.example")
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("exchangeCode", code))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/kakao/exchange").header("Origin", "http://localhost:3000")
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("exchangeCode", code))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("SIGNUP_REQUIRED"))
                .andExpect(cookie().doesNotExist("refreshToken"));
    }

    @Test
    void authenticatedStartBindsExchangeToOriginalSessionAndChecksRevocation() {
        var existing = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        UUID member = existing.data().member().memberId();
        UUID session = UUID.fromString(decoder.decode(existing.data().accessToken()).getClaimAsString("sid"));
        begin(); // Installs authorization stub.
        user("98765", null, false);
        var start = oauth.authorize(null, member, session, "test");
        var bound = new Flow(start.authorizationUrl().split("state=")[1], start.cookie());
        String exchange = callback(bound);
        code(() -> oauth.exchange(exchange, null, null, "test"), "INVALID_PROOF");
        code(() -> oauth.exchange(exchange, member, UUID.randomUUID(), "test"), "INVALID_PROOF");
        assertThat(oauth.exchange(exchange, member, session, "test").data()).containsEntry("result", "SIGNUP_REQUIRED");
        start = oauth.authorize(null, member, session, "test");
        String revokedExchange = callback(new Flow(start.authorizationUrl().split("state=")[1], start.cookie()));
        auth.logout(session, null);
        code(() -> oauth.exchange(revokedExchange, member, session, "test"), "INVALID_CREDENTIALS");
        assertThat(count("social_signup_context")).isEqualTo(1);
    }

    @Test
    void parallelResultExchangeIssuesExactlyOneSession() throws Exception {
        var existing = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        loginMethods.recordVerifiedKakao(existing.data().member().memberId(), "12345");
        user("12345", null, false);
        String code = callback(begin());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                start.await(10, TimeUnit.SECONDS);
                try { oauth.exchange(code, null, null, "parallel"); return "OK"; }
                catch (ApiException ex) { return ex.code(); }
            };
            var first = pool.submit(task); var second = pool.submit(task); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "PROOF_ALREADY_USED");
        }
        assertThat(count("auth_session")).isEqualTo(2);
    }

    @Test
    void inactiveLinkedMemberCannotObtainSession() {
        var existing = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        UUID member = existing.data().member().memberId();
        loginMethods.recordVerifiedKakao(member, "12345");
        user("12345", null, false);
        String code = callback(begin());
        jdbc.update("UPDATE member SET status='WITHDRAWING' WHERE member_id=?", id(member));
        code(() -> oauth.exchange(code, null, null, "test"), "INVALID_CREDENTIALS");
        assertThat(count("auth_session")).isEqualTo(1);
    }

    @Test
    void malformedVerifiedEmailFallsBackToSeparateVerification() {
        user("98765", "not-an-email", true);
        var result = oauth.exchange(callback(begin()), null, null, "test");
        var info = (KakaoOAuthService.SignupInfo) result.data().get("signupInfo");
        assertThat(info.email()).isNull();
        assertThat(info.requiredFields()).contains("emailProof");
        assertThat(result.toString()).doesNotContain((String) result.data().get("signupProof"));
    }

    @Test
    void parallelCallbacksContactProviderOnce() throws Exception {
        user("98765", null, false);
        var flow = begin();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<KakaoOAuthService.Callback> task = () -> {
                start.await(10, TimeUnit.SECONDS);
                return oauth.callback(flow.state(), flow.cookie(), "code", null, settings.redirectUri());
            };
            var first = pool.submit(task); var second = pool.submit(task); start.countDown();
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.exchangeCode() != null).count()).isEqualTo(1);
            assertThat(results.stream().filter(r -> "KAKAO_AUTH_FAILED".equals(r.error())).count()).isEqualTo(1);
        }
        verify(kakao, times(1)).identify(anyString(), anyString());
        assertThat(count("oauth_exchange")).isEqualTo(1);
    }

    @Test
    void settingsRejectUnsafeRedirectUrisAndAllowOnlyExplicitLocalHttp() {
        assertThatThrownBy(() -> new KakaoOAuthSettings("", "https://web.example/callback", List.of("/"), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KAKAO_REDIRECT_URI");
        assertThatThrownBy(() -> new KakaoOAuthSettings("https://api.example/api/v1/auth/kakao/callback",
                "/auth/kakao/callback", List.of("/"), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KAKAO_FRONTEND_CALLBACK");
        for (String uri : List.of("https://api.example/callback?x=1", "https://user@api.example/api/v1/auth/kakao/callback",
                "http://api.example/api/v1/auth/kakao/callback", "https://api.example/api/../callback"))
            assertThatThrownBy(() -> new KakaoOAuthSettings(uri, "https://web.example/callback", List.of("/"), true))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KakaoOAuthSettings("http://localhost:8080/api/v1/auth/kakao/callback",
                "http://localhost:3000/callback", List.of("/"), true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new KakaoOAuthSettings("http://localhost:8080/api/v1/auth/kakao/callback",
                "http://localhost:3000/callback", List.of("/"), false).returnPath(null)).isEqualTo("/");
    }
}
