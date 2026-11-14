package kr.nospo.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import kr.nospo.identity.application.AuthService;
import kr.nospo.identity.application.PasswordResetService;
import kr.nospo.identity.contract.AuthResult;
import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.member.contract.Members;
import kr.nospo.support.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record Login(
            @NotBlank @Size(max = 254) String email, @NotNull @Size(max = 128) String password) {}

    public record Email(@NotBlank @Size(max = 254) String email) {}

    public record Verify(
            @NotNull UUID verificationId, @NotNull @Pattern(regexp = "[0-9]{6}") String code) {}

    public record Reset(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String resetProof,
            @NotNull @Size(min = 10, max = 128) String newPassword) {}

    private final AuthService auth;
    private final PasswordResetService resets;
    private final Members members;
    private final SignupIdentity identity;
    private final boolean secure;
    private final Clock clock;

    public AuthController(
            AuthService auth,
            PasswordResetService resets,
            Members members,
            SignupIdentity identity,
            @Value("${nospo.auth.secure-cookie:true}") boolean secure,
            Clock clock) {
        this.auth = auth;
        this.resets = resets;
        this.members = members;
        this.identity = identity;
        this.secure = secure;
        this.clock = clock;
    }

    private AuthResult.Member member(UUID id) {
        var profile = members.get(id);
        return new AuthResult.Member(
                id,
                profile.nickname(),
                identity.email(id),
                profile.phoneNumber(),
                false,
                profile.profileImageUrl(),
                List.of("EMAIL"),
                profile.createdAt());
    }

    private String cookie(String token, long seconds) {
        return ResponseCookie.from("refreshToken", token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(Math.max(0, seconds))
                .build()
                .toString();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Login input, HttpServletRequest request) {
        var s = auth.login(input.email(), input.password(), request.getRemoteAddr());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookie(
                                s.refresh(),
                                Duration.between(clock.instant(), s.expiry()).toSeconds()))
                .body(
                        ApiResponse.of(
                                new AuthResult(
                                        s.accessToken(), "Bearer", 900, member(s.member()))));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = "refreshToken", required = false) String token) {
        var s = auth.refresh(token);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookie(
                                s.refresh(),
                                Duration.between(clock.instant(), s.expiry()).toSeconds()))
                .body(
                        ApiResponse.of(
                                Map.of(
                                        "accessToken",
                                        s.accessToken(),
                                        "tokenType",
                                        "Bearer",
                                        "expiresIn",
                                        900)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal Jwt jwt,
            @CookieValue(name = "refreshToken", required = false) String token) {
        auth.logout(jwt == null ? null : UUID.fromString(jwt.getClaimAsString("sid")), token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).build();
    }

    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> request(@Valid @RequestBody Email input, HttpServletRequest request) {
        return ApiResponse.of(resets.request(input.email(), request.getRemoteAddr()));
    }

    @PostMapping("/password-resets/verify")
    public ApiResponse<?> verify(@Valid @RequestBody Verify input) {
        return ApiResponse.of(resets.verify(input.verificationId(), input.code()));
    }

    @PostMapping("/password-resets/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@Valid @RequestBody Reset input, HttpServletRequest request) {
        resets.complete(input.resetProof(), input.newPassword(), request.getRemoteAddr());
    }
}
