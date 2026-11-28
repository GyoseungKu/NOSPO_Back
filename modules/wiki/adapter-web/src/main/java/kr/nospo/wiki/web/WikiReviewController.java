package kr.nospo.wiki.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

import kr.nospo.support.ApiResponse;
import kr.nospo.wiki.application.WikiReviews;
import kr.nospo.wiki.application.WikiStore.ReviewStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static kr.nospo.wiki.web.WikiDraftController.*;

@RestController
@Tag(name = "위키 검수")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1")
class WikiReviewController {
    private static final String ADMIN = "/admin/wiki/submissions";
    private static final String SUBMISSION = "/wiki/submissions/{submissionId}";
    private final WikiReviews reviews;

    WikiReviewController(WikiReviews reviews) { this.reviews = reviews; }

    record Comment(String comment) {}
    record Reason(String reason) {}

    @PostMapping("/wiki/drafts/{draftId}/submissions")
    @Operation(summary = "위키 초안 검수 제출", description = "초안의 검수 스냅샷을 고정하고 제출합니다. If-Match와 UUID Idempotency-Key가 필요합니다.")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<?> submit(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.submit(member(principal), draftId, tag, key));
    }

    @GetMapping("/me/wiki/submissions")
    @Operation(summary = "내 위키 제출 목록", description = "본인 제출 내역을 상태와 판본 기준으로 조회합니다.")
    ApiResponse<?> list(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) ReviewStatus status, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.list(member(principal), false, bookId, status, cursor, limit));
    }

    @GetMapping(SUBMISSION)
    @Operation(summary = "내 위키 제출 상세", description = "본인 제출의 고정 내용과 검수 상태·이력을 조회합니다.")
    ApiResponse<?> detail(Principal principal, @PathVariable UUID submissionId) {
        return ApiResponse.of(reviews.details(member(principal), submissionId, false).data());
    }

    @PostMapping(SUBMISSION + "/revision-drafts")
    @Operation(summary = "위키 재작성 초안 열기", description = "수정 요청된 제출에서 새 초안을 만들거나 기존 편집 초안을 반환합니다.")
    ResponseEntity<?> revision(Principal principal, @PathVariable UUID submissionId) {
        var result = reviews.revision(member(principal), submissionId);
        return response(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.draft(), result.draft().revision());
    }

    @GetMapping("/me/wiki/activities")
    @Operation(summary = "내 위키 활동 조회", description = "기간, 판본, 유형별 위키 활동을 페이지 단위로 조회합니다.")
    ApiResponse<?> activities(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) String type, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.activities(member(principal), bookId, type, from, to, cursor, limit));
    }

    @GetMapping("/me/wiki/review-summary")
    @Operation(summary = "내 위키 검수 요약", description = "편집 중인 초안 수와 제출의 검수 상태별 건수를 반환합니다.")
    ApiResponse<?> summary(Principal principal) {
        return ApiResponse.of(reviews.summary(member(principal)));
    }

    @GetMapping(ADMIN)
    @Operation(summary = "관리자 위키 검수 목록", description = "관리자가 판본과 상태로 위키 제출을 필터링해 조회합니다.")
    ApiResponse<?> adminList(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) ReviewStatus status, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.list(member(principal), true, bookId, status, cursor, limit));
    }

    @GetMapping(ADMIN + "/{submissionId}")
    @Operation(summary = "관리자 위키 검수 상세", description = "관리자가 제출 스냅샷과 검수 버전 ETag를 조회합니다.")
    ResponseEntity<?> adminDetails(Principal principal, @PathVariable UUID submissionId) {
        var result = reviews.details(member(principal), submissionId, true);
        return response(HttpStatus.OK, result.data(), result.revision());
    }

    @GetMapping(ADMIN + "/{submissionId}/diff")
    @Operation(summary = "관리자 위키 변경 비교", description = "제출 변경과 기준 공개 버전을 비교하고 최신 버전과의 충돌 여부를 표시합니다.")
    ApiResponse<?> diff(Principal principal, @PathVariable UUID submissionId) {
        return ApiResponse.of(reviews.diff(member(principal), submissionId));
    }

    @PostMapping(ADMIN + "/{submissionId}/approve")
    @Operation(summary = "관리자 위키 승인", description = "검수 완료 후 새 공개 버전을 생성하고 지정한 보상을 처리합니다. If-Match와 UUID Idempotency-Key가 필요합니다.")
    ApiResponse<?> approve(Principal principal, @PathVariable UUID submissionId, @RequestBody WikiReviews.Decision input,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.approve(member(principal), submissionId, tag, key, input));
    }

    @PostMapping(ADMIN + "/{submissionId}/request-changes")
    @Operation(summary = "관리자 위키 수정 요청", description = "검수 사유를 기록하고 작성자의 재작성을 허용합니다. If-Match와 UUID Idempotency-Key가 필요합니다.")
    ApiResponse<?> changes(Principal principal, @PathVariable UUID submissionId, @RequestBody Comment input,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.decide(member(principal), submissionId, tag, key, ReviewStatus.CHANGES_REQUESTED, input.comment()));
    }

    @PostMapping(ADMIN + "/{submissionId}/reject")
    @Operation(summary = "관리자 위키 반려", description = "검수 사유를 기록하고 제출을 반려합니다. If-Match와 UUID Idempotency-Key가 필요합니다.")
    ApiResponse<?> reject(Principal principal, @PathVariable UUID submissionId, @RequestBody Reason input,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.decide(member(principal), submissionId, tag, key, ReviewStatus.REJECTED, input.reason()));
    }

    @GetMapping(ADMIN + "/{submissionId}/history")
    @Operation(summary = "관리자 위키 검수 이력", description = "지정 제출의 검수 처리 이력을 페이지 단위로 조회합니다.")
    ApiResponse<?> history(Principal principal, @PathVariable UUID submissionId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.history(member(principal), submissionId, cursor, limit));
    }
}
