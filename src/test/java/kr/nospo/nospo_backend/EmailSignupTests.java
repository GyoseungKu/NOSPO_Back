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
