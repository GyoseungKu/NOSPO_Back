package kr.nospo.identity.integration;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.*;
import kr.nospo.support.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@ExtendWith(OutputCaptureExtension.class)
class KakaoHttpClientTests {
    HttpServer server;
    ExecutorService executor;
    KakaoHttpClient client;
    String base;
    final AtomicReference<String> tokenBody = new AtomicReference<>();
    final AtomicReference<String> userBody = new AtomicReference<>();
    final AtomicInteger tokenStatus = new AtomicInteger(200);
    final AtomicInteger userStatus = new AtomicInteger(200);
    final AtomicInteger userCalls = new AtomicInteger();
    final AtomicReference<String> posted = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> contentType = new AtomicReference<>();
    final AtomicReference<String> method = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        tokenBody.set("{\"access_token\":\"test-provider-token\"}");
        userBody.set("{\"id\":12345,\"kakao_account\":{\"email\":\"reader@example.com\","
                + "\"is_email_valid\":true,\"is_email_verified\":true,\"profile\":{\"nickname\":\"reader\"}}}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/token", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            method.set(exchange.getRequestMethod());
            if (tokenStatus.get() == 302) exchange.getResponseHeaders().add("Location", base + "/leak");
            reply(exchange, tokenStatus.get(), tokenBody.get());
        });
        server.createContext("/user", exchange -> {
            userCalls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, userStatus.get(), userBody.get());
        });
        server.createContext("/leak", exchange -> {
            userCalls.incrementAndGet(); reply(exchange, 200, "{}");
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new KakaoHttpClient("test-client-id", "test-client-secret", JsonMapper.builder().build(),
                URI.create(base + "/token"), URI.create(base + "/user"));
    }
    @AfterEach
    void close() { server.stop(0); executor.shutdownNow(); }
    void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
    Map<String, String> form(String input) {
        Map<String, String> result = new HashMap<>();
        for (String entry : input.split("&")) {
            String[] parts = entry.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    void failure() {
        assertThatThrownBy(() -> client.identify("test-authorization-code", "https://api.example/callback"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("KAKAO_AUTH_FAILED");
                    assertThat(ex.getCause()).isNull();
                    assertThat(ex.getMessage()).doesNotContain("test-client-secret", "test-provider-token", "test-authorization-code");
                });
    }

    @Test
    void sendsCorrectFormAndBearerWithoutLoggingSecrets(CapturedOutput output) {
        var user = client.identify("test-authorization-code+&", "https://api.example/api/v1/auth/kakao/callback");
        assertThat(user.subject()).isEqualTo("12345");
        assertThat(user.emailVerified()).isTrue();
        assertThat(user.nickname()).isEqualTo("reader");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
        assertThat(form(posted.get())).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_id", "test-client-id").containsEntry("client_secret", "test-client-secret")
                .containsEntry("code", "test-authorization-code+&")
                .containsEntry("redirect_uri", "https://api.example/api/v1/auth/kakao/callback");
        assertThat(authorization.get()).isEqualTo("Bearer test-provider-token");
        assertThat(output.getAll()).doesNotContain("test-client-secret", "test-provider-token", "test-authorization-code");
    }
    @Test
    void authorizationUrlContainsStateAndFixedRedirectButNoSecret() {
        String url = client.authorizationUrl("test-state", "https://api.example/api/v1/auth/kakao/callback");
        assertThat(url).startsWith("https://kauth.kakao.com/oauth/authorize?").doesNotContain("test-client-secret");
        assertThat(form(URI.create(url).getRawQuery())).containsEntry("response_type", "code")
                .containsEntry("scope", "account_email")
                .containsEntry("state", "test-state").containsEntry("client_id", "test-client-id")
                .containsEntry("redirect_uri", "https://api.example/api/v1/auth/kakao/callback");
    }
    @Test
    void reauthenticationExplicitlyPromptsLoginWithoutExposingSecret() {
        String url = client.reauthenticationUrl("state", "https://api.example/callback");
        assertThat(form(URI.create(url).getRawQuery())).containsEntry("prompt", "login").containsEntry("state", "state")
                .containsEntry("redirect_uri", "https://api.example/callback").doesNotContainKey("scope");
        assertThat(url).doesNotContain("test-client-secret");
    }
    @Test
    void tokenErrorsAreSanitizedAndUserApiIsNotCalled(CapturedOutput output) {
        tokenStatus.set(400); tokenBody.set("test-client-secret test-provider-token test-authorization-code");
        failure(); assertThat(userCalls.get()).isZero();
        assertThat(output.getAll()).doesNotContain("test-client-secret", "test-provider-token", "test-authorization-code");
    }
    @Test
    void userErrorsAndMalformedJsonAreSanitized(CapturedOutput output) {
        userStatus.set(500); userBody.set("test-client-secret test-provider-token"); failure();
        userStatus.set(200); userBody.set("not-json test-provider-token"); failure();
        assertThat(output.getAll()).doesNotContain("test-client-secret", "test-provider-token");
    }
    @Test
    void redirectsAreNotFollowedAndOversizedResponsesAreRejected() {
        tokenStatus.set(302); failure(); assertThat(userCalls.get()).isZero();
        tokenStatus.set(200); tokenBody.set("x".repeat(65537)); failure();
        assertThat(userCalls.get()).isZero();
    }
    @Test
    void rejectsMissingTokenAndInvalidProviderId() {
        tokenBody.set("{}"); failure(); assertThat(userCalls.get()).isZero();
        tokenBody.set("{\"access_token\":\"test-provider-token\"}");
        for (String id : List.of("null", "0", "-1", "1.2", "\"12345\"")) {
            userBody.set("{\"id\":" + id + "}"); failure();
        }
    }
    @Test
    void requiresBothEmailValidityFlagsAndDoesNotTrustStringTrue() {
        userBody.set("{\"id\":12345,\"kakao_account\":{\"email\":\"reader@example.com\","
                + "\"is_email_valid\":true,\"is_email_verified\":\"true\"}}");
        assertThat(client.identify("code", "https://api.example/callback").emailVerified()).isFalse();
        userBody.set("{\"id\":12345}");
        var user = client.identify("code", "https://api.example/callback");
        assertThat(user.email()).isNull(); assertThat(user.emailVerified()).isFalse();
    }
    @Test
    void missingEmailConsentFallsBackAndNewConsentResponseCanProvideVerifiedEmail(CapturedOutput output) {
        userBody.set("{\"id\":12345,\"kakao_account\":{\"email_needs_agreement\":true}}");
        var before = client.identify("first-test-code", "https://api.example/callback");
        assertThat(before.email()).isNull();
        assertThat(before.emailVerified()).isFalse();
        userBody.set("{\"id\":12345,\"kakao_account\":{\"email_needs_agreement\":false,"
                + "\"email\":\"reader@example.com\",\"is_email_valid\":true,\"is_email_verified\":true}}");
        var after = client.identify("new-test-code", "https://api.example/callback");
        assertThat(after.email()).isEqualTo("reader@example.com");
        assertThat(after.emailVerified()).isTrue();
        assertThat(output.getAll()).doesNotContain("reader@example.com", "test-provider-token", "first-test-code", "new-test-code");
    }
    @Test
    void consentDoesNotReplaceStrictEmailValidityAndVerification() {
        for (String valid : List.of("true", "false", "null", "\"true\"")) {
            for (String verified : List.of("true", "false", "null", "\"true\"")) {
                userBody.set("{\"id\":12345,\"kakao_account\":{\"email_needs_agreement\":false,"
                        + "\"email\":\"reader@example.com\",\"is_email_valid\":" + valid
                        + ",\"is_email_verified\":" + verified + "}}");
                assertThat(client.identify("test-code", "https://api.example/callback").emailVerified())
                        .isEqualTo(valid.equals("true") && verified.equals("true"));
            }
        }
    }
    @Test
    void missingConfigurationDoesNotBreakOtherAuthAndNeverPrintsSecrets() {
        var disabled = new KakaoHttpClient("", "", JsonMapper.builder().build());
        assertThatThrownBy(() -> disabled.authorizationUrl("state", "https://api.example/callback"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("KAKAO_NOT_CONFIGURED"));
    }
}
