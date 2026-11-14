package kr.nospo.identity.web;

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
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam(required=false) String returnPath,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if (!singleParameters(request, Set.of("returnPath"))) throw new ApiException(422, "VALIDATION_ERROR");
        var result = service.authorize(returnPath, member(jwt), session(jwt), request.getRemoteAddr());
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, result.authorizationUrl())
                .header(HttpHeaders.SET_COOKIE, flowCookie(result.cookie(), 300))
                .header("Referrer-Policy", "no-referrer").build();
    }
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
