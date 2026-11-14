package kr.nospo.identity.web;

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
    @PostMapping("/reauth/kakao")
    public ResponseEntity<?> reauthenticate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Reauth input) {
        return started(service.reauthenticate(member(jwt), session(jwt), input.purpose()));
    }
    @PostMapping("/login-methods/kakao/authorization")
    public ResponseEntity<?> authorize(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Authorization input) {
        return started(service.authorizeLink(member(jwt), session(jwt), input.reauthProof()));
    }
    @PostMapping("/login-methods/kakao")
    public ApiResponse<KakaoAccountService.Linked> link(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Link input) {
        return ApiResponse.of(service.link(member(jwt), session(jwt), input.linkProof(), key));
    }
}
