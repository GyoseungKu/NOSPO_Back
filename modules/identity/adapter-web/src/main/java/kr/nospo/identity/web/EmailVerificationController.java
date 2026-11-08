package kr.nospo.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import kr.nospo.identity.application.EmailVerificationService;
import kr.nospo.support.ApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class EmailVerificationController {
    public record EmailRequest(@NotBlank @Size(max = 300) String email) {}

    public record ConfirmRequest(@NotBlank @Size(max = 128) String token) {}

    private final EmailVerificationService service;

    public EmailVerificationController(EmailVerificationService service) {
        this.service = service;
    }

    @GetMapping("/availability/email")
    public ApiResponse<Map<String, Boolean>> available(@RequestParam String email) {
        return ApiResponse.of(Map.of("available", service.available(email)));
    }

    @PostMapping("/signup/email-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EmailVerificationService.Issued> issue(
            @Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        return ApiResponse.of(service.request(request.email(), http.getRemoteAddr()));
    }

    @PostMapping("/signup/email-verifications/confirm")
    public ApiResponse<EmailVerificationService.Confirmed> confirm(
            @Valid @RequestBody ConfirmRequest request) {
        return ApiResponse.of(service.confirm(request.token()));
    }
}
