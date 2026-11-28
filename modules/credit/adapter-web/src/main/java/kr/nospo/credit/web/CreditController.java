package kr.nospo.credit.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.nospo.credit.contract.AiCredits;
import kr.nospo.support.ApiException;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI 토큰")
public class CreditController {
    private final AiCredits credits;

    public CreditController(AiCredits credits) {
        this.credits = credits;
    }

    @GetMapping("/ai/credit-policy")
    @Operation(summary = "AI 토큰 정책 조회", description = "가입 지급량과 AI 기능의 명세상 비용을 조회합니다.")
    public ApiResponse<Map<String, Object>> policy() {
        return ApiResponse.of(Map.of("signupGrant", 5, "questionCost", 1, "graphCost", 1,
                "graphRegenerationCost", 1, "reviewRewardMode", "ADMIN_SPECIFIED"));
    }

    @GetMapping("/me/ai/credits")
    @Operation(summary = "내 AI 토큰 조회", description = "본인의 잔액, 예약량, 가용량을 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AiCredits.Balance> balance(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(credits.balance(UUID.fromString(jwt.getSubject())));
    }

    @GetMapping("/admin/members/{memberId}/ai/credits")
    @Operation(summary = "회원 AI 토큰 조회", description = "관리자가 활성 회원의 잔액, 예약량, 가용량을 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AiCredits.Balance> adminBalance(@PathVariable UUID memberId) {
        return ApiResponse.of(credits.balance(memberId));
    }

    @GetMapping("/me/ai/credit-transactions")
    @Operation(summary = "내 AI 토큰 거래 목록", description = "본인 토큰 거래를 유형과 기간으로 필터링해 조회합니다.")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<AiCredits.Page> transactions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String type, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(credits.transactions(UUID.fromString(jwt.getSubject()), type, from, to, cursor, limit));
    }

    @GetMapping("/me/ai/credit-transactions/{transactionId}")
    @Operation(summary = "내 AI 토큰 거래 상세", description = "본인의 지정 거래와 연결된 작업 또는 위키 제출을 확인합니다.")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<AiCredits.Transaction> transaction(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID transactionId) {
        return ApiResponse.of(credits.transaction(UUID.fromString(jwt.getSubject()), transactionId));
    }

    @GetMapping("/admin/members/{memberId}/ai/credit-transactions")
    @Operation(summary = "회원 AI 토큰 거래 목록", description = "관리자가 활성 회원의 거래를 유형과 기간으로 필터링해 조회합니다.")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<AiCredits.Page> adminTransactions(@PathVariable UUID memberId,
            @RequestParam(required = false) String type, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(credits.transactions(memberId, type, from, to, cursor, limit));
    }

    public record Refund(String reason) {}

    @PostMapping("/admin/ai/credit-transactions/{transactionId}/refund")
    @Operation(summary = "관리자 AI 토큰 환불", description = "사용 거래의 전액을 한 번 복구합니다. 사유와 UUID Idempotency-Key가 필요합니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<AiCredits.Transaction> refund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Refund refund) {
        return ApiResponse.of(credits.refund(UUID.fromString(jwt.getSubject()), transactionId, key, refund.reason()));
    }

    public record Adjustment(String type, @Schema(implementation = Integer.class) JsonNode amount, String reason) {
        int parsedAmount() {
            if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToInt())
                throw new ApiException(422, "VALIDATION_ERROR");
            return amount.intValue();
        }
    }

    @PostMapping("/admin/members/{memberId}/ai/credit-adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "AI 토큰 지급·회수", description = "관리자가 사유와 금액을 지정합니다. Idempotency-Key에는 UUID를 보냅니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<AiCredits.Transaction> adjust(@PathVariable UUID memberId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Adjustment adjustment) {
        return ApiResponse.of(credits.adjust(UUID.fromString(jwt.getSubject()), memberId, key,
                adjustment.type(), adjustment.parsedAmount(), adjustment.reason()));
    }
}
