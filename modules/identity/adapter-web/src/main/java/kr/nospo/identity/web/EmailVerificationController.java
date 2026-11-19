package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import kr.nospo.identity.application.EmailVerificationService;
import kr.nospo.support.ApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "가입 이메일 인증", description = "이메일 사용 가능 여부 확인 및 가입용 이메일 인증")
@RestController
@RequestMapping("/api/v1/auth")
public class EmailVerificationController {
    public record EmailRequest(@NotBlank @Size(max = 300) String email) {}

    public record ConfirmRequest(@NotBlank @Size(max = 128) String token) {}

    private final EmailVerificationService service;

    public EmailVerificationController(EmailVerificationService service) {
        this.service = service;
    }

    @Operation(summary = "이메일 사용 가능 여부", description = "이메일의 사용 가능 여부를 available 값으로 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping("/availability/email")
    public ApiResponse<Map<String, Boolean>> available(@RequestParam String email) {
        return ApiResponse.of(Map.of("available", service.available(email)));
    }

    @Operation(summary = "가입 인증 메일 요청", description = "회원가입에 사용할 이메일로 인증 메일 발송을 요청합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "인증 요청 접수")
    @PostMapping("/signup/email-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EmailVerificationService.Issued> issue(
            @Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        return ApiResponse.of(service.request(request.email(), http.getRemoteAddr()));
    }

    @Operation(summary = "가입 이메일 인증 완료", description = "메일의 token을 제출하여 회원가입에 사용할 emailProof를 발급받습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/signup/email-verifications/confirm")
    public ApiResponse<EmailVerificationService.Confirmed> confirm(
            @Valid @RequestBody ConfirmRequest request) {
        return ApiResponse.of(service.confirm(request.token()));
    }
}
