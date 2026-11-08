package kr.nospo.lifecycle.web;

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
    private final boolean secureCookie;

    public SignupController(
            SignupService service,
            Clock clock,
            @Value("${nospo.auth.secure-cookie:true}") boolean secureCookie) {
        this.service = service;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/auth/availability/nickname")
    public ApiResponse<Map<String, Boolean>> available(@RequestParam String nickname) {
        return ApiResponse.of(Map.of("available", service.nicknameAvailable(nickname)));
    }

    @PostMapping("/auth/signup")
    public ResponseEntity<ApiResponse<SignupResult>> signup(
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Request request) {
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

    @GetMapping("/me")
    public ApiResponse<SignupResult.Member> me(Principal principal) {
        return ApiResponse.of(service.me(UUID.fromString(principal.getName())));
    }
}
