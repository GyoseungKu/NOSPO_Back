package kr.nospo.lifecycle.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.lifecycle.application.*;
import kr.nospo.support.*;
import kr.nospo.terms.contract.Terms;
import kr.nospo.lifecycle.web.SignupController.ConsentInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth/recovery")
public class RecoveryController {
    public record Request(@NotBlank @Size(max=128) String recoveryProof,@Size(max=128) String newPassword,
            @NotNull @Size(max=100) List<@NotNull @Valid ConsentInput> consents) {
        @Override public String toString() {return "RecoveryRequest[REDACTED]";}
    }
    private final RecoveryService service;
    private final DatabaseGuard guard;
    private final Clock clock;
    private final boolean secure;
    public RecoveryController(RecoveryService service,DatabaseGuard guard,Clock clock,@Value("${nospo.auth.secure-cookie:true}") boolean secure) {
        this.service=service;this.guard=guard;this.clock=clock;this.secure=secure;
    }
    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<SignupResult>> complete(@Valid @RequestBody Request request,jakarta.servlet.http.HttpServletRequest http) {
        guard.rateLimit("recovery-complete:"+http.getRemoteAddr(),30,Duration.ofMinutes(15),clock.instant());
        var result=service.recover(new RecoveryService.Command(request.recoveryProof(),request.newPassword(),
                request.consents().stream().map(c->new Terms.Consent(c.termVersionId(),c.agreed())).toList()));
        var cookie=ResponseCookie.from("refreshToken",result.refreshToken()).httpOnly(true).secure(secure).sameSite("Lax")
                .path("/api/v1/auth").maxAge(Math.max(0,Duration.between(clock.instant(),result.refreshExpiresAt()).toSeconds())).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie.toString()).body(ApiResponse.of(result.data()));
    }
}
