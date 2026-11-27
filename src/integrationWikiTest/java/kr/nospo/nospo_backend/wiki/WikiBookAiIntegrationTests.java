package kr.nospo.nospo_backend.wiki;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import kr.nospo.credit.contract.SignupCredits;
import kr.nospo.identity.application.KakaoLinkAccess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wiki_combined;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "nospo.auth.email-confirm-url=https://app.example.test/auth/confirm",
        "nospo.auth.allowed-origins=https://app.example.test",
        "nospo.auth.kakao.redirect-uri=https://api.example.test/api/v1/auth/kakao/callback",
        "nospo.auth.kakao.frontend-callback=https://app.example.test/oauth/callback",
        "nospo.auth.kakao.return-paths=/",
        "nospo.ai.poll-ms=50"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WikiBookAiIntegrationTests {
    private static HttpServer provider;
    private static final AtomicReference<String> upstream = new AtomicReference<>();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired SignupCredits signup;
    @MockitoBean KakaoLinkAccess consent;

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry properties) {
        String mysql = System.getenv("NOSPO_WIKI_TEST_MYSQL_URL");
        if (mysql != null && !mysql.isBlank()) {
            properties.add("spring.datasource.url", () -> mysql);
            properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            properties.add("spring.datasource.username", () -> System.getenv("NOSPO_WIKI_TEST_MYSQL_USER"));
            properties.add("spring.datasource.password", () -> System.getenv("NOSPO_WIKI_TEST_MYSQL_PASSWORD"));
        }
        try {
            if (provider == null) {
                provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                provider.createContext("/v1/chat/completions", exchange -> {
                    try (exchange) {
                        upstream.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write("""
                                data: {"choices":[{"delta":{"content":"기록에서 확인했습니다."}}]}

                                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                                data: [DONE]

                                """.getBytes(StandardCharsets.UTF_8));
                    }
                });
                provider.start();
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        properties.add("nospo.ai.openai-key", () -> "fixture");
        properties.add("nospo.ai.openai-url",
                () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1/chat/completions");
    }

    @AfterAll
    static void shutdown() {
        if (provider != null) {
            provider.stop(0);
            provider = null;
        }
    }

    @Test
    void approvesPermanentEditionRewardsOnceAndUsesTheScopedWikiForBothAiJobs() throws Exception {
        assertThat(db.queryForList("SELECT version FROM flyway_schema_history WHERE success=TRUE", String.class))
                .contains("12", "13", "14", "15", "16", "17", "18");
        UUID reader = member("USER");
        UUID admin = member("ADMIN");
        signup.grant(reader, Instant.now());
        UUID book = UUID.randomUUID();
        db.update("""
                INSERT INTO book_edition(book_id,book_type,title,source,source_item_id,total_units)
                VALUES (?,'WEB_NOVEL','별빛 도서관','TEST',?,100)
                """, id(book), id(book));
        db.update("INSERT INTO book_progress(member_id,book_id,current_value,highest_value,revision) VALUES (?,?,30,45,0)",
                id(reader), id(book));
        db.update("INSERT INTO book_shelf(member_id,book_id,added_at) VALUES (?,?,?)", id(reader), id(book), at(Instant.now()));
        var draft = write("/api/v1/wiki/drafts", reader, null, null,
                Map.of("bookId", book, "mode", "CREATE", "position", pos(30)), 201);
        String draftPath = "/api/v1/wiki/drafts/" + text(draft, "draftId");
        var character = write(draftPath + "/characters", reader, tag(draft), null,
                Map.of("firstRevealAt", pos(10), "profile", Map.of("name", "별빛 사서 하린", "description", "", "revealAt", pos(10))), 201);
        var future = write(draftPath + "/characters/" + text(character, "entityId") + "/profiles", reader,
                tag(character), null, Map.of("name", "숨겨진 도서관장", "description", "", "revealAt", pos(25)), 201);
        var submitted = write(draftPath + "/submissions", reader, tag(future), UUID.randomUUID(), null, 201);
        UUID submission = UUID.fromString(text(submitted, "submissionId"));
        String review = "/api/v1/admin/wiki/submissions/" + submission;
        var reviewDetails = mvc.perform(get(review).with(as(admin))).andExpect(status().isOk()).andReturn();
        UUID approvalKey = UUID.randomUUID();
        var approval = Map.of("rewardReason", "공개 검수", "rewardAmount", 2, "expectedWikiVersion", 0);
        MvcResult granted;
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = requests.submit(() -> {
                start.await();
                return write(review + "/approve", admin, tag(reviewDetails), approvalKey, approval, 200);
            });
            var retry = requests.submit(() -> {
                start.await();
                return write(review + "/approve", admin, tag(reviewDetails), approvalKey, approval, 200);
            });
            start.countDown();
            granted = first.get(10, TimeUnit.SECONDS);
            assertThat(text(retry.get(10, TimeUnit.SECONDS), "rewardTransactionId"))
                    .isEqualTo(text(granted, "rewardTransactionId"));
        }
        write(review + "/approve", admin, tag(reviewDetails), UUID.randomUUID(), approval, 409);
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM wiki_version v JOIN wiki w ON v.wiki_id=w.wiki_id WHERE w.book_id=?
                """, Integer.class, id(book))).isOne();
        mvc.perform(get("/api/v1/me/ai/credit-transactions/" + text(granted, "rewardTransactionId")).with(as(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionId").value(submission.toString()));
        assertThat(db.queryForObject("SELECT balance FROM credit_wallet WHERE member_id=?", Integer.class, id(reader))).isEqualTo(7);

        db.update("UPDATE book_progress SET current_value=20 WHERE book_id=? AND member_id=?", id(book), id(reader));
        String wiki = "/api/v1/books/" + book + "/wiki";
        mvc.perform(get(wiki + "/characters").with(as(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("별빛 사서 하린"));
        mvc.perform(get(wiki).with(as(reader)).param("positionValue", "25")).andExpect(status().is(422));

        var graph = write("/api/v1/ai/graphs", reader, null, UUID.randomUUID(), Map.of("bookId", book), 202);
        var graphJob = await(reader, text(graph, "jobId"));
        String graphId = json.readTree(graphJob.getResponse().getContentAsByteArray()).path("data").path("result")
                .path("graphId").asString();
        mvc.perform(get("/api/v1/ai/graphs/" + graphId).with(as(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].name").value("별빛 사서 하린"));
        var question = write("/api/v1/ai/questions", reader, null, UUID.randomUUID(),
                Map.of("bookId", book, "question", "사서는 누구인가요?"), 202);
        await(reader, text(question, "jobId"));
        assertThat(upstream.get()).contains("별빛 사서 하린").doesNotContain("숨겨진 도서관장");
        assertThat(db.queryForObject("SELECT balance FROM credit_wallet WHERE member_id=?", Integer.class, id(reader))).isEqualTo(5);
    }

    private UUID member(String role) {
        UUID member = UUID.randomUUID();
        String name = member.toString().substring(0, 12);
        var now = at(Instant.now());
        db.update("""
                INSERT INTO member(member_id,nickname,nickname_key,phone_number,role,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, id(member), name, name, "+821012345678", role, now, now);
        return member;
    }

    private MvcResult write(String path, UUID actor, String tag, UUID key, Object body, int statusCode) throws Exception {
        var request = post(path).with(as(actor));
        if (tag != null) request.header("If-Match", tag);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(statusCode)).andReturn();
    }

    private MvcResult await(UUID reader, String id) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var result = mvc.perform(get("/api/v1/ai/jobs/" + id).with(as(reader)))
                    .andExpect(status().isOk()).andReturn();
            String status = text(result, "status");
            if ("SUCCEEDED".equals(status)) return result;
            assertThat(status).isIn("QUEUED", "RUNNING");
            Thread.sleep(50);
        }
        throw new AssertionError("AI job did not complete");
    }

    private String text(MvcResult result, String key) {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path(key).asString();
    }

    private static String tag(MvcResult result) { return result.getResponse().getHeader("ETag"); }
    private static Map<String, Object> pos(int value) { return Map.of("unit", "EPISODE", "value", value); }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(UUID member) {
        return jwt().jwt(token -> token.subject(member.toString()).claim("sid", UUID.randomUUID().toString()));
    }
}
