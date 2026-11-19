package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.identity.application.*;
import kr.nospo.support.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@Tag(name = "카카오 로그인", description = "카카오 OAuth 리다이렉트 및 인증 결과 교환")
@RestController
@RequestMapping("/api/v1/auth/kakao")
public class KakaoOAuthController {
    public record Exchange(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String exchangeCode) {
        @Override public String toString() { return "Exchange[REDACTED]"; }
    }
    private final KakaoOAuthService service;
    private final KakaoOAuthSettings settings;
    private final boolean secure;
    private final Clock clock;

    public KakaoOAuthController(KakaoOAuthService service, KakaoOAuthSettings settings,
            @Value("${nospo.auth.secure-cookie:true}") boolean secure, Clock clock) {
        this.service = service; this.settings = settings; this.secure = secure; this.clock = clock;
    }
    private UUID member(Jwt jwt) { return jwt == null ? null : UUID.fromString(jwt.getSubject()); }
    private UUID session(Jwt jwt) { return jwt == null ? null : UUID.fromString(jwt.getClaimAsString("sid")); }
    private String flowCookie(String value, int seconds) {
        return ResponseCookie.from("nospoKakaoFlow", value).httpOnly(true).secure(secure)
                .sameSite("Lax").path("/api/v1/auth/kakao/callback").maxAge(seconds).build().toString();
    }
    private boolean singleParameters(HttpServletRequest request, Set<String> allowed) {
        return request.getParameterMap().entrySet().stream()
                .allMatch(e -> allowed.contains(e.getKey()) && e.getValue().length == 1);
    }
    @Operation(summary = "카카오 로그인 시작", description = "카카오 인증 페이지로 이동합니다. 선택적으로 returnPath를 전달할 수 있으며 인증 흐름 쿠키를 설정합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "302", description = "인증 페이지 또는 프런트엔드로 리다이렉트")
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam(required=false) String returnPath,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if (!singleParameters(request, Set.of("returnPath"))) throw new ApiException(422, "VALIDATION_ERROR");
        var result = service.authorize(returnPath, member(jwt), session(jwt), request.getRemoteAddr());
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, result.authorizationUrl())
                .header(HttpHeaders.SET_COOKIE, flowCookie(result.cookie(), 300))
                .header("Referrer-Policy", "no-referrer").build();
    }
    @Operation(summary = "카카오 인증 콜백", description = "카카오가 호출하는 콜백입니다. state와 인증 흐름 쿠키를 확인한 후 프런트엔드 콜백으로 exchangeCode 또는 error를 전달합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "302", description = "인증 페이지 또는 프런트엔드로 리다이렉트")
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request) {
        String cookie = null;
        if (request.getCookies() != null) {
            var matching = Arrays.stream(request.getCookies()).filter(c -> c.getName().equals("nospoKakaoFlow")).toList();
            if (matching.size() == 1) cookie = matching.getFirst().getValue();
        }
        boolean valid = singleParameters(request, Set.of("state", "code", "error", "error_description", "error_uri"));
        var result = valid ? service.callback(request.getParameter("state"), cookie,
                request.getParameter("code"), request.getParameter("error"), request.getRequestURL().toString())
                : new KakaoOAuthService.Callback(null, "KAKAO_AUTH_FAILED");
        String query = result.exchangeCode() == null ? "error=" + result.error() : "exchangeCode=" + result.exchangeCode();
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, settings.frontendCallback() + "?" + query)
                .header(HttpHeaders.SET_COOKIE, flowCookie("", 0))
                .header("Referrer-Policy", "no-referrer").build();
    }
    @Operation(summary = "카카오 인증 결과 교환", description = "콜백에서 받은 exchangeCode를 제출하여 인증 결과를 받습니다. 로그인 결과인 경우 refreshToken 쿠키를 설정합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@Valid @RequestBody Exchange input,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var result = service.exchange(input.exchangeCode(), member(jwt), session(jwt), request.getRemoteAddr());
        var response = ResponseEntity.ok();
        if (result.refresh() != null)
            response.header(HttpHeaders.SET_COOKIE, ResponseCookie.from("refreshToken", result.refresh())
                    .httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1/auth")
                    .maxAge(Math.max(0, Duration.between(clock.instant(), result.refreshExpiresAt()).toSeconds()))
                    .build().toString());
        return response.body(ApiResponse.of(result.data()));
    }
}
