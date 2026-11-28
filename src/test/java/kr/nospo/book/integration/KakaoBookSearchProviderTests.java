package kr.nospo.book.integration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.nospo.book.application.BookSearchProvider;
import kr.nospo.book.contract.SearchType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class KakaoBookSearchProviderTests {
    private static final String FIXTURE = "local-fixture";
    private static final String ISBN = "9780306406157";
    private HttpServer localhost;
    private URI endpoint;
    private final ConcurrentLinkedQueue<Request> calls = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Function<Request, Reply>> replies = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void server() throws IOException {
        localhost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localhost.createContext("/v3/search/book", this::respond);
        localhost.start();
        endpoint = URI.create("http://127.0.0.1:" + localhost.getAddress().getPort() + "/v3/search/book");
    }

    @AfterEach
    void stop() {
        localhost.stop(0);
    }

    @Test
    void sharedSpringSettingSuppliesOnlyTheRestApiKeyToBookSearch() throws Exception {
        replies.set(request -> page(true, row(ISBN)));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fakeKakao", Map.of(
                    "KAKAO_CLIENT_ID", FIXTURE, "KAKAO_CLIENT_SECRET", "oauth-only-fixture")));
            var properties = new PropertySourcesPlaceholderConfigurer();
            properties.setEnvironment(context.getEnvironment());
            properties.setLocation(new ClassPathResource("application.properties"));
            context.addBeanFactoryPostProcessor(properties);
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(KakaoBookSearchProvider.class);
            context.refresh();

            var provider = org.mockito.Mockito.spy(context.getBean(KakaoBookSearchProvider.class));
            org.mockito.Mockito.doAnswer(invocation -> {
                URI uri = invocation.getArgument(0);
                assertThat(uri.getScheme()).isEqualTo("https");
                assertThat(uri.getHost()).isEqualTo("dapi.kakao.com");
                return (HttpURLConnection) URI.create(endpoint + "?" + uri.getRawQuery()).toURL().openConnection();
            }).when(provider).open(org.mockito.ArgumentMatchers.any(URI.class));

            assertThat(provider.search("fixture", SearchType.TITLE, null, 1).items()).hasSize(1);
            assertThat(calls).singleElement().satisfies(request -> {
                assertThat(request.authorization()).isEqualTo("KakaoAK " + FIXTURE);
                assertThat(request.params()).containsOnlyKeys("query", "target", "sort", "page", "size");
            });
        }
    }

    @Test
    void requestMapsAllTargetsAndResponseMapsBibliography() {
        replies.set(request -> page(true, row("0-306-40615-2 9780306406157")));
        var provider = provider(FIXTURE, 1000);
        for (SearchType type : SearchType.values()) {
            var result = provider.search("한국 + A&B", type, null, 20);
            var edition = result.items().getFirst();
            assertThat(edition.isbn()).isEqualTo(ISBN);
            assertThat(edition.sourceItemId()).isEqualTo(ISBN);
            assertThat(edition.title()).isEqualTo("Fixture title");
            assertThat(edition.authors()).containsExactly("Writer");
            assertThat(edition.description()).isEqualTo("Summary");
            assertThat(edition.publisher()).isEqualTo("Press");
            assertThat(edition.coverUrl()).isEqualTo("https://covers.example.test/book.jpg");
            assertThat(edition.publicationDate()).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThat(edition.totalUnits()).isNull();
            assertThat(edition.editionLabel()).isNull();
            assertThat(result.nextContinuation()).isNull();
        }
        assertThat(calls).extracting(request -> request.params().get("target")).containsExactly("title", "person", "isbn");
        assertThat(calls).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.authorization()).isEqualTo("KakaoAK " + FIXTURE);
            assertThat(request.params()).containsEntry("query", "한국 + A&B").containsEntry("page", "1")
                    .containsEntry("size", "20").containsEntry("sort", "accuracy");
        });
    }

    @Test
    void verifiesCheckDigitsAliasesAndConflictingIsbns() {
        assertThat(KakaoIsbn.canonical("0306406152")).isEqualTo(ISBN);
        assertThat(KakaoIsbn.canonical("9780306406157 0306406152")).isEqualTo(ISBN);
        assertThat(KakaoIsbn.canonical("0-8044-2957-x")).isEqualTo("9780804429573");
        assertThat(KakaoIsbn.canonical("9790000000001")).isEqualTo("9790000000001");
        assertThat(KakaoIsbn.canonical("9780306406158 0306406153")).isNull();
        assertThat(KakaoIsbn.canonical(ISBN + " 9780140328721")).isNull();
        assertThat(KakaoIsbn.canonical("")).isNull();
    }

    @Test
    void paginationHandlesFiftyAndHundredLimitChangesAndCrossPageDuplicates() {
        replies.set(request -> {
            int page = Integer.parseInt(request.params().get("page"));
            int start = page == 1 ? 0 : (page - 1) * 49;
            return page(page == 3, IntStream.range(start, start + 50).mapToObj(i -> row(isbn(i))).toArray(String[]::new));
        });
        var provider = provider(FIXTURE, 1000);
        var first = provider.search("q", SearchType.TITLE, null, 50);
        var second = provider.search("q", SearchType.TITLE, first.nextContinuation(), 100);
        assertThat(first.items()).hasSize(50);
        assertThat(second.items()).hasSize(98);
        assertThat(second.nextContinuation()).isNull();
        var keys = new ArrayList<String>();
        first.items().forEach(b -> keys.add(b.isbn()));
        second.items().forEach(b -> keys.add(b.isbn()));
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(calls).extracting(request -> request.params().get("page")).containsExactly("1", "2", "3");
        assertThat(calls).allSatisfy(request -> assertThat(request.params()).containsEntry("size", "50"));

        calls.clear();
        var hundred = provider.search("q", SearchType.TITLE, null, 100);
        assertThat(hundred.items()).hasSize(99);
        assertThat(calls).hasSize(2);
        assertThatThrownBy(() -> provider.search("other", SearchType.TITLE, hundred.nextContinuation(), 1))
                .isInstanceOf(BookSearchProvider.InvalidContinuation.class);
        var small = provider.search("q", SearchType.TITLE, hundred.nextContinuation(), 1);
        assertThat(small.items().getFirst().isbn()).isEqualTo(isbn(99));
        var rest = provider.search("q", SearchType.TITLE, small.nextContinuation(), 100);
        assertThat(rest.items()).hasSize(48);
        assertThat(calls).hasSize(3);
    }

    @Test
    void stopsAtFiftyPagesEvenWhenUpstreamKeepsAdvertisingNext() {
        replies.set(request -> page(false, row(isbn(Integer.parseInt(request.params().get("page"))))));
        var provider = provider(FIXTURE, 1000);
        String cursor = null;
        for (int i = 1; i <= 50; i++) {
            var result = provider.search("q", SearchType.TITLE, cursor, 1);
            assertThat(result.items().getFirst().isbn()).isEqualTo(isbn(i));
            cursor = result.nextContinuation();
        }
        assertThat(cursor).isNull();
        assertThat(calls).hasSize(50);
        assertThat(new ArrayList<>(calls).getLast().params()).containsEntry("page", "50").containsEntry("size", "1");
    }

    @Test
    void filtersUnidentifiedRowsAndContinuesAfterAnEmptyBoundedBatch() {
        replies.set(request -> switch (request.params().get("page")) {
            case "1", "2" -> page(false, row(""), row("9780306406158"));
            default -> page(true, row(ISBN));
        });
        var provider = provider(FIXTURE, 1000);
        var first = provider.search("q", SearchType.TITLE, null, 20);
        assertThat(first.items()).isEmpty();
        assertThat(first.nextContinuation()).isNotNull();
        assertThat(calls).hasSize(2);
        assertThat(provider.search("q", SearchType.TITLE, first.nextContinuation(), 20).items()).hasSize(1);
        replies.set(request -> page(true));
        var empty = provider.search("empty", SearchType.TITLE, null, 20);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextContinuation()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503, 302})
    void mapsStatusAndDoesNotFollowRedirectOrRetry(int status) {
        replies.set(request -> new Reply(status, "ignored", 0));
        assertThatThrownBy(() -> provider(FIXTURE, 1000).search("q", SearchType.TITLE, null, 20))
                .isInstanceOf(BookSearchProvider.Unavailable.class).hasNoCause().hasMessageNotContaining(FIXTURE);
        assertThat(calls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "[]", "null", "{\"meta\":{\"total_count\":0,\"pageable_count\":0,\"is_end\":true},\"documents\":[]} {}"})
    void invalidPayloadIsUnavailable(String json) {
        replies.set(request -> new Reply(200, json, 0));
        assertThatThrownBy(() -> provider(FIXTURE, 1000).search("q", SearchType.TITLE, null, 20))
                .isInstanceOf(BookSearchProvider.Unavailable.class).hasNoCause();
    }

    @Test
    void boundsBodyAndTimesOutOnAnUnresponsiveConnection() {
        replies.set(request -> new Reply(200, " ".repeat(1024 * 1024 + 1), 0));
        assertThatThrownBy(() -> provider(FIXTURE, 1000).search("q", SearchType.TITLE, null, 20))
                .isInstanceOf(BookSearchProvider.Unavailable.class);
        replies.set(request -> new Reply(200, "{}", 300));
        assertThatThrownBy(() -> provider(FIXTURE, 50).search("q", SearchType.TITLE, null, 20))
                .isInstanceOf(BookSearchProvider.Unavailable.class);
    }

    @Test
    void missingKeyDoesNotOpenHttpConnection() {
        assertThatThrownBy(() -> provider("", 1000).search("q", SearchType.ISBN, null, 1))
                .isInstanceOf(BookSearchProvider.Unavailable.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void connectTimeoutUsesConfiguredBoundAndDropsDiagnosticCause() {
        var provider = new KakaoBookSearchProvider(FIXTURE, mapper, Clock.systemUTC(), endpoint, 27, 46) {
            @Override
            HttpURLConnection open(URI uri) throws IOException {
                return new HttpURLConnection(uri.toURL()) {
                    @Override public void disconnect() {}
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() throws SocketTimeoutException {
                        assertThat(getConnectTimeout()).isEqualTo(27);
                        assertThat(getReadTimeout()).isEqualTo(46);
                        throw new SocketTimeoutException("fixture diagnostic");
                    }
                    @Override public int getResponseCode() throws IOException {
                        connect();
                        return HTTP_OK;
                    }
                };
            }
        };
        assertThatThrownBy(() -> provider.search("q", SearchType.TITLE, null, 20))
                .isInstanceOf(BookSearchProvider.Unavailable.class).hasNoCause()
                .hasMessageNotContaining("fixture diagnostic");
        assertThat(calls).isEmpty();
    }

    @Test
    void expiredContinuationIsRejectedWithoutAnAdditionalRequest() {
        var now = new AtomicReference<>(Instant.parse("2026-11-26T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { throw new UnsupportedOperationException(); }
            @Override public Instant instant() { return now.get(); }
        };
        replies.set(request -> page(false, row(ISBN)));
        var provider = new KakaoBookSearchProvider(FIXTURE, mapper, clock, endpoint, 1000, 1000);
        var first = provider.search("q", SearchType.TITLE, null, 1);
        now.updateAndGet(time -> time.plusSeconds(300));
        assertThatThrownBy(() -> provider.search("q", SearchType.TITLE, first.nextContinuation(), 1))
                .isInstanceOf(BookSearchProvider.InvalidContinuation.class);
        assertThat(calls).hasSize(1);
    }

    private KakaoBookSearchProvider provider(String key, int timeout) {
        return new KakaoBookSearchProvider(key, mapper, Clock.systemUTC(), endpoint, timeout, timeout);
    }

    private void respond(HttpExchange exchange) throws IOException {
        var params = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], UTF_8)));
        var request = new Request(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"), params);
        calls.add(request);
        var reply = replies.get().apply(request);
        try (exchange) {
            if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (reply.status() == 302) exchange.getResponseHeaders().set("Location", endpoint.toString());
            byte[] body = reply.body().getBytes(UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Reply page(boolean end, String... rows) {
        return new Reply(200, """
                {"meta":{"total_count":%d,"pageable_count":%d,"is_end":%s},"documents":[%s]}
                """.formatted(rows.length, rows.length, end, String.join(",", rows)), 0);
    }

    private static String row(String isbn) {
        return """
                {"isbn":"%s","title":"Fixture title","authors":["Writer"],"publisher":"Press","contents":"Summary",
                 "thumbnail":"https://covers.example.test/book.jpg","datetime":"2024-03-05T01:00:00.000+09:00",
                 "translators":["Translator"],"url":"https://example.test/book","status":"정상판매"}
                """.formatted(isbn);
    }

    private static String isbn(int index) {
        String prefix = "97800000%04d".formatted(index);
        int sum = IntStream.range(0, 12).map(i -> (prefix.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3)).sum();
        return prefix + ((10 - sum % 10) % 10);
    }

    private record Request(String method, String authorization, Map<String, String> params) {}
    private record Reply(int status, String body, int delayMillis) {}
}
