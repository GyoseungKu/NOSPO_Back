package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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

@Tag(name = "이메일 로그인 수단", description = "기존 계정에 이메일 로그인 추가")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
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
    @Operation(summary = "이메일 로그인 추가 인증 요청", description = "재인증 증명 reauthProof를 제출하여 이메일 로그인 추가를 위한 인증 메일을 요청합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "인증 요청 접수")
    @PostMapping("/verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EmailVerificationService.Issued> request(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Verification input, HttpServletRequest request) {
        return ApiResponse.of(service.request(member(jwt), session(jwt), input.reauthProof(), request.getRemoteAddr()));
    }
    @Operation(summary = "이메일 로그인 추가 인증 확인", description = "메일의 token을 제출하여 emailLoginProof를 발급받습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/verifications/confirm")
    public ApiResponse<EmailLoginService.Confirmed> confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Confirm input) {
        return ApiResponse.of(service.confirm(member(jwt), session(jwt), input.token()));
    }
    @Operation(summary = "이메일 로그인 활성화", description = "emailLoginProof와 비밀번호를 제출합니다. Idempotency-Key에 UUID를 전달합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping
    public ApiResponse<EmailLoginService.Enabled> enable(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Enable input) {
        return ApiResponse.of(service.enable(member(jwt), session(jwt), input.emailLoginProof(), input.password(), key));
    }
}
