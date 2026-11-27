package kr.nospo.wiki.web;

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
@RequestMapping("/api/v1")
class WikiReviewController {
    private static final String ADMIN = "/admin/wiki/submissions";
    private static final String SUBMISSION = "/wiki/submissions/{submissionId}";
    private final WikiReviews reviews;

    WikiReviewController(WikiReviews reviews) { this.reviews = reviews; }

    record Comment(String comment) {}
    record Reason(String reason) {}

    @PostMapping("/wiki/drafts/{draftId}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<?> submit(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.submit(member(principal), draftId, tag, key));
    }

    @GetMapping("/me/wiki/submissions")
    ApiResponse<?> list(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) ReviewStatus status, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.list(member(principal), false, bookId, status, cursor, limit));
    }

    @GetMapping(SUBMISSION)
    ApiResponse<?> detail(Principal principal, @PathVariable UUID submissionId) {
        return ApiResponse.of(reviews.details(member(principal), submissionId, false).data());
    }

    @PostMapping(SUBMISSION + "/revision-drafts")
    ResponseEntity<?> revision(Principal principal, @PathVariable UUID submissionId) {
        var result = reviews.revision(member(principal), submissionId);
        return response(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.draft(), result.draft().revision());
    }

    @GetMapping("/me/wiki/activities")
    ApiResponse<?> activities(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) String type, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.activities(member(principal), bookId, type, from, to, cursor, limit));
    }

    @GetMapping("/me/wiki/review-summary")
    ApiResponse<?> summary(Principal principal) {
        return ApiResponse.of(reviews.summary(member(principal)));
    }

    @GetMapping(ADMIN)
    ApiResponse<?> adminList(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) ReviewStatus status, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.list(member(principal), true, bookId, status, cursor, limit));
    }

    @GetMapping(ADMIN + "/{submissionId}")
    ResponseEntity<?> adminDetails(Principal principal, @PathVariable UUID submissionId) {
        var result = reviews.details(member(principal), submissionId, true);
        return response(HttpStatus.OK, result.data(), result.revision());
    }

    @GetMapping(ADMIN + "/{submissionId}/diff")
    ApiResponse<?> diff(Principal principal, @PathVariable UUID submissionId) {
        return ApiResponse.of(reviews.diff(member(principal), submissionId));
    }

    @PostMapping(ADMIN + "/{submissionId}/approve")
    ApiResponse<?> approve(Principal principal, @PathVariable UUID submissionId, @RequestBody WikiReviews.Decision input,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.approve(member(principal), submissionId, tag, key, input));
    }

    @PostMapping(ADMIN + "/{submissionId}/request-changes")
    ApiResponse<?> changes(Principal principal, @PathVariable UUID submissionId, @RequestBody Comment input,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.decide(member(principal), submissionId, tag, key, ReviewStatus.CHANGES_REQUESTED, input.comment()));
    }

    @PostMapping(ADMIN + "/{submissionId}/reject")
    ApiResponse<?> reject(Principal principal, @PathVariable UUID submissionId, @RequestBody Reason input,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(reviews.decide(member(principal), submissionId, tag, key, ReviewStatus.REJECTED, input.reason()));
    }

    @GetMapping(ADMIN + "/{submissionId}/history")
    ApiResponse<?> history(Principal principal, @PathVariable UUID submissionId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(reviews.history(member(principal), submissionId, cursor, limit));
    }
}
