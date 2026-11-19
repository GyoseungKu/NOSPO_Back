package kr.nospo.lifecycle.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import kr.nospo.lifecycle.application.SignupResult;
import kr.nospo.lifecycle.application.SignupService;
import kr.nospo.support.ApiResponse;
import kr.nospo.terms.contract.Terms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.*;
import java.util.*;

@Tag(name = "회원가입 및 내 정보", description = "이메일 회원가입, 닉네임 중복 확인 및 내 정보 조회")
@RestController
@RequestMapping("/api/v1")
public class SignupController {
    public record ConsentInput(@NotNull UUID termVersionId, @NotNull Boolean agreed) {}

    public record Request(
            @NotBlank @Size(max = 100) String nickname,
            @NotBlank @Size(max = 128) String emailProof,
            @NotNull @Size(min = 10, max = 128) String password,
            @NotBlank @Size(max = 32) String phoneNumber,
            @NotNull @Size(max = 100) List<@NotNull @Valid ConsentInput> consents) {}

    private final SignupService service;
    private final Clock clock;
    private final kr.nospo.support.DatabaseGuard guard;
    private final boolean secureCookie;

    public SignupController(
            SignupService service,
            kr.nospo.support.DatabaseGuard guard,
            Clock clock,
            @Value("${nospo.auth.secure-cookie:true}") boolean secureCookie) {
        this.service = service;
        this.guard = guard;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    @Operation(summary = "닉네임 사용 가능 여부", description = "닉네임의 사용 가능 여부를 available 값으로 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping("/auth/availability/nickname")
    public ApiResponse<Map<String, Boolean>> available(@RequestParam String nickname) {
        return ApiResponse.of(Map.of("available", service.nicknameAvailable(nickname)));
    }

    @Operation(summary = "이메일 회원가입", description = "이메일 인증으로 받은 emailProof, 비밀번호, 회원 정보와 약관 동의를 제출합니다. Idempotency-Key에 UUID를 전달합니다. 가입 결과와 refreshToken 쿠키를 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료")
    @PostMapping("/auth/signup")
    public ResponseEntity<ApiResponse<SignupResult>> signup(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Request request,
            jakarta.servlet.http.HttpServletRequest http) {
        guard.rateLimit(
                "signup-ip:" + http.getRemoteAddr(), 20, Duration.ofMinutes(15), clock.instant());
        var command =
                new SignupService.Command(
                        request.nickname(),
                        request.emailProof(),
                        request.password(),
                        request.phoneNumber(),
                        request.consents().stream()
                                .map(c -> new Terms.Consent(c.termVersionId(), c.agreed()))
                                .toList());
        var result = service.signup(key, command);
        var cookie =
                ResponseCookie.from("refreshToken", result.refreshToken())
                        .httpOnly(true)
                        .secure(secureCookie)
                        .sameSite("Lax")
                        .path("/api/v1/auth")
                        .maxAge(
                                Math.max(
                                        0,
                                        Duration.between(clock.instant(), result.refreshExpiresAt())
                                                .toSeconds()))
                        .build();
        return ResponseEntity.status(201)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.of(result.data()));
    }

    @Operation(summary = "내 회원 정보 조회", description = "로그인한 회원의 정보를 조회합니다. Bearer 액세스 토큰이 필요합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping("/me")
    public ApiResponse<SignupResult.Member> me(Principal principal) {
        return ApiResponse.of(service.me(UUID.fromString(principal.getName())));
    }
}
