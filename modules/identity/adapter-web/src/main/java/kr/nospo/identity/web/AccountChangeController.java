package kr.nospo.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import kr.nospo.identity.application.AccountChangeService;
import kr.nospo.support.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
public class AccountChangeController {
    public record Reauth(@NotNull @Size(max = 128) String password, @NotBlank String purpose) {}

    public record Password(
            @NotNull @Size(max = 128) String currentPassword,
            @NotNull @Size(min = 10, max = 128) String newPassword) {}

    public record Email(
            @NotBlank @Size(max = 254) String newEmail,
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String reauthProof) {}

    public record Verify(
            @NotNull UUID verificationId, @NotNull @Pattern(regexp = "[0-9]{6}") String code) {}

    public record Complete(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String emailChangeProof) {}

    private final AccountChangeService service;
    private final boolean secure;

    public AccountChangeController(
            AccountChangeService service,
            @Value("${nospo.auth.secure-cookie:true}") boolean secure) {
        this.service = service;
        this.secure = secure;
    }

    private UUID member(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private UUID session(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }

    @PostMapping("/reauth/password")
    public ApiResponse<?> reauth(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Reauth input) {
        return ApiResponse.of(
                service.reauthenticate(
                        member(jwt), session(jwt), input.password(), input.purpose()));
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> password(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Password input) {
        service.password(member(jwt), session(jwt), input.currentPassword(), input.newPassword());
        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        ResponseCookie.from("refreshToken", "")
                                .httpOnly(true)
                                .secure(secure)
                                .sameSite("Lax")
                                .path("/api/v1/auth")
                                .maxAge(0)
                                .build()
                                .toString())
                .build();
    }

    @PostMapping("/email-changes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> request(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Email input) {
        return ApiResponse.of(
                service.requestEmail(
                        member(jwt), session(jwt), input.newEmail(), input.reauthProof()));
    }

    @PostMapping("/email-changes/verify")
    public ApiResponse<?> verify(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Verify input) {
        return ApiResponse.of(
                service.verifyEmail(
                        member(jwt), session(jwt), input.verificationId(), input.code()));
    }

    @PatchMapping("/email")
    public ApiResponse<?> complete(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Complete input) {
        return ApiResponse.of(
                Map.of(
                        "email",
                        service.completeEmail(
                                member(jwt), session(jwt), input.emailChangeProof())));
    }
}
