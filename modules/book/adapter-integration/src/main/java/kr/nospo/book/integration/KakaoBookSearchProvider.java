package kr.nospo.book.integration;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.nospo.book.application.BookSearchCache;
import kr.nospo.book.application.BookSearchProvider;
import kr.nospo.book.application.EditionMetadata;
import kr.nospo.book.contract.SearchType;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
public class KakaoBookSearchProvider implements BookSearchProvider {
    private static final URI ENDPOINT = URI.create("https://dapi.kakao.com/v3/search/book");
    private static final int MAX_PAGE = 50;
    private static final int MAX_SIZE = 50;
    private static final int MAX_CALLS = 2;
    private static final int MAX_BODY = 1024 * 1024;
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(KakaoBookSearchProvider.class);

    private final String key;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final int connectTimeout;
    private final int readTimeout;
    private final BookSearchCache<String, Position> continuations;

    @Autowired
    public KakaoBookSearchProvider(@Value("${nospo.auth.kakao.client-id:}") String key,
            ObjectMapper mapper, Clock clock) {
        this(key, mapper, clock, ENDPOINT, 2000, 3000);
    }

    // Local HTTP fixtures use a package-private constructor. Production always uses the fixed HTTPS endpoint.
    KakaoBookSearchProvider(String key, ObjectMapper mapper, Clock clock, URI endpoint,
            int connectTimeout, int readTimeout) {
        this.key = key.strip();
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        continuations = new BookSearchCache<>(clock, Duration.ofMinutes(5), 128);
    }

    @Override
    public String source() {
        return "KAKAO_DAUM";
    }

    @Override
    public boolean transientResults() {
        return true;
    }

    @Scheduled(fixedDelay = 60_000)
    void pruneContinuations() {
        continuations.prune();
    }

    @Override
    public Results search(String query, SearchType type, String continuation, int limit) {
        if (key.isEmpty()) throw new Unavailable();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid search limit");
        String target = switch (type) {
            case TITLE -> "title";
            case AUTHOR -> "person";
            case ISBN -> "isbn";
        };
        var state = continuation == null
                ? new Position(query, type, Math.min(limit, MAX_SIZE), 1, Set.of(), List.of())
                : continuations.get(continuation);
        if (state == null || !state.query().equals(query) || state.type() != type)
            throw new InvalidContinuation();

        var seen = new HashSet<>(state.seen());
        var items = new ArrayList<EditionMetadata>();
        var pending = state.pending();
        int page = state.nextPage();
        int calls = 0;
        while (true) {
            int consumed = 0;
            while (consumed < pending.size() && items.size() < limit) {
                var book = pending.get(consumed++);
                if (seen.add(book.isbn())) items.add(book);
            }
            pending = pending.subList(consumed, pending.size());
            if (items.size() == limit || page > MAX_PAGE || calls == MAX_CALLS) break;

            var response = request(query, target, page, state.pageSize());
            calls++;
            page = response.lastPage() ? MAX_PAGE + 1 : page + 1;
            pending = response.books();
        }
        if (page > MAX_PAGE && pending.isEmpty()) return new Results(items, null);
        String next = UUID.randomUUID().toString();
        continuations.put(next, new Position(query, type, state.pageSize(), page, Set.copyOf(seen), List.copyOf(pending)));
        return new Results(items, next);
    }

    private KakaoBookResponse request(String query, String target, int page, int size) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(endpoint + "?query=" + URLEncoder.encode(query, UTF_8)
                    + "&target=" + target + "&sort=accuracy&page=" + page + "&size=" + size);
            connection = open(uri);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Authorization", "KakaoAK " + key);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != 200) {
                LOG.warn("Kakao book search HTTP failure status={}", status);
                throw new Unavailable();
            }
            try (var stream = connection.getInputStream()) {
                byte[] body = stream.readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) throw new IllegalArgumentException("Book search body too large");
                var document = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
                return KakaoBookResponse.parse(document, size);
            }
        } catch (SocketTimeoutException timeout) {
            LOG.warn("Kakao book search timed out");
            throw new Unavailable();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof Unavailable unavailable) throw unavailable;
            LOG.warn("Kakao book search transport or response failure type={}", failure.getClass().getSimpleName());
            throw new Unavailable();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    HttpURLConnection open(URI uri) throws IOException {
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    private record Position(String query, SearchType type, int pageSize, int nextPage,
            Set<String> seen, List<EditionMetadata> pending) {}
}
