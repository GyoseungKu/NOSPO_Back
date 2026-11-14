package kr.nospo.lifecycle.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.identity.contract.KakaoSignupIdentity;
import kr.nospo.lifecycle.application.*;
import kr.nospo.lifecycle.web.SignupController.ConsentInput;
import kr.nospo.support.*;
import kr.nospo.terms.contract.Terms;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth/signup/kakao")
public class KakaoSignupController {
    public record ContextRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String signupProof) {
        @Override public String toString() { return "KakaoContextRequest[REDACTED]"; }
    }

    public record Request(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String signupProof,
            @NotBlank @Size(max = 100) String nickname,
            @NotBlank @Size(max = 32) String phoneNumber,
            @Pattern(regexp = "[A-Za-z0-9_-]{43}") String emailProof,
            @NotNull @Size(max = 100) List<@NotNull @Valid ConsentInput> consents) {
        @Override public String toString() { return "KakaoSignupRequest[REDACTED]"; }
    }

    private final KakaoSignupService service;
    private final KakaoSignupIdentity identity;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final boolean secureCookie;

    public KakaoSignupController(KakaoSignupService service, KakaoSignupIdentity identity,
            DatabaseGuard guard, Clock clock, @Value("${nospo.auth.secure-cookie:true}") boolean secureCookie) {
        this.service = service;
        this.identity = identity;
        this.guard = guard;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/context")
    public ApiResponse<KakaoSignupIdentity.Context> context(
            @Valid @RequestBody ContextRequest request, HttpServletRequest http) {
        guard.rateLimit("kakao-context-ip:" + http.getRemoteAddr(), 60, Duration.ofMinutes(15), clock.instant());
        return ApiResponse.of(identity.context(request.signupProof()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SignupResult>> signup(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Request request, HttpServletRequest http) {
        guard.rateLimit("signup-ip:" + http.getRemoteAddr(), 20, Duration.ofMinutes(15), clock.instant());
        var result = service.signup(key, new KakaoSignupService.Command(request.signupProof(), request.nickname(),
                request.phoneNumber(), request.emailProof(), request.consents().stream()
                        .map(c -> new Terms.Consent(c.termVersionId(), c.agreed())).toList()));
        String cookie = ResponseCookie.from("refreshToken", result.refreshToken()).httpOnly(true)
                .secure(secureCookie).sameSite("Lax").path("/api/v1/auth")
                .maxAge(Math.max(0, Duration.between(clock.instant(), result.refreshExpiresAt()).toSeconds()))
                .build().toString();
        return ResponseEntity.status(201).header(HttpHeaders.SET_COOKIE, cookie).body(ApiResponse.of(result.data()));
    }
}
