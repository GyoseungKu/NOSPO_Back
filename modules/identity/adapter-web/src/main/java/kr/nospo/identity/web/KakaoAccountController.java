package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.identity.application.KakaoAccountService;
import kr.nospo.support.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Tag(name = "카카오 계정 연결", description = "카카오 재인증 및 로그인 수단 연결")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
public class KakaoAccountController {
    public record Reauth(@NotBlank String purpose) {}
    public record Authorization(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String reauthProof) {
        @Override public String toString() { return "Authorization[REDACTED]"; }
    }
    public record Link(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String linkProof) {
        @Override public String toString() { return "Link[REDACTED]"; }
    }
    private final KakaoAccountService service;
    private final boolean secure;
    public KakaoAccountController(KakaoAccountService service, @Value("${nospo.auth.secure-cookie:true}") boolean secure) {
        this.service = service; this.secure = secure;
    }
    private UUID member(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private UUID session(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("sid")); }
    private ResponseEntity<?> started(kr.nospo.identity.application.KakaoOAuthService.Started result) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                ResponseCookie.from("nospoKakaoFlow", result.cookie()).httpOnly(true).secure(secure)
                        .sameSite("Lax").path("/api/v1/auth/kakao/callback").maxAge(300).build().toString())
                .body(ApiResponse.of(Map.of("authorizationUrl", result.authorizationUrl())));
    }
    @Operation(summary = "카카오 재인증 시작", description = "작업 목적 purpose에 대한 카카오 재인증 URL을 반환하고 인증 흐름 쿠키를 설정합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/reauth/kakao")
    public ResponseEntity<?> reauthenticate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Reauth input) {
        return started(service.reauthenticate(member(jwt), session(jwt), input.purpose()));
    }
    @Operation(summary = "카카오 연결 인증 시작", description = "reauthProof로 카카오 연결 인증 URL을 발급받고 인증 흐름 쿠키를 설정합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/login-methods/kakao/authorization")
    public ResponseEntity<?> authorize(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Authorization input) {
        return started(service.authorizeLink(member(jwt), session(jwt), input.reauthProof()));
    }
    @Operation(summary = "카카오 계정 연결 완료", description = "카카오 인증 후 받은 linkProof로 연결을 완료합니다. Idempotency-Key에 UUID를 전달합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/login-methods/kakao")
    public ApiResponse<KakaoAccountService.Linked> link(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Link input) {
        return ApiResponse.of(service.link(member(jwt), session(jwt), input.linkProof(), key));
    }
}
