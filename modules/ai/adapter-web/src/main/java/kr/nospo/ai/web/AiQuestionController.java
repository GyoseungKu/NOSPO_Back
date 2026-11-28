package kr.nospo.ai.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.nospo.ai.application.AiQuestions;
import kr.nospo.ai.application.AiGraphs;
import kr.nospo.support.ApiException;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static kr.nospo.ai.application.AiBookContext.*;
import static kr.nospo.ai.application.AiQuestionStore.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI 도서 질문")
@SecurityRequirement(name = "bearerAuth")
public class AiQuestionController {
    private final AiQuestions questions;
    private final AiGraphs graphs;
    private final AiQuestionStreams streams;
    private final ObjectMapper json;

    AiQuestionController(AiQuestions questions, AiGraphs graphs, AiQuestionStreams streams, ObjectMapper json) {
        this.questions = questions;
        this.graphs = graphs;
        this.streams = streams;
        this.json = json;
    }

    public record RequestedPosition(String unit, @Schema(implementation = Integer.class) JsonNode value) {
        Position parse() {
            if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
                throw new ApiException(422, "INVALID_POSITION");
            try {
                return new Position(Unit.valueOf(unit), value.intValue());
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new ApiException(422, "INVALID_POSITION");
            }
        }
    }

    public record QuestionInput(UUID bookId, RequestedPosition position, String question, UUID conversationId) {}
    public record QuestionAccepted(UUID jobId, UUID conversationId, UUID questionId, Position appliedPosition,
                           int wikiVersion, int cost, String status, String eventsUrl) {}
    public record Failure(String code, String message) {}
    public record Job(UUID jobId, String type, String status, Instant createdAt, Instant completedAt,
                      Map<String, UUID> result, Failure error, int reservedCredits, int chargedCredits,
                      Position appliedPosition, int wikiVersion) {}
    public record QuestionResult(UUID questionId, UUID conversationId, UUID jobId, String status, String question,
                                 String answer, JsonNode citations, Position appliedPosition, int wikiVersion,
                                 int chargedCredits, Failure error) {}
    public record ConversationSummary(UUID conversationId, UUID bookId, String title, Instant createdAt,
                                      Instant updatedAt, Position maxContextPosition) {}
    public record MessageResult(UUID messageId, UUID questionId, String role, String content,
                                Position appliedPosition, Instant createdAt, JsonNode citations) {}

    @PostMapping("/ai/questions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "도서 질문 접수", description = "공개 위키 범위를 고정하고 1토큰을 예약합니다. Idempotency-Key에는 UUID를 보냅니다.")
    public ApiResponse<QuestionAccepted> submit(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody QuestionInput input) {
        var q = questions.submit(member(jwt), key, input.bookId(),
                input.position() == null ? null : input.position().parse(), input.question(), input.conversationId());
        return ApiResponse.of(new QuestionAccepted(q.job(), q.conversation(), q.id(), q.position(), q.version(), 1, q.status(),
                "/api/v1/ai/questions/" + q.id() + "/events"));
    }

    @GetMapping("/ai/jobs/{jobId}")
    @Operation(summary = "AI 작업 상태 조회", description = "본인 질문 또는 관계도의 처리 상태와 차감 여부를 조회합니다.")
    public ApiResponse<Job> job(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        var graph = graphs.job(member(jwt), jobId);
        if (graph.isPresent()) {
            var g = graph.get();
            boolean success = "SUCCEEDED".equals(g.status());
            return ApiResponse.of(new Job(g.job(), "GRAPH", g.status(), g.created(), g.completed(),
                    success ? Map.of("graphId", g.id()) : null,
                    g.error() == null ? null : new Failure(g.error(), "관계도를 완료하지 못했습니다."),
                    "QUEUED".equals(g.status()) ? 1 : 0, success ? 1 : 0, g.position(), g.version()));
        }
        var q = questions.visibleQuestion(member(jwt), jobId, true);
        int charged = charged(q);
        return ApiResponse.of(new Job(q.job(), "QUESTION", q.status(), q.created(), q.completed(),
                charged == 1 ? Map.of("questionId", q.id()) : null, failure(q), q.pending() ? 1 : 0,
                charged, q.position(), q.version()));
    }

    @GetMapping("/ai/questions/{questionId}")
    @Operation(summary = "도서 질문 결과 조회", description = "현재 읽은 범위 안에서 저장된 답변이나 실패 정보를 반환합니다.")
    public ApiResponse<QuestionResult> question(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID questionId) {
        var q = questions.visibleQuestion(member(jwt), questionId, false);
        return ApiResponse.of(new QuestionResult(q.id(), q.conversation(), q.job(), q.status(), q.text(), q.answer(),
                citations(q.citations()), q.position(), q.version(), charged(q), failure(q)));
    }

    @GetMapping(value = "/ai/questions/{questionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "도서 답변 스트리밍", description = "Bearer로 SSE에 연결합니다. 재연결할 때 Last-Event-ID를 보냅니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "질문 SSE 스트림",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "text/event-stream",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string")))
    public SseEmitter events(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID questionId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return streams.open(jwt, questionId, lastEventId);
    }

    @GetMapping("/me/ai/conversations")
    @Operation(summary = "내 도서 대화 목록", description = "현재 읽은 범위에서 접근 가능한 대화를 조회합니다.")
    public ApiResponse<AiQuestions.Page<ConversationSummary>> conversations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID bookId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        var page = questions.conversations(member(jwt), bookId, cursor, limit);
        return ApiResponse.of(new AiQuestions.Page<>(page.items().stream().map(AiQuestionController::summary).toList(),
                page.nextCursor(), page.hasNext()));
    }

    @GetMapping("/ai/conversations/{conversationId}")
    @Operation(summary = "도서 대화 상세", description = "본인 대화의 도서, 제목과 기준 지점을 반환합니다.")
    public ApiResponse<ConversationSummary> conversation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId) {
        return ApiResponse.of(summary(questions.visibleConversation(member(jwt), conversationId)));
    }

    @GetMapping("/ai/conversations/{conversationId}/messages")
    @Operation(summary = "도서 대화 메시지 이력", description = "본인 대화의 질문과 완료된 답변을 시간순으로 조회합니다.")
    public ApiResponse<AiQuestions.Page<MessageResult>> messages(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID conversationId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        var page = questions.messages(member(jwt), conversationId, cursor, limit);
        return ApiResponse.of(new AiQuestions.Page<>(page.items().stream().map(m -> new MessageResult(m.messageId(),
                m.questionId(), m.role(), m.content(), m.appliedPosition(), m.createdAt(), citations(m.citations()))).toList(),
                page.nextCursor(), page.hasNext()));
    }

    private JsonNode citations(String value) {
        return value == null ? json.createArrayNode() : json.readTree(value);
    }

    static UUID member(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    private static int charged(Question q) { return "SUCCEEDED".equals(q.status()) ? 1 : 0; }

    private static Failure failure(Question q) {
        return q.error() == null ? null : new Failure(q.error(), "질문을 완료하지 못했습니다.");
    }

    private static ConversationSummary summary(Conversation c) {
        return new ConversationSummary(c.id(), c.book(), c.title(), c.created(), c.updated(), c.scope());
    }
}
