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
