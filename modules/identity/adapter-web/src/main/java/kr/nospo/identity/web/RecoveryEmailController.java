package kr.nospo.identity.web;

import jakarta.validation.Valid;
import kr.nospo.identity.application.RecoveryIdentityService;
import kr.nospo.identity.contract.RecoveryIdentity;
import kr.nospo.support.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/recovery/email-verifications")
public class RecoveryEmailController {
    private final RecoveryIdentityService service;
    public RecoveryEmailController(RecoveryIdentityService service) {this.service=service;}
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<RecoveryIdentityService.Requested> request(@Valid @RequestBody EmailVerificationController.EmailRequest request,jakarta.servlet.http.HttpServletRequest http) {
        return ApiResponse.of(service.request(request.email(),http.getRemoteAddr()));
    }
    @PostMapping("/confirm")
    public ApiResponse<RecoveryIdentity.Issued> confirm(@Valid @RequestBody EmailVerificationController.ConfirmRequest request) {
        return ApiResponse.of(service.confirm(request.token()));
    }
}
