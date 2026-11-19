package kr.nospo.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import kr.nospo.identity.contract.LoginMethods;
import kr.nospo.support.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@Tag(name = "로그인 수단 조회", description = "회원에게 연결된 로그인 수단 확인")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/login-methods")
public class LoginMethodsController {
    private final LoginMethods methods;

    public LoginMethodsController(LoginMethods methods) {
        this.methods = methods;
    }

    @Operation(summary = "내 로그인 수단 조회", description = "현재 회원의 이메일·카카오 로그인 수단 상태를 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping
    public ApiResponse<LoginMethods.Status> get(Principal principal) {
        return ApiResponse.of(methods.get(UUID.fromString(principal.getName())));
    }
}
