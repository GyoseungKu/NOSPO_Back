package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import kr.nospo.identity.application.*;
import kr.nospo.lifecycle.application.SignupService;
import kr.nospo.support.ApiException;
import kr.nospo.support.Secrets;
import kr.nospo.terms.contract.Terms;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailSignupTests.TimeConfig.class)
class EmailSignupTests {
    @Autowired EmailVerificationService verification;
    @Autowired EmailDeliveryService delivery;
    @Autowired SignupService signup;
    @Autowired IdentityStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Secrets secrets;
    @MockitoBean EmailSender sender;
    final List<Terms.Consent> consents = new ArrayList<>();
    final Map<String, String> sent = new ConcurrentHashMap<>();

    @Autowired kr.nospo.identity.application.AuthService auth;
    @Autowired kr.nospo.identity.application.PasswordResetService resets;
    @Autowired LoginMethodService loginMethods;
    @Autowired LoginMethodStore loginMethodStore;

    @Test
    void loginMethodsReflectEmailKakaoAndCombinedAccounts() throws Exception {
        var original = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        UUID member = original.data().member().memberId();
        assertThat(original.data().member().loginMethods()).containsExactly("EMAIL");
        assertThat(loginMethods.get(member).canLinkKakao()).isTrue();
        assertThat(loginMethods.get(member).canAddEmail()).isFalse();
        mvc.perform(get("/api/v1/me/login-methods")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/login-methods")
                        .header("Authorization", "Bearer " + original.data().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginMethods[0]").value("EMAIL"))
                .andExpect(jsonPath("$.data.canAddEmail").value(false))
                .andExpect(jsonPath("$.data.canLinkKakao").value(true));

        loginMethods.recordVerifiedKakao(member, "12345");
        assertThat(loginMethodStore.kakaoMember("12345")).contains(member);
        assertThat(loginMethods.get(member).loginMethods()).containsExactly("EMAIL", "KAKAO");
        assertThat(loginMethods.get(member).canAddEmail()).isFalse();
        assertThat(loginMethods.get(member).canLinkKakao()).isFalse();
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"email\":\"reader@example.com\",\"password\":\" valid password \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.loginMethods[1]").value("KAKAO"));
        assertThat(signup.me(member).loginMethods()).containsExactly("EMAIL", "KAKAO");

        // A future Kakao signup creates an account without password_credential.
        jdbc.update("DELETE FROM password_credential WHERE member_id=?", id(member));
        mvc.perform(get("/api/v1/me/login-methods")
                        .header("Authorization", "Bearer " + original.data().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginMethods.length()").value(1))
                .andExpect(jsonPath("$.data.loginMethods[0]").value("KAKAO"))
                .andExpect(jsonPath("$.data.canAddEmail").value(true))
                .andExpect(jsonPath("$.data.canLinkKakao").value(false));
        mvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + original.data().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginMethods[0]").value("KAKAO"));
        code(() -> auth.login("reader@example.com", " valid password ", "login"), "INVALID_CREDENTIALS");
    }

    @Test
    void kakaoOnlyResetDoesNotSendMailOrCreatePassword() throws Exception {
        var original = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        UUID member = original.data().member().memberId();
        loginMethods.recordVerifiedKakao(member, "12345");
        jdbc.update("DELETE FROM password_credential WHERE member_id=?", id(member));
        sent.clear();
        mvc.perform(post("/api/v1/auth/password-resets").contentType("application/json")
                        .content("{\"email\":\"reader@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.verificationId").isNotEmpty());
        delivery.processDue();
        assertThat(sent).isEmpty();
        assertThat(count("password_credential")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_challenge WHERE"
                + " purpose='PASSWORD_RESET' AND member_id IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void resetCompletionRechecksPasswordCredentialAndRollsBack() {
        var original = signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "reader"));
        UUID member = original.data().member().memberId();
        var request = resets.request("reader@example.com", "reset");
        delivery.processDue();
        var verified = resets.verify(request.verificationId(), sent.get("reader@example.com").substring(6));
        jdbc.update("DELETE FROM password_credential WHERE member_id=?", id(member));
        code(() -> resets.complete(verified.resetProof(), "new valid password", "reset"), "INVALID_PROOF");
        assertThat(count("password_credential")).isZero();
        // Failed reset must not revoke sessions or consume the proof.
        assertThat(store.sessionActive(member, sessionId(original), clock.instant())).isTrue();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM auth_proof WHERE proof_hash=?",
                java.sql.Timestamp.class, secrets.hash(verified.resetProof()))).isNull();
    }

    @Test
    void kakaoConstraintsAndInactiveMembersAreEnforced() {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "first"));
        var second = signup.signup(UUID.randomUUID(), command(proof("second@example.com"), "second"));
        UUID a = first.data().member().memberId(), b = second.data().member().memberId();
        loginMethods.recordVerifiedKakao(a, "12345");
        code(() -> loginMethods.recordVerifiedKakao(b, "12345"), "SOCIAL_ACCOUNT_ALREADY_LINKED");
        code(() -> loginMethods.recordVerifiedKakao(a, "67890"), "SOCIAL_ACCOUNT_ALREADY_LINKED");
        code(() -> loginMethods.recordVerifiedKakao(b, ""), "VALIDATION_ERROR");
        jdbc.update("UPDATE member SET status='WITHDRAWING' WHERE member_id=?", id(b));
        code(() -> loginMethods.recordVerifiedKakao(b, "67890"), "INVALID_CREDENTIALS");
        code(() -> loginMethods.get(b), "INVALID_CREDENTIALS");
        assertThat(count("social_identity")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(2);
    }

    @Test
    void concurrentKakaoOwnersHaveExactlyOneWinner() throws Exception {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "first"));
        var second = signup.signup(UUID.randomUUID(), command(proof("second@example.com"), "second"));
        raceKakao(first.data().member().memberId(), "12345", second.data().member().memberId(), "12345");
    }

    @Test
    void concurrentKakaoSubjectsForOneMemberHaveExactlyOneWinner() throws Exception {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "first"));
        UUID member = first.data().member().memberId();
        raceKakao(member, "12345", member, "67890");
    }

    void raceKakao(UUID first, String firstSubject, UUID second, String secondSubject) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = new ArrayList<>();
            for (var entry : List.of(Map.entry(first, firstSubject), Map.entry(second, secondSubject))) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    try {
                        loginMethods.recordVerifiedKakao(entry.getKey(), entry.getValue());
                        return "OK";
                    } catch (ApiException e) {
                        return e.code();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "SOCIAL_ACCOUNT_ALREADY_LINKED");
        } finally {
            start.countDown();
        }
        assertThat(count("social_identity")).isEqualTo(1);
    }

    @Test
    void loginRotationReuseAndLogout() throws Exception {
        signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "독서가"));
        code(() -> auth.login("reader@example.com", "wrong", "login"), "INVALID_CREDENTIALS");
        var session = auth.login("reader@example.com", " valid password ", "login");
        var rotated = auth.refresh(session.refresh());
        assertThat(rotated.refresh()).isNotEqualTo(session.refresh());
        code(() -> auth.refresh(session.refresh()), "INVALID_CREDENTIALS");
        code(() -> auth.refresh(rotated.refresh()), "INVALID_CREDENTIALS");
        var next = auth.login("reader@example.com", " valid password ", "login");
        auth.logout(null, next.refresh());
        code(() -> auth.refresh(next.refresh()), "INVALID_CREDENTIALS");
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\"reader@example.com\",\"password\":\" valid"
                                            + " password \"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("refreshToken", true));
        mvc.perform(post("/api/v1/auth/token/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetConsumesProofAndRevokesSessions() {
        var original =
                signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "독서가"));
        var issued = resets.request("reader@example.com", "reset");
        delivery.processDue();
        String resetCode = sent.get("reader@example.com").substring(6);
        var verified = resets.verify(issued.verificationId(), resetCode);
        resets.complete(verified.resetProof(), "new valid password", "reset");
        code(
                () -> resets.complete(verified.resetProof(), "another password", "reset"),
                "INVALID_PROOF");
        code(() -> auth.refresh(original.refreshToken()), "INVALID_CREDENTIALS");
        code(
                () -> auth.login("reader@example.com", " valid password ", "login"),
                "INVALID_CREDENTIALS");
        assertThat(auth.login("reader@example.com", "new valid password", "login").accessToken())
                .isNotBlank();
    }

    @Test
    void resetWrongAttemptsArePersistedAndUnknownEmailDoesNotSend() {
        signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "독서가"));
        var issued = resets.request("reader@example.com", "reset");
        delivery.processDue();
        String actual = sent.get("reader@example.com").substring(6);
        String wrong = actual.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++)
            code(() -> resets.verify(issued.verificationId(), wrong), "INVALID_VERIFICATION");
        code(() -> resets.verify(issued.verificationId(), actual), "INVALID_VERIFICATION");
        resets.request("unknown@example.com", "reset");
        delivery.processDue();
        assertThat(sent).doesNotContainKey("unknown@example.com");
    }

    @Test
    void refreshLogoutAndResetHttpContracts() throws Exception {
        var original =
                signup.signup(UUID.randomUUID(), command(proof("reader@example.com"), "독서가"));
        mvc.perform(
                        post("/api/v1/auth/token/refresh")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "refreshToken", original.refreshToken()))
                                .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        var response =
                mvc.perform(
                                post("/api/v1/auth/token/refresh")
                                        .cookie(
                                                new jakarta.servlet.http.Cookie(
                                                        "refreshToken", original.refreshToken()))
                                        .header("Origin", "http://localhost:3000"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.expiresIn").value(900))
                        .andReturn()
                        .getResponse();
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(response.getCookie("refreshToken"))
                                .header("Origin", "http://localhost:3000"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("refreshToken", 0));
        var issuedResponse =
                mvc.perform(
                                post("/api/v1/auth/password-resets")
                                        .contentType("application/json")
                                        .content("{\"email\":\"reader@example.com\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        String verificationId =
                mapper.readTree(issuedResponse.getContentAsString())
                        .path("data")
                        .path("verificationId")
                        .asText();
        delivery.processDue();
        var verifiedResponse =
                mvc.perform(
                                post("/api/v1/auth/password-resets/verify")
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "verificationId",
                                                                verificationId,
                                                                "code",
                                                                sent.get("reader@example.com")
                                                                        .substring(6)))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        String resetProof =
                mapper.readTree(verifiedResponse.getContentAsString())
                        .path("data")
                        .path("resetProof")
                        .asText();
        mvc.perform(
                        post("/api/v1/auth/password-resets/complete")
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "resetProof",
                                                        resetProof,
                                                        "newPassword",
                                                        "new valid password"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void lateSignupReplayRequiresLogin() {
        UUID key = UUID.randomUUID();
        var command = command(proof("reader@example.com"), "독서가");
        signup.signup(key, command);
        clock.advance(901);
        code(() -> signup.signup(key, command), "SIGNUP_ALREADY_COMPLETED_LOGIN_REQUIRED");
    }

    @Autowired kr.nospo.identity.application.AccountChangeService changes;
    @Autowired kr.nospo.member.application.ProfileService profiles;
    @Autowired org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    private UUID sessionId(SignupService.Completed completed) {
        return UUID.fromString(
                decoder.decode(completed.data().accessToken()).getClaimAsString("sid"));
    }

    @Test
    void profileChangesAreVisibleAndDuplicateNicknameRollsBack() throws Exception {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "첫회원"));
        signup.signup(UUID.randomUUID(), command(proof("second@example.com"), "다른회원"));
        UUID member = first.data().member().memberId(), session = sessionId(first);
        assertThat(profiles.nickname(member, session, "새닉네임")).isEqualTo("새닉네임");
        code(() -> profiles.nickname(member, session, "다른회원"), "NICKNAME_ALREADY_EXISTS");
        profiles.phone(member, session, "010-9876-5432");
        var me = signup.me(member);
        assertThat(me.nickname()).isEqualTo("새닉네임");
        assertThat(me.phoneNumber()).isEqualTo("+821098765432");
        assertThat(me.phoneVerified()).isFalse();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/me/nickname")
                                .header("Authorization", "Bearer " + first.data().accessToken())
                                .contentType("application/json")
                                .content("{\"nickname\":\"웹변경\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("웹변경"));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/me/phone-number")
                                .contentType("application/json")
                                .content("{\"phoneNumber\":\"01012345678\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void imageUploadSanitizesAndDeleteClearsMe() throws Exception {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "첫회원"));
        UUID member = first.data().member().memberId(), session = sessionId(first);
        var image =
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        var file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "photo.svg", "text/plain", bytes.toByteArray());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart(
                                        org.springframework.http.HttpMethod.PUT,
                                        "/api/v1/me/profile-image")
                                .file(file)
                                .header("Authorization", "Bearer " + first.data().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value("/api/v1/me/profile-image"));
        assertThat(signup.me(member).profileImageUrl()).isEqualTo("/api/v1/me/profile-image");
        assertThat(
                        javax.imageio.ImageIO.read(
                                        new java.io.ByteArrayInputStream(
                                                profiles.image(member, session)))
                                .getWidth())
                .isEqualTo(2);
        code(() -> profiles.image(member, session, "<svg/>".getBytes()), "INVALID_IMAGE");
        code(
                () -> profiles.image(member, session, new byte[5 * 1024 * 1024 + 1]),
                "IMAGE_TOO_LARGE");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/api/v1/me/profile-image")
                                .header("Authorization", "Bearer " + first.data().accessToken()))
                .andExpect(status().isNoContent());
        assertThat(signup.me(member).profileImageUrl()).isNull();
        code(() -> profiles.image(member, session), "RESOURCE_NOT_FOUND");
    }

    @Test
    void emailChangeIsBoundToSessionAndInvalidatesOldRecovery() {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "첫회원"));
        UUID member = first.data().member().memberId(), session = sessionId(first);
        var recovery = resets.request("first@example.com", "reset");
        delivery.processDue();
        var recoveryProof =
                resets.verify(
                        recovery.verificationId(), sent.get("first@example.com").substring(6));
        var reauth = changes.reauthenticate(member, session, " valid password ", "CHANGE_EMAIL");
        var other = auth.login("first@example.com", " valid password ", "login");
        UUID otherSession =
                UUID.fromString(decoder.decode(other.accessToken()).getClaimAsString("sid"));
        code(
                () ->
                        changes.requestEmail(
                                member, otherSession, "new@example.com", reauth.reauthProof()),
                "INVALID_PROOF");
        var issued = changes.requestEmail(member, session, "new@example.com", reauth.reauthProof());
        code(
                () ->
                        changes.requestEmail(
                                member, session, "new@example.com", reauth.reauthProof()),
                "INVALID_PROOF");
        delivery.processDue();
        String codeValue = sent.get("new@example.com").substring(7);
        code(
                () -> changes.verifyEmail(member, otherSession, issued.verificationId(), codeValue),
                "INVALID_VERIFICATION");
        var verified = changes.verifyEmail(member, session, issued.verificationId(), codeValue);
        assertThat(changes.completeEmail(member, session, verified.emailChangeProof()))
                .isEqualTo("new@example.com");
        assertThat(signup.me(member).email()).isEqualTo("new@example.com");
        code(
                () -> changes.completeEmail(member, session, verified.emailChangeProof()),
                "INVALID_PROOF");
        code(
                () -> resets.complete(recoveryProof.resetProof(), "new password!", "reset"),
                "INVALID_PROOF");
        code(
                () -> auth.login("first@example.com", " valid password ", "login"),
                "INVALID_CREDENTIALS");
        assertThat(auth.login("new@example.com", " valid password ", "login").member())
                .isEqualTo(member);
    }

    @Test
    void passwordChangeRevokesEverySessionAndProof() throws Exception {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "첫회원"));
        UUID member = first.data().member().memberId(), session = sessionId(first);
        var other = auth.login("first@example.com", " valid password ", "login");
        var proof = changes.reauthenticate(member, session, " valid password ", "CHANGE_EMAIL");
        code(
                () -> changes.password(member, session, "wrong", "new password!"),
                "INVALID_CREDENTIALS");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/me/password")
                                .header("Authorization", "Bearer " + first.data().accessToken())
                                .contentType("application/json")
                                .content(
                                        "{\"currentPassword\":\" valid password"
                                            + " \",\"newPassword\":\"new password!\"}"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("refreshToken", 0));
        code(() -> auth.refresh(first.refreshToken()), "INVALID_CREDENTIALS");
        code(() -> auth.refresh(other.refresh()), "INVALID_CREDENTIALS");
        code(
                () -> changes.requestEmail(member, session, "new@example.com", proof.reauthProof()),
                "INVALID_CREDENTIALS");
        assertThat(auth.login("first@example.com", "new password!", "login").member())
                .isEqualTo(member);
    }

    @Test
    void expiredReauthAndChangedTargetOwnershipAreRejected() {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "첫회원"));
        UUID member = first.data().member().memberId(), session = sessionId(first);
        var expired = changes.reauthenticate(member, session, " valid password ", "CHANGE_EMAIL");
        clock.advance(301);
        code(
                () ->
                        changes.requestEmail(
                                member, session, "new@example.com", expired.reauthProof()),
                "INVALID_PROOF");
        var valid = changes.reauthenticate(member, session, " valid password ", "CHANGE_EMAIL");
        var issued = changes.requestEmail(member, session, "new@example.com", valid.reauthProof());
        delivery.processDue();
        var verified =
                changes.verifyEmail(
                        member,
                        session,
                        issued.verificationId(),
                        sent.get("new@example.com").substring(7));
        signup.signup(UUID.randomUUID(), command(proof("new@example.com"), "둘째회원"));
        code(
                () -> changes.completeEmail(member, session, verified.emailChangeProof()),
                "EMAIL_ALREADY_EXISTS");
        assertThat(signup.me(member).email()).isEqualTo("first@example.com");
    }

    @Test
    void emailChangeHttpFlowAndCodeAttemptLimit() throws Exception {
        var first = signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "첫회원"));
        String bearer = "Bearer " + first.data().accessToken();
        var reauth =
                mvc.perform(
                                post("/api/v1/me/reauth/password")
                                        .header("Authorization", bearer)
                                        .contentType("application/json")
                                        .content(
                                                "{\"password\":\" valid password"
                                                    + " \",\"purpose\":\"CHANGE_EMAIL\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        String proof =
                mapper.readTree(reauth.getContentAsString())
                        .path("data")
                        .path("reauthProof")
                        .asText();
        var issued =
                mvc.perform(
                                post("/api/v1/me/email-changes")
                                        .header("Authorization", bearer)
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "newEmail",
                                                                "new@example.com",
                                                                "reauthProof",
                                                                proof))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        String verification =
                mapper.readTree(issued.getContentAsString())
                        .path("data")
                        .path("verificationId")
                        .asText();
        delivery.processDue();
        String codeValue = sent.get("new@example.com").substring(7);
        var verified =
                mvc.perform(
                                post("/api/v1/me/email-changes/verify")
                                        .header("Authorization", bearer)
                                        .contentType("application/json")
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "verificationId",
                                                                verification,
                                                                "code",
                                                                codeValue))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        String emailProof =
                mapper.readTree(verified.getContentAsString())
                        .path("data")
                        .path("emailChangeProof")
                        .asText();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/me/email")
                                .header("Authorization", bearer)
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("emailChangeProof", emailProof))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new@example.com"));
        clock.advance(61);
        UUID member = first.data().member().memberId(), session = sessionId(first);
        var fresh = changes.reauthenticate(member, session, " valid password ", "CHANGE_EMAIL");
        var next =
                changes.requestEmail(member, session, "another@example.com", fresh.reauthProof());
        delivery.processDue();
        String actual = sent.get("another@example.com").substring(7),
                wrong = actual.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++)
            code(
                    () -> changes.verifyEmail(member, session, next.verificationId(), wrong),
                    "INVALID_VERIFICATION");
        code(
                () -> changes.verifyEmail(member, session, next.verificationId(), actual),
                "INVALID_VERIFICATION");
    }

    @Autowired kr.nospo.member.application.ProfileImageCodec imageCodec;

    @Test
    void webpIsDecodedAndOversizedDimensionsAreRejected() throws Exception {
        byte[] webp =
                Base64.getDecoder()
                        .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        byte[] png = imageCodec.sanitize(webp);
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)).getWidth())
                .isEqualTo(1);
        var image =
                new java.awt.image.BufferedImage(
                        4097, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        code(() -> imageCodec.sanitize(output.toByteArray()), "INVALID_IMAGE_DIMENSIONS");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now.get();
        }

        void advance(long seconds) {
            now.updateAndGet(t -> t.plusSeconds(seconds));
        }
    }

    @BeforeEach
    void setup() {
        for (String table :
                List.of(
                        "social_signup_context",
                        "oauth_exchange",
                        "oauth_flow",
                        "social_identity",
                        "email_delivery",
                        "auth_proof",
                        "auth_challenge",
                        "idempotency_record",
                        "credit_transaction",
                        "credit_wallet",
                        "member_consent",
                        "refresh_token",
                        "auth_session",
                        "password_credential",
                        "account",
                        "member",
                        "term_version",
                        "term",
                        "auth_rate_limit",
                        "auth_guard")) jdbc.update("DELETE FROM " + table);
        clock.now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        reset(sender);
        sent.clear();
        consents.clear();
        doAnswer(
                        invocation -> {
                            sent.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(sender)
                .send(anyString(), anyString());
        for (String type : List.of("SERVICE", "PRIVACY", "OPTIONAL")) {
            UUID term = UUID.randomUUID(), version = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO term(term_id,type,created_at) VALUES (?,?,?)",
                    id(term),
                    type,
                    at(clock.instant()));
            jdbc.update(
                    "INSERT INTO"
                        + " term_version(term_version_id,term_id,version,title,content,required,publication_status,effective_at,requires_reconsent,created_at)"
                        + " VALUES (?,?,'1.0',?,'TEST FIXTURE',?,'PUBLISHED',?,false,?)",
                    id(version),
                    id(term),
                    type,
                    !type.equals("OPTIONAL"),
                    at(clock.instant().minusSeconds(1)),
                    at(clock.instant()));
            consents.add(new Terms.Consent(version, !type.equals("OPTIONAL")));
        }
    }

    String proof(String email) {
        verification.request(email, "127.0.0.1");
        delivery.processDue();
        return verification.confirm(sent.get(email.toLowerCase(Locale.ROOT))).emailProof();
    }

    SignupService.Command command(String proof, String nickname) {
        return new SignupService.Command(
                nickname, proof, " valid password ", "010-1234-5678", List.copyOf(consents));
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    void code(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void signupIsAtomicAndIdempotentAndAutomaticallyAuthenticated() throws Exception {
        String proof = proof("reader@example.com");
        UUID key = UUID.randomUUID();
        var request = command(proof, "책읽는새");
        var first = signup.signup(key, request);
        var second = signup.signup(key, request);
        assertThat(second).isEqualTo(first);
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("member_consent")).isEqualTo(3);
        assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet", Integer.class))
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM password_credential", String.class))
                .startsWith("$argon2id$")
                .doesNotContain("valid password");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM auth_proof WHERE consumed_at IS NOT NULL AND"
                                        + " member_id IS NOT NULL",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM email_delivery WHERE member_id IS NOT NULL"
                                        + " AND payload_ciphertext IS NULL",
                                Integer.class))
                .isEqualTo(1);
        mvc.perform(
                        get("/api/v1/me")
                                .header("Authorization", "Bearer " + first.data().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("reader@example.com"))
                .andExpect(jsonPath("$.data.phoneNumber").value("+821012345678"));
        code(() -> signup.signup(key, command(proof, "다른이름")), "IDEMPOTENCY_CONFLICT");
        code(() -> signup.signup(UUID.randomUUID(), request), "PROOF_ALREADY_USED");
        jdbc.update("UPDATE member SET status='WITHDRAWING'");
        mvc.perform(
                        get("/api/v1/me")
                                .header("Authorization", "Bearer " + first.data().accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredChallengeDoesNotDeleteLiveProof() {
        verification.request("reader@example.com", "127.0.0.1");
        delivery.processDue();
        clock.advance(540);
        var verified = verification.confirm(sent.get("reader@example.com"));
        clock.advance(61);
        delivery.cleanup();
        assertThat(count("auth_proof")).isEqualTo(1);
        assertThat(count("auth_challenge")).isEqualTo(1);
        assertThat(verified.expiresAt()).isAfter(clock.instant());
        signup.signup(UUID.randomUUID(), command(verified.emailProof(), "독서가"));
        clock.advance(600);
        delivery.cleanup();
        assertThat(count("auth_proof")).isZero();
        assertThat(count("auth_challenge")).isZero();
    }

    @Test
    void resendInvalidatesOldLinkAndProofAndCancelsQueuedMail() {
        String old = proof("reader@example.com"), oldToken = sent.get("reader@example.com");
        code(() -> verification.confirm(oldToken), "CHALLENGE_ALREADY_VERIFIED");
        assertThat(store.proof(secrets.hash(old)).orElseThrow().invalidatedAt()).isNull();
        code(() -> verification.request("READER@example.com", "127.0.0.1"), "RATE_LIMITED");
        clock.advance(61);
        verification.request("READER@example.com", "127.0.0.1");
        code(() -> verification.confirm(oldToken), "VERIFICATION_EXPIRED");
        code(() -> signup.signup(UUID.randomUUID(), command(old, "독서가")), "PROOF_EXPIRED");
        clock.advance(61);
        verification.request("reader@example.com", "127.0.0.1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM email_delivery WHERE status='CANCELLED' AND"
                                        + " payload_ciphertext IS NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void emailRetryUsesIdenticalLinkAndErasesFinalPayload() {
        List<String> tokens = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            tokens.add(invocation.getArgument(1));
                            if (tokens.size() == 1) throw new EmailSender.DeliveryFailure(false);
                            return null;
                        })
                .when(sender)
                .send(anyString(), anyString());
        verification.request("retry@example.com", "127.0.0.1");
        delivery.processDue();
        assertThat(jdbc.queryForObject("SELECT status FROM email_delivery", String.class))
                .isEqualTo("PENDING");
        clock.advance(16);
        delivery.processDue();
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0)).isEqualTo(tokens.get(1));
        assertThat(jdbc.queryForObject("SELECT status FROM email_delivery", String.class))
                .isEqualTo("ACCEPTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload_ciphertext FROM email_delivery", byte[].class))
                .isNull();
    }

    @Test
    void workerReclaimsAbandonedLeaseAndCancelsExpiredWork() {
        verification.request("retry@example.com", "127.0.0.1");
        jdbc.update(
                "UPDATE email_delivery SET status='PROCESSING',lease_token=?,lease_until=?",
                id(UUID.randomUUID()),
                at(clock.instant().minusSeconds(1)));
        delivery.processDue();
        verify(sender, times(1)).send(eq("retry@example.com"), anyString());
        verification.request("expired@example.com", "127.0.0.1");
        clock.advance(601);
        delivery.processDue();
        verify(sender, never()).send(eq("expired@example.com"), anyString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM email_delivery WHERE status='CANCELLED' AND"
                                        + " payload_ciphertext IS NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void permanentFailureDoesNotRetry() {
        doThrow(new EmailSender.DeliveryFailure(true)).when(sender).send(anyString(), anyString());
        verification.request("bad@example.com", "127.0.0.1");
        delivery.processDue();
        clock.advance(30);
        delivery.processDue();
        verify(sender, times(1)).send(anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT status FROM email_delivery", String.class))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload_ciphertext FROM email_delivery", byte[].class))
                .isNull();
    }

    @Test
    void duplicateNicknameAndInvalidConsentRollBackEverything() {
        signup.signup(UUID.randomUUID(), command(proof("first@example.com"), "SameName"));
        String proof = proof("next@example.com");
        code(
                () -> signup.signup(UUID.randomUUID(), command(proof, "samename")),
                "NICKNAME_ALREADY_EXISTS");
        var invalid =
                new SignupService.Command(
                        "새회원",
                        proof,
                        "valid password",
                        "+821012345678",
                        consents.stream()
                                .map(c -> new Terms.Consent(c.termVersionId(), false))
                                .toList());
        code(() -> signup.signup(UUID.randomUUID(), invalid), "REQUIRED_CONSENT_MISSING");
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(store.proof(secrets.hash(proof)).orElseThrow().consumedAt()).isNull();
        signup.signup(UUID.randomUUID(), command(proof, "새회원"));
        assertThat(count("member")).isEqualTo(2);
    }

    @Test
    void concurrentSignupCreatesOneMemberAndOneReward() throws Exception {
        String proof = proof("race@example.com");
        UUID key = UUID.randomUUID();
        var command = command(proof, "경합회원");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<SignupService.Completed> call =
                    () -> {
                        start.await();
                        return signup.signup(key, command);
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("member")).isEqualTo(1);
        assertThat(count("credit_transaction")).isEqualTo(1);
    }

    @Test
    void concurrentResendSerializesFirstRequest() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<String> call =
                    () -> {
                        start.await();
                        try {
                            verification.request("race@example.com", "127.0.0.1");
                            return "accepted";
                        } catch (ApiException ex) {
                            return ex.code();
                        }
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("accepted", "RATE_LIMITED");
        }
        assertThat(count("auth_challenge")).isEqualTo(1);
        assertThat(count("email_delivery")).isEqualTo(1);
    }

    @Test
    void httpContractCookieOriginValidationAndTerms() throws Exception {
        mvc.perform(get("/api/v1/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
        mvc.perform(
                        post("/api/v1/auth/signup/email-verifications")
                                .contentType("application/json")
                                .header("Origin", "https://evil.example")
                                .content("{\"email\":\"reader@example.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/auth/signup/email-verifications")
                                .contentType("application/json")
                                .content("{\"email\":\" Reader@Example.com \"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.verificationId").exists())
                .andExpect(header().string("Cache-Control", "no-store"));
        delivery.processDue();
        String token = sent.get("reader@example.com");
        var result =
                mvc.perform(
                                post("/api/v1/auth/signup/email-verifications/confirm")
                                        .contentType("application/json")
                                        .content(mapper.writeValueAsString(Map.of("token", token))))
                        .andExpect(status().isOk())
                        .andReturn();
        String proof =
                mapper.readTree(result.getResponse().getContentAsString())
                        .at("/data/emailProof")
                        .asString();
        mvc.perform(
                        post("/api/v1/auth/signup")
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(command(proof, "독서회원"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andExpect(cookie().secure("refreshToken", true))
                .andExpect(cookie().path("refreshToken", "/api/v1/auth"));
    }
}
