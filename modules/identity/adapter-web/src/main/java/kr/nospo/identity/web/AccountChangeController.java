package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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

@Tag(name = "계정 정보 변경", description = "비밀번호 재인증 및 비밀번호·이메일 변경")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
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

    @Operation(summary = "비밀번호 재인증", description = "현재 비밀번호와 작업 목적 purpose를 제출하여 후속 작업에 사용할 재인증 증명을 발급받습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/reauth/password")
    public ApiResponse<?> reauth(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Reauth input) {
        return ApiResponse.of(
                service.reauthenticate(
                        member(jwt), session(jwt), input.password(), input.purpose()));
    }

    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호와 새 비밀번호를 제출합니다. 성공 시 refreshToken 쿠키를 삭제합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리 완료 (응답 본문 없음)")
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

    @Operation(summary = "이메일 변경 인증 요청", description = "새 이메일 newEmail과 재인증 증명 reauthProof를 제출하여 인증을 요청합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "인증 요청 접수")
    @PostMapping("/email-changes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> request(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Email input) {
        return ApiResponse.of(
                service.requestEmail(
                        member(jwt), session(jwt), input.newEmail(), input.reauthProof()));
    }

    @Operation(summary = "이메일 변경 인증번호 확인", description = "verificationId와 6자리 code를 제출하여 이메일 변경 증명을 발급받습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/email-changes/verify")
    public ApiResponse<?> verify(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Verify input) {
        return ApiResponse.of(
                service.verifyEmail(
                        member(jwt), session(jwt), input.verificationId(), input.code()));
    }

    @Operation(summary = "이메일 변경 완료", description = "인증번호 확인으로 받은 emailChangeProof를 제출합니다. 변경된 이메일을 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
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
