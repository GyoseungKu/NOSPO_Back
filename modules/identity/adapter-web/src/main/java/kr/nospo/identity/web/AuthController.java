package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import kr.nospo.identity.application.AuthService;
import kr.nospo.identity.application.PasswordResetService;
import kr.nospo.identity.contract.AuthResult;
import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.identity.contract.LoginMethods;
import kr.nospo.member.contract.Members;
import kr.nospo.support.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@Tag(name = "로그인 및 토큰", description = "이메일 로그인, 토큰 갱신, 로그아웃 및 비밀번호 재설정")
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
    private final LoginMethods methods;
    private final boolean secure;
    private final Clock clock;

    public AuthController(
            AuthService auth,
            PasswordResetService resets,
            Members members,
            SignupIdentity identity,
            LoginMethods methods,
            @Value("${nospo.auth.secure-cookie:true}") boolean secure,
            Clock clock) {
        this.auth = auth;
        this.resets = resets;
        this.members = members;
        this.identity = identity;
        this.methods = methods;
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
                methods.get(id).loginMethods(),
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

    @Operation(summary = "이메일 로그인", description = "이메일과 비밀번호로 로그인합니다. 액세스 토큰과 회원 정보를 반환하고 HttpOnly refreshToken 쿠키를 설정합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
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

    @Operation(summary = "액세스 토큰 갱신", description = "refreshToken 쿠키로 새 액세스 토큰을 발급하고 리프레시 토큰 쿠키를 교체합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
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

    @Operation(summary = "로그아웃", description = "액세스 토큰의 세션 또는 refreshToken 쿠키에 해당하는 로그인을 종료하고 쿠키를 삭제합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리 완료 (응답 본문 없음)")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal Jwt jwt,
            @CookieValue(name = "refreshToken", required = false) String token) {
        auth.logout(jwt == null ? null : UUID.fromString(jwt.getClaimAsString("sid")), token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).build();
    }

    @Operation(summary = "비밀번호 재설정 요청", description = "이메일로 비밀번호 재설정 인증을 요청합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "인증 요청 접수")
    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> request(@Valid @RequestBody Email input, HttpServletRequest request) {
        return ApiResponse.of(resets.request(input.email(), request.getRemoteAddr()));
    }

    @Operation(summary = "비밀번호 재설정 인증번호 확인", description = "verificationId와 6자리 code를 제출하여 resetProof를 발급받습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/password-resets/verify")
    public ApiResponse<?> verify(@Valid @RequestBody Verify input) {
        return ApiResponse.of(resets.verify(input.verificationId(), input.code()));
    }

    @Operation(summary = "비밀번호 재설정 완료", description = "resetProof와 새 비밀번호 newPassword를 제출하여 재설정을 완료합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리 완료 (응답 본문 없음)")
    @PostMapping("/password-resets/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@Valid @RequestBody Reset input, HttpServletRequest request) {
        resets.complete(input.resetProof(), input.newPassword(), request.getRemoteAddr());
    }
}
