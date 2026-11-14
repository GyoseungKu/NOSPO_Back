package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import kr.nospo.identity.application.*;
import kr.nospo.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

/** Email-login addition plus inherited Auth 1-4 regression contracts. */
class EmailLoginTests extends KakaoAccountTests {
    @Autowired EmailLoginService emailLogin;
    @Autowired VerificationStore verificationStore;
    @MockitoSpyBean EmailLoginStore emailLoginStore;
    record Ticket(EmailVerificationService.Issued issued, String token, String reauth) {}

    Account kakaoAccount(String email, String nickname, String subject) {
        var completed = kakaoSignup.signup(UUID.randomUUID(), kakaoCommand(signupProof(subject, email, true), nickname, null));
        return new Account(completed, completed.data().member().memberId(),
                UUID.fromString(decoder.decode(completed.data().accessToken()).getClaimAsString("sid")));
    }
    String kakaoReauth(Account a, String purpose) {
        String subject = jdbc.queryForObject("SELECT provider_subject FROM social_identity WHERE member_id=?", String.class, id(a.member()));
        user(subject, a.signup().data().member().email(), true);
        return (String) finish(actions.reauthenticate(a.member(), a.session(), purpose), a).data().get("reauthProof");
    }
    Ticket ticket(Account a) {
        String proof = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        var issued = emailLogin.request(a.member(), a.session(), proof, "test");
        delivery.processDue();
        String payload = sent.get(signup.me(a.member()).email());
        assertThat(payload).startsWith("LOGIN:");
        return new Ticket(issued, payload.substring(6), proof);
    }
    String ready(Account a) { return emailLogin.confirm(a.member(), a.session(), ticket(a).token()).emailLoginProof(); }
    String enableOutcome(Account a, String proof, UUID key) {
        try { emailLogin.enable(a.member(), a.session(), proof, "new valid password", key); return "OK"; }
        catch (ApiException ex) { return ex.code(); }
    }
    int emailChallenges() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_challenge WHERE purpose='EMAIL_LOGIN'", Integer.class);
    }
    String prepareEmailChange(Account a, String email) {
        var issued = changes.requestEmail(a.member(), a.session(), email, kakaoReauth(a, "CHANGE_EMAIL"));
        delivery.processDue();
        return changes.verifyEmail(a.member(), a.session(), issued.verificationId(), sent.get(email).substring(7)).emailChangeProof();
    }

    @Test
    void emailAdditionHttpAuthenticatesRegisteredEmailAndKeepsKakaoMemberAndReward() throws Exception {
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        String bearer = "Bearer " + a.signup().data().accessToken();
        String reauthProof = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        mvc.perform(post("/api/v1/me/login-methods/email/verifications").header("Authorization", bearer)
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("reauthProof", reauthProof,
                                "email", "attacker@example.com"))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.verificationId").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty()).andExpect(jsonPath("$.data.resendAvailableAt").isNotEmpty());
        delivery.processDue();
        assertThat(sent).containsKey("reader@example.com").doesNotContainKey("attacker@example.com");
        String token = sent.get("reader@example.com").substring(6);
        var confirmed = mvc.perform(post("/api/v1/me/login-methods/email/verifications/confirm").header("Authorization", bearer)
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("token", token))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").isNotEmpty()).andReturn().getResponse();
        String proof = mapper.readTree(confirmed.getContentAsString()).path("data").path("emailLoginProof").asString();
        UUID key = UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of("emailLoginProof", proof, "password", "new valid password"));
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/v1/me/login-methods/email").header("Authorization", bearer)
                            .header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.loginMethods[0]").value("EMAIL"))
                    .andExpect(jsonPath("$.data.loginMethods[1]").value("KAKAO"))
                    .andExpect(cookie().doesNotExist("refreshToken"));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM password_credential", String.class))
                .startsWith("$argon2id$").doesNotContain("new valid password");
        assertThat(count("member")).isEqualTo(1); assertThat(count("account")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1); assertThat(count("auth_session")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet", Integer.class)).isEqualTo(5);
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"email\":\"reader@example.com\",\"password\":\"new valid password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.member.memberId").value(a.member().toString()))
                .andExpect(jsonPath("$.data.member.loginMethods[1]").value("KAKAO"));
        var kakaoLogin = oauth.exchange(callback(begin()), null, null, "test");
        assertThat(kakaoLogin.data()).containsEntry("result", "LOGIN_SUCCESS");
        assertThat(count("credit_transaction")).isEqualTo(1);
        mvc.perform(get("/api/v1/me/login-methods").header("Authorization", bearer))
                .andExpect(jsonPath("$.data.canAddEmail").value(false));
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "different password", UUID.randomUUID()), "EMAIL_LOGIN_ALREADY_ENABLED");
    }

    @Test
    void requestRejectsPurposeNonKakaoProofAndWrongMemberOrSession() {
        var a = kakaoAccount("first@example.com", "first", "12345");
        var b = kakaoAccount("second@example.com", "second", "67890");
        String wrong = kakaoReauth(a, "CHANGE_EMAIL");
        code(() -> emailLogin.request(a.member(), a.session(), wrong, "test"), "PROOF_PURPOSE_MISMATCH");
        String proof = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        code(() -> emailLogin.request(b.member(), b.session(), proof, "test"), "INVALID_PROOF");
        var next = storeSession(a);
        code(() -> emailLogin.request(a.member(), next, proof, "test"), "INVALID_PROOF");
        String forged = secrets.token();
        verificationStore.createProof(UUID.randomUUID(), null, a.member(), a.session(), "ADD_EMAIL_LOGIN", null,
                secrets.hash(forged), clock.instant(), 300);
        code(() -> emailLogin.request(a.member(), a.session(), forged, "test"), "INVALID_PROOF");
        assertThat(emailChallenges()).isZero(); unconsumed(proof);
    }
    UUID storeSession(Account a) {
        var tokens = sessionIdentity.openSession(a.member(), clock.instant());
        return UUID.fromString(decoder.decode(tokens.accessToken()).getClaimAsString("sid"));
    }
    @Autowired kr.nospo.identity.contract.SignupIdentity sessionIdentity;

    @Test
    void expiredInvalidatedOrUsedReauthCannotSendMail() {
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        String expired = kakaoReauth(a, "ADD_EMAIL_LOGIN"); clock.advance(300);
        code(() -> emailLogin.request(a.member(), a.session(), expired, "test"), "PROOF_EXPIRED");
        String invalid = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        jdbc.update("UPDATE auth_proof SET invalidated_at=? WHERE proof_hash=?", at(clock.instant()), secrets.hash(invalid));
        code(() -> emailLogin.request(a.member(), a.session(), invalid, "test"), "PROOF_EXPIRED");
        String used = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        emailLogin.request(a.member(), a.session(), used, "test");
        code(() -> emailLogin.request(a.member(), a.session(), used, "test"), "PROOF_ALREADY_USED");
        assertThat(emailChallenges()).isEqualTo(1);
    }

    @Test
    void confirmAndEnableRejectWrongMemberSessionAndPurpose() {
        var a = kakaoAccount("first@example.com", "first", "12345");
        var b = kakaoAccount("second@example.com", "second", "67890");
        var ticket = ticket(a); UUID other = storeSession(a);
        code(() -> emailLogin.confirm(b.member(), b.session(), ticket.token()), "INVALID_VERIFICATION");
        code(() -> emailLogin.confirm(a.member(), other, ticket.token()), "INVALID_VERIFICATION");
        var proof = emailLogin.confirm(a.member(), a.session(), ticket.token());
        code(() -> emailLogin.confirm(a.member(), a.session(), ticket.token()), "CHALLENGE_ALREADY_VERIFIED");
        code(() -> emailLogin.enable(b.member(), b.session(), proof.emailLoginProof(), "new valid password", UUID.randomUUID()), "INVALID_PROOF");
        code(() -> emailLogin.enable(a.member(), other, proof.emailLoginProof(), "new valid password", UUID.randomUUID()), "INVALID_PROOF");
        String wrong = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        code(() -> emailLogin.enable(a.member(), a.session(), wrong, "new valid password", UUID.randomUUID()), "INVALID_PROOF");
        assertThat(count("password_credential")).isZero(); unconsumed(proof.emailLoginProof());
    }

    @Test
    void expiredLinkAndEmailLoginProofCannotEnablePassword() {
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        var ticket = ticket(a); clock.advance(600);
        code(() -> emailLogin.confirm(a.member(), a.session(), ticket.token()), "VERIFICATION_EXPIRED");
        String proof = ready(a); clock.advance(600);
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "new valid password", UUID.randomUUID()), "PROOF_EXPIRED");
        assertThat(count("password_credential")).isZero();
    }

    @Test
    void lateLinkConfirmationKeepsProofAndParentForFullLifetime() {
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        var ticket = ticket(a); clock.advance(599);
        var proof = emailLogin.confirm(a.member(), a.session(), ticket.token());
        clock.advance(2); delivery.cleanup();
        assertThat(emailChallenges()).isEqualTo(1);
        assertThat(emailLogin.enable(a.member(), a.session(), proof.emailLoginProof(), "new valid password", UUID.randomUUID())
                .loginMethods()).containsExactly("EMAIL", "KAKAO");
    }

    @Test
    void resendInvalidatesLinkAndProofAndErasesQueuedPayload() {
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        var first = ticket(a); String proof = emailLogin.confirm(a.member(), a.session(), first.token()).emailLoginProof();
        clock.advance(60);
        emailLogin.request(a.member(), a.session(), kakaoReauth(a, "ADD_EMAIL_LOGIN"), "test");
        byte[] encrypted = jdbc.queryForObject("SELECT payload_ciphertext FROM email_delivery WHERE status='PENDING'", byte[].class);
        UUID deliveryId = uuid(jdbc.queryForObject("SELECT delivery_id FROM email_delivery WHERE status='PENDING'", byte[].class));
        String payload = new String(secrets.decrypt(encrypted, "mail:" + deliveryId), java.nio.charset.StandardCharsets.UTF_8);
        String oldToken = payload.split("LOGIN:")[1];
        clock.advance(60);
        var latest = emailLogin.request(a.member(), a.session(), kakaoReauth(a, "ADD_EMAIL_LOGIN"), "test");
        code(() -> emailLogin.confirm(a.member(), a.session(), oldToken), "VERIFICATION_EXPIRED");
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "new valid password", UUID.randomUUID()), "PROOF_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT payload_ciphertext FROM email_delivery WHERE delivery_id=?", byte[].class,
                id(deliveryId))).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM email_delivery WHERE delivery_id=?", String.class,
                id(deliveryId))).isEqualTo("CANCELLED");
        delivery.processDue(); assertThat(latest.verificationId()).isNotEqualTo(first.issued().verificationId());
        assertThat(emailLogin.confirm(a.member(), a.session(), sent.get("reader@example.com").substring(6)).emailLoginProof()).isNotBlank();
    }

    @Test
    void resendCooldownPreservesFreshReauthProof() {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); ticket(a);
        String fresh = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        code(() -> emailLogin.request(a.member(), a.session(), fresh, "test"), "RATE_LIMITED");
        unconsumed(fresh); assertThat(emailChallenges()).isEqualTo(1);
    }

    @Test
    void logoutPreventsQueuedMailConfirmationAndCompletion() {
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        emailLogin.request(a.member(), a.session(), kakaoReauth(a, "ADD_EMAIL_LOGIN"), "test");
        auth.logout(a.session(), null); delivery.processDue();
        assertThat(sent).doesNotContainKey("reader@example.com");
        assertThat(jdbc.queryForObject("SELECT status FROM email_delivery", String.class)).isEqualTo("CANCELLED");
        var b = kakaoAccount("other@example.com", "other", "67890"); var ticket = ticket(b);
        String proof = emailLogin.confirm(b.member(), b.session(), ticket.token()).emailLoginProof();
        auth.logout(b.session(), null);
        code(() -> emailLogin.confirm(b.member(), b.session(), ticket.token()), "INVALID_CREDENTIALS");
        code(() -> emailLogin.enable(b.member(), b.session(), proof, "new valid password", UUID.randomUUID()), "INVALID_CREDENTIALS");
    }

    @Test
    void emailChangeInvalidatesPreviousAdditionMaterialAndCancelsMail() {
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        String proof = ready(a);
        changes.completeEmail(a.member(), a.session(), prepareEmailChange(a, "new@example.com"));
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "new valid password", UUID.randomUUID()), "PROOF_EXPIRED");
        assertThat(count("password_credential")).isZero();
        clock.advance(60);
        var change = prepareEmailChange(a, "third@example.com");
        emailLogin.request(a.member(), a.session(), kakaoReauth(a, "ADD_EMAIL_LOGIN"), "test");
        // Complete the authenticated email change while the addition mail is still queued.
        changes.completeEmail(a.member(), a.session(), change);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_delivery WHERE status='PENDING'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT d.status FROM email_delivery d JOIN auth_challenge c"
                + " ON c.challenge_id=d.challenge_id WHERE c.purpose='EMAIL_LOGIN' AND c.target_email_key='new@example.com'",
                String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT d.payload_ciphertext FROM email_delivery d JOIN auth_challenge c"
                + " ON c.challenge_id=d.challenge_id WHERE c.purpose='EMAIL_LOGIN' AND c.target_email_key='new@example.com'",
                byte[].class)).isNull();
    }

    @Test
    void finalEmailSnapshotCheckRejectsChangedAddressEvenWithoutInvalidation() {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); var ticket = ticket(a);
        jdbc.update("UPDATE account SET email='new@example.com',email_key='new@example.com' WHERE member_id=?", id(a.member()));
        code(() -> emailLogin.confirm(a.member(), a.session(), ticket.token()), "INVALID_VERIFICATION");
        jdbc.update("UPDATE account SET email='reader@example.com',email_key='reader@example.com' WHERE member_id=?", id(a.member()));
        String proof = emailLogin.confirm(a.member(), a.session(), ticket.token()).emailLoginProof();
        jdbc.update("UPDATE account SET email='new@example.com',email_key='new@example.com' WHERE member_id=?", id(a.member()));
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "new valid password", UUID.randomUUID()), "INVALID_PROOF");
        assertThat(count("password_credential")).isZero(); unconsumed(proof);
    }

    @Test
    void enableRollbackRestoresCredentialProofAndRetryResult() {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); String proof = ready(a); UUID key = UUID.randomUUID();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated failure")).when(emailLoginStore)
                .save(any(UUID.class), any(UUID.class), any(byte[].class), any(byte[].class), any(Instant.class));
        assertThatThrownBy(() -> emailLogin.enable(a.member(), a.session(), proof, "new valid password", key))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(count("password_credential")).isZero(); unconsumed(proof);
        reset(emailLoginStore);
        assertThat(emailLogin.enable(a.member(), a.session(), proof, "new valid password", key).loginMethods()).contains("EMAIL");
    }

    @Test
    void requestRollbackDoesNotConsumeReauthOrLeaveChallengeOrMail() {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); String proof = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated failure")).when(emailLoginStore)
                .ownDelivery(any(UUID.class), any(UUID.class));
        assertThatThrownBy(() -> emailLogin.request(a.member(), a.session(), proof, "test"))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        unconsumed(proof); assertThat(emailChallenges()).isZero(); assertThat(count("email_delivery")).isZero();
        reset(emailLoginStore);
        emailLogin.request(a.member(), a.session(), proof, "test"); assertThat(emailChallenges()).isEqualTo(1);
    }

    @Test
    void alreadyEnabledAndIdempotencyConflictsNeverOverwritePassword() {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); String proof = ready(a); UUID key = UUID.randomUUID();
        var result = emailLogin.enable(a.member(), a.session(), proof, "new valid password", key);
        assertThat(emailLogin.enable(a.member(), a.session(), proof, "new valid password", key)).isEqualTo(result);
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "changed password", key), "IDEMPOTENCY_CONFLICT");
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "changed password", UUID.randomUUID()), "EMAIL_LOGIN_ALREADY_ENABLED");
        code(() -> emailLogin.request(a.member(), a.session(), secrets.token(), "test"), "EMAIL_LOGIN_ALREADY_ENABLED");
        assertThat(auth.login("reader@example.com", "new valid password", "login").member()).isEqualTo(a.member());
        assertThat(count("password_credential")).isEqualTo(1);
    }

    @Test
    void shortPasswordRollsBackNothingAndAllowsValidRetry() {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); String proof = ready(a);
        code(() -> emailLogin.enable(a.member(), a.session(), proof, "short", UUID.randomUUID()), "VALIDATION_ERROR");
        unconsumed(proof); assertThat(count("password_credential")).isZero();
        emailLogin.enable(a.member(), a.session(), proof, "new valid password", UUID.randomUUID());
    }

    @Test
    void concurrentRequestsConsumeReauthAndCreateOutboxOnce() throws Exception {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); String proof = kakaoReauth(a, "ADD_EMAIL_LOGIN");
        Callable<String> call = () -> {
            try { emailLogin.request(a.member(), a.session(), proof, "race"); return "OK"; }
            catch (ApiException e) { return e.code(); }
        };
        assertThat(race(call, call)).containsExactlyInAnyOrder("OK", "PROOF_ALREADY_USED");
        assertThat(emailChallenges()).isEqualTo(1); assertThat(count("email_delivery")).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsIssueOneProof() throws Exception {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); var ticket = ticket(a);
        Callable<String> call = () -> {
            try { emailLogin.confirm(a.member(), a.session(), ticket.token()); return "OK"; }
            catch (ApiException e) { return e.code(); }
        };
        assertThat(race(call, call)).containsExactlyInAnyOrder("OK", "CHALLENGE_ALREADY_VERIFIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_proof WHERE purpose='EMAIL_LOGIN'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentEnableSameKeyReplaysAndDifferentKeysCannotDuplicateCredential() throws Exception {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); String proof = ready(a); UUID key = UUID.randomUUID();
        assertThat(race(() -> enableOutcome(a, proof, key), () -> enableOutcome(a, proof, key))).containsExactly("OK", "OK");
        var b = kakaoAccount("other@example.com", "other", "67890"); String second = ready(b);
        assertThat(race(() -> enableOutcome(b, second, UUID.randomUUID()), () -> enableOutcome(b, second, UUID.randomUUID())))
                .containsExactlyInAnyOrder("OK", "EMAIL_LOGIN_ALREADY_ENABLED");
        assertThat(count("password_credential")).isEqualTo(2); assertThat(count("credit_transaction")).isEqualTo(2);
    }

    @Test
    void emailChangeAndCredentialCreationSerializeOnSameMember() throws Exception {
        var a = kakaoAccount("reader@example.com", "reader", "12345"); String proof = ready(a);
        String change = prepareEmailChange(a, "new@example.com");
        var results = race(() -> {
            changes.completeEmail(a.member(), a.session(), change); return "CHANGED";
        }, () -> enableOutcome(a, proof, UUID.randomUUID()));
        assertThat(results).contains("CHANGED");
        assertThat(results.get(1)).isIn("OK", "PROOF_EXPIRED");
        assertThat(signup.me(a.member()).email()).isEqualTo("new@example.com");
        if (results.get(1).equals("OK"))
            assertThat(auth.login("new@example.com", "new valid password", "login").member()).isEqualTo(a.member());
        else assertThat(count("password_credential")).isZero();
        assertThat(count("member")).isEqualTo(1); assertThat(count("credit_transaction")).isEqualTo(1);
    }

    @Test
    void emailAdditionEndpointsRequireBearerOriginAndIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/me/login-methods/email/verifications").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("reauthProof", secrets.token()))))
                .andExpect(status().isUnauthorized());
        var a = kakaoAccount("reader@example.com", "reader", "12345");
        String bearer = "Bearer " + a.signup().data().accessToken();
        String body = mapper.writeValueAsString(Map.of("emailLoginProof", secrets.token(), "password", "new valid password"));
        mvc.perform(post("/api/v1/me/login-methods/email").header("Authorization", bearer).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/login-methods/email").header("Authorization", bearer).header("Idempotency-Key", UUID.randomUUID())
                        .header("Origin", "https://evil.example").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/login-methods/email/verifications/confirm").header("Authorization", bearer)
                        .contentType("application/json").content("{\"token\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
