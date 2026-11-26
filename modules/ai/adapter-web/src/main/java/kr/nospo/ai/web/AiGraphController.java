package kr.nospo.ai.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.nospo.ai.application.AiBookContext.Position;
import kr.nospo.ai.application.AiGraphs;
import kr.nospo.ai.application.AiQuestions.Page;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI 도서 관계도")
@SecurityRequirement(name = "bearerAuth")
class AiGraphController {
    private final AiGraphs graphs;

    AiGraphController(AiGraphs graphs) {
        this.graphs = graphs;
    }

    record Input(UUID bookId, AiQuestionController.RequestedPosition position, UUID regenerateFromGraphId) {}
    record Accepted(UUID jobId, Position appliedPosition, int wikiVersion, int cost, String status) {}
    record Result(UUID graphId, UUID bookId, Position generatedPosition, int wikiVersion, Instant createdAt,
                  Position currentPosition, boolean positionMatches, boolean regenerationRequired,
                  List<AiGraphs.Node> nodes, List<AiGraphs.Edge> edges, List<AiGraphs.Citation> citations) {}

    @PostMapping("/ai/graphs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Accepted> submit(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Input input) {
        var graph = graphs.submit(AiQuestionController.member(jwt), key, input.bookId(),
                input.position() == null ? null : input.position().parse(), input.regenerateFromGraphId());
        return ApiResponse.of(new Accepted(graph.job(), graph.position(), graph.version(), 1, graph.status()));
    }

    @GetMapping("/ai/graphs/{graphId}")
    public ApiResponse<Result> result(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID graphId) {
        var result = graphs.result(AiQuestionController.member(jwt), graphId);
        var summary = result.summary();
        var content = result.content();
        return ApiResponse.of(new Result(summary.graphId(), summary.bookId(), summary.generatedPosition(),
                summary.wikiVersion(), summary.createdAt(), summary.currentPosition(), summary.positionMatches(),
                summary.regenerationRequired(), content.nodes(), content.edges(), content.citations()));
    }

    @GetMapping("/me/ai/graphs")
    public ApiResponse<Page<AiGraphs.Summary>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID bookId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(graphs.list(AiQuestionController.member(jwt), bookId, cursor, limit));
    }
}
