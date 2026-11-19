package kr.nospo.terms.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.support.ApiResponse;
import kr.nospo.terms.contract.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Tag(name = "회원 약관 동의", description = "약관 동의 이력 조회 및 추가 동의")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/consents")
public class MemberConsentController {
    public record Input(@NotNull UUID termVersionId, @NotNull Boolean agreed) {}
    public record Submission(@NotEmpty @Size(max=100) List<@NotNull @Valid Input> consents) {}
    private final MemberConsents consents;
    public MemberConsentController(MemberConsents consents) { this.consents=consents; }

    @Operation(summary = "약관 동의 이력 조회", description = "cursor와 limit으로 동의 이력을 조회합니다. limit의 기본값은 20입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping
    public ApiResponse<MemberConsents.Page> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int limit) {
        return ApiResponse.of(consents.history(UUID.fromString(jwt.getSubject()),cursor,limit));
    }

    @Operation(summary = "미동의 약관 조회", description = "현재 회원에게 추가 동의가 필요한 약관을 조회합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping("/pending")
    public ApiResponse<MemberConsents.Pending> pending(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(consents.pending(UUID.fromString(jwt.getSubject()),session(jwt)));
    }

    @Operation(summary = "약관 동의 제출", description = "약관 버전 termVersionId와 동의 여부 agreed 목록을 제출하고 생성된 동의 이력을 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,List<MemberConsents.Event>>>> submit(
            @AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Submission input) {
        var values=input.consents().stream().map(c->new Terms.Consent(c.termVersionId(),c.agreed())).toList();
        var events=consents.submit(UUID.fromString(jwt.getSubject()),session(jwt),values);
        return ResponseEntity.status(201).body(ApiResponse.of(Map.of("items",events)));
    }

    private static UUID session(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("sid")); }

}
