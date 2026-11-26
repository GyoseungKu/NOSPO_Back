package kr.nospo.ai.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import kr.nospo.credit.contract.AiCredits;
import kr.nospo.member.contract.MemberAccess;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static kr.nospo.ai.application.AiBookContext.*;
import static kr.nospo.ai.application.AiGraphStore.Graph;
import static kr.nospo.ai.application.AiQuestions.*;

@Service
public class AiGraphs {
    private final AiGraphStore store;
    private final AiBookContext books;
    private final AiQuestions scope;
    private final MemberAccess members;
    private final AiCredits credits;
    private final ObjectMapper json;

    public AiGraphs(AiGraphStore store, AiBookContext books, AiQuestions scope,
                    MemberAccess members, AiCredits credits, ObjectMapper json) {
        this.store = store;
        this.books = books;
        this.scope = scope;
        this.members = members;
        this.credits = credits;
        this.json = json;
    }

    public record Summary(UUID graphId, UUID bookId, Position generatedPosition, int wikiVersion, Instant createdAt,
                          Position currentPosition, boolean positionMatches, boolean regenerationRequired) {}
    public record Node(UUID id, String type, String name, String imageUrl) {}
    public record Edge(UUID id, UUID from, UUID to, String type, String description) {}
    public record Citation(UUID relationId, Position sourcePosition, int wikiVersion) {}
    public record Content(List<Node> nodes, List<Edge> edges, List<Citation> citations) {}
    public record Result(Summary summary, Content content) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Graph submit(UUID member, UUID key, UUID bookId, Position requested, UUID source) {
        if (bookId == null) throw new ApiException(422, "VALIDATION_ERROR");
        var owner = members.lockState(member).filter(MemberAccess.State::active)
                .orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS"));
        Instant now = scope.now();
        Integer requestedValue = requested == null ? null : requested.value();
        var previous = store.request(member, key);
        if (previous.isPresent()) {
            Graph graph = previous.get();
            if (graph.created().isAfter(now.minusSeconds(86400))) {
                if (!graph.book().equals(bookId) || !Objects.equals(graph.source(), source)
                        || !Objects.equals(graph.requestedValue(), requestedValue)
                        || (requested != null && graph.position().unit() != requested.unit()))
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                checkVisible(member, graph);
                return graph;
            }
            store.clearKey(graph.id());
        }
        if (!books.ready()) throw new ApiException(503, "AI_UNAVAILABLE");
        Book book = books.book(bookId).orElseThrow(AiGraphs::missing);
        Position current = scope.current(member, bookId);
        Position selected = requested == null ? current : requested;
        if (current.unit() != book.unit() || selected.unit() != book.unit() || selected.value() < 0
                || (book.totalUnits() != null && selected.value() > book.totalUnits()))
            throw new ApiException(422, "INVALID_POSITION");
        if (selected.value() > current.value()) throw new ApiException(422, "POSITION_EXCEEDS_CURRENT");
        if (source != null) {
            Graph original = successful(member, source);
            if (!original.book().equals(bookId)) throw missing();
            checkVisible(member, original);
        }
        Snapshot snapshot = books.visibleSnapshot(bookId, selected)
                .orElseThrow(() -> new ApiException(409, "WIKI_NOT_AVAILABLE"));
        String payload = json.writeValueAsString(snapshot);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 60_000)
            throw new ApiException(503, "AI_UNAVAILABLE");

        credits.reserve(member);
        var graph = new Graph(UUID.randomUUID(), UUID.randomUUID(), member, bookId, source, requestedValue,
                selected, snapshot.version(), owner.withdrawalGeneration(), "QUEUED", payload, null,
                now, null, now.plusSeconds(180));
        store.insert(graph, key);
        return graph;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void process(UUID job) {
        var initial = store.find(job, true).orElse(null);
        if (initial == null) return;
        var owner = members.lockState(initial.member()).orElse(null);
        var graph = store.find(job, true).orElse(null);
        if (owner == null || graph == null || !"QUEUED".equals(graph.status())) return;

        String error = null;
        String payload = null;
        if (!owner.active() || owner.withdrawalGeneration() != graph.generation()) {
            error = "ACCOUNT_INACTIVE";
        } else if (graph.deadline().isAfter(scope.now())) {
            try {
                payload = json.writeValueAsString(content(json.readValue(graph.payload(), Snapshot.class), graph));
                if (payload.getBytes(StandardCharsets.UTF_8).length > 60_000)
                    throw new IllegalStateException("Graph exceeds the output limit");
            } catch (RuntimeException failure) {
                error = "AI_GENERATION_FAILED";
            }
        }
        Instant now = scope.now();
        if (error == null && !graph.deadline().isAfter(now)) error = "JOB_TIMEOUT";
        credits.settle(graph.member(), graph.job(), error == null, "AI_GRAPH");
        store.finish(graph.id(), now, error == null ? payload : null, error);
    }

    private static Content content(Snapshot snapshot, Graph graph) {
        var nodes = snapshot.entities().stream()
                .map(entity -> new Node(entity.id(), entity.type(), entity.name(), entity.imageUrl())).toList();
        Set<UUID> ids = nodes.stream().map(Node::id).collect(Collectors.toSet());
        var relations = snapshot.relations().stream()
                .filter(relation -> ids.contains(relation.from()) && ids.contains(relation.to()))
                .filter(relation -> relation.sourcePosition() != null && inScope(relation.sourcePosition(), graph.position()))
                .toList();
        return new Content(nodes, relations.stream()
                .map(relation -> new Edge(relation.id(), relation.from(), relation.to(), relation.type(), relation.description()))
                .toList(), relations.stream().map(relation ->
                        new Citation(relation.id(), relation.sourcePosition(), graph.version())).toList());
    }

    public Optional<Graph> job(UUID member, UUID id) {
        return store.find(id, true).filter(graph -> member.equals(graph.member())).map(graph -> {
            checkVisible(member, graph);
            return graph;
        });
    }

    public Result result(UUID member, UUID id) {
        Graph graph = successful(member, id);
        Position current = scope.current(member, graph.book());
        if (!inScope(graph.position(), current)) throw new ApiException(403, "SPOILER_SCOPE_EXCEEDED");
        return new Result(summary(graph, current), json.readValue(graph.payload(), Content.class));
    }

    public Page<Summary> list(UUID member, UUID book, String cursor, int limit) {
        validLimit(limit);
        var after = decode(cursor);
        var items = new ArrayList<Summary>();
        while (books.ready()) {
            var batch = store.list(member, book, after);
            for (Graph graph : batch) {
                var current = books.currentPosition(member, graph.book())
                        .filter(position -> inScope(graph.position(), position));
                if (current.isPresent() && items.size() == limit) {
                    Summary last = items.getLast();
                    return new Page<>(items, encode(last.createdAt(), last.graphId()), true);
                }
                current.ifPresent(position -> items.add(summary(graph, position)));
                after = new AiQuestionStore.Cursor(graph.created(), graph.id());
            }
            if (batch.size() < 50) break;
        }
        return new Page<>(items, null, false);
    }

    private Summary summary(Graph graph, Position current) {
        boolean matches = graph.position().equals(current);
        boolean currentVersion = matches && books.visibleSnapshot(graph.book(), current)
                .filter(snapshot -> snapshot.version() == graph.version()).isPresent();
        return new Summary(graph.id(), graph.book(), graph.position(), graph.version(), graph.created(),
                current, matches, !currentVersion);
    }

    private Graph successful(UUID member, UUID id) {
        return store.find(id, false).filter(graph -> member.equals(graph.member()) && "SUCCEEDED".equals(graph.status()))
                .orElseThrow(AiGraphs::missing);
    }

    private void checkVisible(UUID member, Graph graph) {
        if ("QUEUED".equals(graph.status()) && members.state(member)
                .filter(state -> state.active() && state.withdrawalGeneration() == graph.generation()).isEmpty())
            throw missing();
        scope.requireVisible(member, graph.book(), graph.position());
    }

    private static ApiException missing() {
        return new ApiException(404, "RESOURCE_NOT_FOUND");
    }
}
