package kr.nospo.nospo_backend.ai;

import com.sun.net.httpserver.HttpServer;
import kr.nospo.credit.contract.SignupCredits;
import kr.nospo.nospo_backend.NospoBackendApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = NospoBackendApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_questions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "nospo.ai.poll-ms=100",
        "nospo.auth.allowed-origins=https://app.example.test",
        "nospo.auth.kakao.redirect-uri=https://api.example.test/api/v1/auth/kakao/callback",
        "nospo.auth.kakao.frontend-callback=https://app.example.test/oauth/callback",
        "nospo.auth.kakao.return-paths=/"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "ai-local"})
class AiCreditsAndQuestionsTests {
    private static final UUID BOOK = AiContextConfiguration.SAMPLE_BOOK;
    private static final String BEARER = "Authorization";
    private static final String KEY = "Idempotency-Key";
    private static final String BALANCE = "/api/v1/me/ai/credits";
    private static final String QUESTIONS = "/api/v1/ai/questions";
    private static final AtomicReference<String> upstreamRequest = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> hold = new AtomicReference<>();
    private static final HttpServer OPENAI = stubOpenAi();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired SignupCredits signupCredits;

    @DynamicPropertySource
    static void openAi(DynamicPropertyRegistry properties) {
        properties.add("nospo.ai.openai-url",
                () -> "http://127.0.0.1:" + OPENAI.getAddress().getPort() + "/v1/chat/completions");
        properties.add("nospo.ai.openai-key", () -> "test-key");
    }

    @AfterAll
    static void stopOpenAi() { OPENAI.stop(0); }

    @Test
    void administratorCanGrantAndRecoverOnceWhileMemberCannotAdjust() throws Exception {
        var user = member("USER");
        var admin = member("ADMIN");
        String path = "/api/v1/admin/members/" + user.id() + "/ai/credit-adjustments";
        UUID key = UUID.randomUUID();
        String grant = "{\"type\":\"GRANT\",\"amount\":3,\"reason\":\"Support credit\"}";
        mvc.perform(post(path).contentType("application/json").header(KEY, key).content(grant))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType("application/json").header(BEARER, user.token()).header(KEY, key).content(grant))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        var granted = adjust(admin, path, key, "GRANT", 3, 201);
        assertThat(adjust(admin, path, key, "GRANT", 3, 201).path("transactionId"))
                .isEqualTo(granted.path("transactionId"));
        assertThat(adjust(admin, path, key, "GRANT", 4, 409).path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(adjust(admin, path, UUID.randomUUID(), "RECOVERY", 9, 409).path("code").asString())
                .isEqualTo("INSUFFICIENT_AVAILABLE_CREDITS");
        var recovered = adjust(admin, path, UUID.randomUUID(), "RECOVERY", 2, 201);
        assertThat(recovered.path("amount").asInt()).isEqualTo(-2);
        mvc.perform(get(BALANCE).header(BEARER, user.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(6)).andExpect(jsonPath("$.data.available").value(6));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM credit_transaction WHERE member_id=?", Integer.class,
                id(user.id()))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM credit_operation WHERE actor_id=? AND member_id=?",
                Integer.class, id(admin.id()), id(user.id()))).isEqualTo(2);
    }

    @Test
    void openAiStreamingIsReplayableAndChargesOnlyOnceWithOwnedHistory() throws Exception {
        var reader = member("USER");
        var stranger = member("USER");
        var admin = member("ADMIN");
        var latch = new CountDownLatch(1);
        hold.set(latch);
        UUID key = UUID.randomUUID();
        JsonNode accepted;
        try {
            accepted = ask(reader, key, "Who is the librarian?");
            assertThat(ask(reader, key, "Who is the librarian?").path("questionId")).isEqualTo(accepted.path("questionId"));
            mvc.perform(get(BALANCE).header(BEARER, reader.token())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reserved").value(1)).andExpect(jsonPath("$.data.available").value(4));
            String path = "/api/v1/admin/members/" + reader.id() + "/ai/credit-adjustments";
            assertThat(adjust(admin, path, UUID.randomUUID(), "RECOVERY", 5, 409).path("code").asString())
                    .isEqualTo("INSUFFICIENT_AVAILABLE_CREDITS");
        } finally {
            latch.countDown();
            hold.set(null);
        }
        String question = QUESTIONS + "/" + accepted.path("questionId").asString();
        awaitStatus(reader, accepted, "SUCCEEDED");
        assertThat(upstreamRequest.get()).contains("test-key", "gpt-5-mini", "하린");
        var stream = mvc.perform(get(question + "/events").header(BEARER, reader.token()).header("Last-Event-ID", "1"))
                .andExpect(request().asyncStarted()).andReturn();
        stream.getAsyncResult(10_000);
        String replay = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(replay).contains("event:delta", "event:completed", "\"chargedCredits\":1")
                .doesNotContain("event:started");
        mvc.perform(get(question).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("하린")))
                .andExpect(jsonPath("$.data.citations[0].sourcePosition.value").value(10));
        String conversation = "/api/v1/ai/conversations/" + accepted.path("conversationId").asString();
        mvc.perform(get(conversation + "/messages").header(BEARER, stranger.token())).andExpect(status().isNotFound());
        var first = data(mvc.perform(get(conversation + "/messages?limit=1").header(BEARER, reader.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].role").value("USER")).andReturn());
        mvc.perform(get(conversation + "/messages").param("cursor", first.path("nextCursor").asString())
                .header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].role").value("ASSISTANT"));
        mvc.perform(get("/api/v1/me/ai/conversations").header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].bookId").value(BOOK.toString()));
        mvc.perform(get(BALANCE).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(4)).andExpect(jsonPath("$.data.reserved").value(0));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM credit_operation WHERE member_id=? AND kind='USE'",
                Integer.class, id(reader.id()))).isEqualTo(1);
        db.update("UPDATE ai_question SET completed_at=? WHERE question_id=?", at(Instant.now().minusSeconds(90_000)),
                id(UUID.fromString(accepted.path("questionId").asString())));
        mvc.perform(get(question + "/events").header(BEARER, reader.token()).accept("text/event-stream"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("STREAM_REPLAY_EXPIRED"));
    }

    @Test
    void upstreamFailureReleasesReservationAndFuturePositionDoesNotEnqueue() throws Exception {
        var reader = member("USER");
        var failed = ask(reader, UUID.randomUUID(), "FAIL_STREAM");
        assertThat(awaitStatus(reader, failed, "FAILED").path("chargedCredits").asInt()).isZero();
        mvc.perform(post(QUESTIONS).contentType("application/json").header(KEY, UUID.randomUUID())
                .header(BEARER, reader.token()).content(mapper.writeValueAsString(Map.of("bookId", BOOK, "question", "future",
                        "position", Map.of("unit", "EPISODE", "value", 41)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("POSITION_EXCEEDS_CURRENT"));
        mvc.perform(get(BALANCE).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(5));
    }

    @Test
    void resultFromBeforeAccountWithdrawalCannotBeSavedAfterRecovery() throws Exception {
        var reader = member("USER");
        var release = new CountDownLatch(1);
        hold.set(release);
        JsonNode accepted;
        try {
            accepted = ask(reader, UUID.randomUUID(), "Question before account recovery");
            awaitStatus(reader, accepted, "RUNNING");
            // The lifecycle increments this generation on withdrawal and retains it on recovery.
            db.update("UPDATE member SET withdrawal_generation=withdrawal_generation+1 WHERE member_id=?", id(reader.id()));
        } finally {
            release.countDown();
            hold.set(null);
        }
        var failed = awaitStatus(reader, accepted, "FAILED");
        assertThat(failed.path("error").path("code").asString()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(failed.path("chargedCredits").asInt()).isZero();
        mvc.perform(get(BALANCE).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(5));
    }

    @Test
    void graphRegenerationCreatesANewOwnedResultAndChargesOncePerKey() throws Exception {
        var reader = member("USER");
        var other = member("USER");
        UUID key = UUID.randomUUID();
        var input = Map.of("bookId", BOOK, "position", Map.of("unit", "EPISODE", "value", 10));
        var accepted = graph(reader, key, input);
        var finished = awaitStatus(reader, accepted, "SUCCEEDED");
        assertThat(finished.path("type").asString()).isEqualTo("GRAPH");
        assertThat(finished.path("chargedCredits").asInt()).isEqualTo(1);
        assertThat(graph(reader, key, input).path("jobId")).isEqualTo(accepted.path("jobId"));
        String graphId = finished.path("result").path("graphId").asString();
        String resultPath = "/api/v1/ai/graphs/" + graphId;
        mvc.perform(get(resultPath).header(BEARER, other.token())).andExpect(status().isNotFound());
        mvc.perform(get(resultPath).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].name").value("하린"))
                .andExpect(jsonPath("$.data.edges").isEmpty())
                .andExpect(jsonPath("$.data.generatedPosition.value").value(10))
                .andExpect(jsonPath("$.data.currentPosition.value").value(40))
                .andExpect(jsonPath("$.data.regenerationRequired").value(true));

        var regenerated = graph(reader, UUID.randomUUID(), Map.of("bookId", BOOK, "regenerateFromGraphId", graphId));
        var second = awaitStatus(reader, regenerated, "SUCCEEDED");
        assertThat(second.path("result").path("graphId").asString()).isNotEqualTo(graphId);
        mvc.perform(get("/api/v1/ai/graphs/" + second.path("result").path("graphId").asString())
                .header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.edges[0].type").value("MENTOR"))
                .andExpect(jsonPath("$.data.citations[0].sourcePosition.value").value(20));
        mvc.perform(get(resultPath).header(BEARER, reader.token())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/ai/graphs").header(BEARER, reader.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(get(BALANCE).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(3)).andExpect(jsonPath("$.data.reserved").value(0));
    }

    @Test
    void ledgerIncludesSignupAndAllowsOnlyOneRefundOfOwnedUsage() throws Exception {
        var reader = member("USER");
        var admin = member("ADMIN");
        var accepted = graph(reader, UUID.randomUUID(), Map.of("bookId", BOOK));
        awaitStatus(reader, accepted, "SUCCEEDED");

        String ledger = "/api/v1/me/ai/credit-transactions";
        var first = data(mvc.perform(get(ledger).param("limit", "1").header(BEARER, reader.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].type").value("USE")).andReturn());
        String use = first.path("items").get(0).path("transactionId").asString();
        mvc.perform(get(ledger).param("cursor", first.path("nextCursor").asString()).header(BEARER, reader.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].reason").value("SIGNUP"));
        mvc.perform(get(ledger + "/" + use).header(BEARER, admin.token())).andExpect(status().isNotFound());
        mvc.perform(get(ledger + "/" + use).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(accepted.path("jobId").asString()));

        String refundPath = "/api/v1/admin/ai/credit-transactions/" + use + "/refund";
        UUID key = UUID.randomUUID();
        String reason = mapper.writeValueAsString(Map.of("reason", "Support refund"));
        mvc.perform(post(refundPath).contentType("application/json").header(KEY, key)
                .header(BEARER, reader.token()).content(reason)).andExpect(status().isForbidden());
        var refunded = data(mvc.perform(post(refundPath).contentType("application/json").header(KEY, key)
                .header(BEARER, admin.token()).content(reason)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalTransactionId").value(use)).andReturn());
        var replay = data(mvc.perform(post(refundPath).contentType("application/json").header(KEY, key)
                .header(BEARER, admin.token()).content(reason)).andExpect(status().isCreated()).andReturn());
        assertThat(replay.path("transactionId")).isEqualTo(refunded.path("transactionId"));
        mvc.perform(post(refundPath).contentType("application/json").header(KEY, UUID.randomUUID())
                .header(BEARER, admin.token()).content(reason)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_REFUNDED"));
        mvc.perform(get("/api/v1/admin/members/" + reader.id() + "/ai/credit-transactions")
                .param("type", "REFUND").header(BEARER, admin.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get(BALANCE).header(BEARER, reader.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(5));
    }

    private JsonNode graph(Member member, UUID key, Map<String, ?> input) throws Exception {
        return data(mvc.perform(post("/api/v1/ai/graphs").contentType("application/json").header(KEY, key)
                .header(BEARER, member.token()).content(mapper.writeValueAsString(input)))
                .andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode ask(Member member, UUID key, String question) throws Exception {
        return data(mvc.perform(post(QUESTIONS).contentType("application/json").header(KEY, key)
                .header(BEARER, member.token()).content(mapper.writeValueAsString(Map.of("bookId", BOOK, "question", question))))
                .andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode awaitStatus(Member member, JsonNode accepted, String expected) throws Exception {
        String path = "/api/v1/ai/jobs/" + accepted.path("jobId").asString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode job;
        do {
            var result = mvc.perform(get(path).header(BEARER, member.token())).andReturn();
            if (result.getResponse().getStatus() == 404) {
                // A withdrawn generation is hidden while its worker is still settling it.
                job = mapper.createObjectNode();
                Thread.sleep(75);
                continue;
            }
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            job = data(result);
            if (expected.equals(job.path("status").asString())) return job;
            Thread.sleep(75);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + expected + ": " + job);
    }

    private JsonNode adjust(Member actor, String path, UUID key, String type, int amount, int statusCode) throws Exception {
        var result = mvc.perform(post(path).contentType("application/json").header(KEY, key).header(BEARER, actor.token())
                .content(mapper.writeValueAsString(Map.of("type", type, "amount", amount, "reason", "Support credit"))))
                .andExpect(status().is(statusCode)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).path(statusCode == 201 ? "data" : "error");
    }

    private JsonNode data(MvcResult result) {
        return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private Member member(String role) {
        UUID member = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        String name = member.toString().substring(0, 12);
        String email = name + "@example.com";
        Instant now = Instant.now();
        db.update("""
                INSERT INTO member (member_id,nickname,nickname_key,phone_number,role,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, id(member), name, name, "+821012345678", role, at(now), at(now));
        db.update("""
                INSERT INTO account (member_id,email,email_key,email_verified_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?)
                """, id(member), email, email, at(now), at(now), at(now));
        db.update("INSERT INTO auth_session(session_id,member_id,created_at,expires_at) VALUES (?,?,?,?)",
                id(session), id(member), at(now), at(now.plusSeconds(1800)));
        signupCredits.grant(member, now);
        int eventOrder = 0;
        for (String type : List.of("SERVICE", "PRIVACY")) {
            UUID term = UUID.nameUUIDFromBytes(type.getBytes(StandardCharsets.UTF_8));
            UUID version = UUID.nameUUIDFromBytes((type + ":1").getBytes(StandardCharsets.UTF_8));
            if (db.queryForObject("SELECT COUNT(*) FROM term WHERE term_id=?", Integer.class, id(term)) == 0) {
                db.update("INSERT INTO term(term_id,type,created_at) VALUES (?,?,?)", id(term), type, at(now));
                db.update("""
                        INSERT INTO term_version(term_version_id,term_id,version,title,content,required,
                            publication_status,effective_at,created_at)
                        VALUES (?,?,'1',?,'Policy',TRUE,'PUBLISHED',?,?)
                        """, id(version), id(term), type, at(now.minusSeconds(60)), at(now));
            }
            db.update("""
                    INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at,event_order)
                    VALUES (?,?,?,TRUE,?,?)
                    """, id(UUID.randomUUID()), id(member), id(version), at(now), ++eventOrder);
        }
        var jwt = JwtClaimsSet.builder().issuer("nospo").subject(member.toString()).claim("sid", session.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(900)).build();
        return new Member(member, "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), jwt)).getTokenValue());
    }

    private record Member(UUID id, String token) {}

    private static HttpServer stubOpenAi() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    upstreamRequest.set(exchange.getRequestHeaders().getFirst("Authorization") + "\n" + request);
                    var latch = hold.get();
                    if (latch != null && !latch.await(10, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    var output = exchange.getResponseBody();
                    output.write("""
                            data: {"choices":[{"delta":{"content":"하린입니다. [00000000-0000-0000-0000-000000000103]"}}]}

                            """.getBytes(StandardCharsets.UTF_8));
                    if (!request.contains("FAIL_STREAM")) output.write("""
                            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                            data: [DONE]

                            """.getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
