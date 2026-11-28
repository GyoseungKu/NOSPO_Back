package kr.nospo.book.integration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import kr.nospo.book.application.BookSearchProvider;
import tools.jackson.databind.ObjectMapper;

public final class KakaoLoopback implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    public KakaoLoopback(String json) throws IOException {
        byte[] response = json.getBytes(UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/search/book", exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())
                        || !"KakaoAK shelf-fixture".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || !exchange.getRequestURI().getRawQuery().contains("target=title")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
    }

    public BookSearchProvider provider(ObjectMapper mapper, Clock clock) {
        var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/search/book");
        return new KakaoBookSearchProvider("shelf-fixture", mapper, clock, endpoint, 2000, 2000);
    }

    public void assertSingleSearch() {
        assertThat(requests.get()).isOne();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
