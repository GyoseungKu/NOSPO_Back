package kr.nospo.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.identity.application.*;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/login-methods/email")
public class EmailLoginController {
    public record Verification(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String reauthProof) {
        @Override public String toString() { return "EmailLoginVerification[REDACTED]"; }
    }
    public record Confirm(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "EmailLoginConfirm[REDACTED]"; }
    }
    public record Enable(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String emailLoginProof,
                         @NotNull @Size(min=10,max=128) String password) {
        @Override public String toString() { return "EmailLoginEnable[REDACTED]"; }
    }
    private final EmailLoginService service;
    public EmailLoginController(EmailLoginService service) { this.service = service; }
    private UUID member(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private UUID session(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("sid")); }
    @PostMapping("/verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EmailVerificationService.Issued> request(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Verification input, HttpServletRequest request) {
        return ApiResponse.of(service.request(member(jwt), session(jwt), input.reauthProof(), request.getRemoteAddr()));
    }
    @PostMapping("/verifications/confirm")
    public ApiResponse<EmailLoginService.Confirmed> confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Confirm input) {
        return ApiResponse.of(service.confirm(member(jwt), session(jwt), input.token()));
    }
    @PostMapping
    public ApiResponse<EmailLoginService.Enabled> enable(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Enable input) {
        return ApiResponse.of(service.enable(member(jwt), session(jwt), input.emailLoginProof(), input.password(), key));
    }
}
