package kr.nospo.identity.web;

import kr.nospo.identity.contract.LoginMethods;
import kr.nospo.support.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/login-methods")
public class LoginMethodsController {
    private final LoginMethods methods;

    public LoginMethodsController(LoginMethods methods) {
        this.methods = methods;
    }

    @GetMapping
    public ApiResponse<LoginMethods.Status> get(Principal principal) {
        return ApiResponse.of(methods.get(UUID.fromString(principal.getName())));
    }
}
