package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import kr.nospo.identity.application.RecoveryIdentityService;
import kr.nospo.identity.contract.RecoveryIdentity;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "계정 복구 이메일 인증", description = "계정 복구를 위한 이메일 인증")
@RestController
@RequestMapping("/api/v1/auth/recovery/email-verifications")
public class RecoveryEmailController {
    private final RecoveryIdentityService service;
    public RecoveryEmailController(RecoveryIdentityService service) {this.service=service;}
    @Operation(summary = "계정 복구 인증 메일 요청", description = "복구할 계정의 이메일로 인증 메일 발송을 요청합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "인증 요청 접수")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<RecoveryIdentityService.Requested> request(@Valid @RequestBody EmailVerificationController.EmailRequest request,jakarta.servlet.http.HttpServletRequest http) {
        return ApiResponse.of(service.request(request.email(),http.getRemoteAddr()));
    }
    @Operation(summary = "계정 복구 이메일 인증 완료", description = "메일의 token을 확인하여 계정 복구에 사용할 recoveryProof를 발급받습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @PostMapping("/confirm")
    public ApiResponse<RecoveryIdentity.Issued> confirm(@Valid @RequestBody EmailVerificationController.ConfirmRequest request) {
        return ApiResponse.of(service.confirm(request.token()));
    }
}
