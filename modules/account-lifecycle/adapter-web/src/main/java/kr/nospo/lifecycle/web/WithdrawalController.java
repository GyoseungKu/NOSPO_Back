package kr.nospo.lifecycle.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.lifecycle.application.WithdrawalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class WithdrawalController {
    public record Request(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String reauthProof,
                          @NotNull @AssertTrue Boolean confirmed){
        @Override public String toString(){return "WithdrawalRequest[REDACTED]";}
    }
    private final WithdrawalService service;
    private final boolean secure;
    public WithdrawalController(WithdrawalService service,@Value("${nospo.auth.secure-cookie:true}") boolean secure){
        this.service=service;this.secure=secure;
    }
    @PostMapping("/withdrawal")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Request request){
        service.withdraw(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),key,request.reauthProof());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,ResponseCookie.from("refreshToken","")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1/auth").maxAge(0).build().toString()).build();
    }
}
