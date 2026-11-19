package kr.nospo.lifecycle.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.lifecycle.application.WithdrawalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@Tag(name = "회원 탈퇴", description = "재인증을 통한 회원 탈퇴")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
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
    @Operation(summary = "회원 탈퇴", description = "탈퇴 목적의 reauthProof와 confirmed=true를 제출합니다. Idempotency-Key에 UUID를 전달합니다. 처리 후 refreshToken 쿠키를 삭제합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리 완료 (응답 본문 없음)")
    @PostMapping("/withdrawal")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Request request){
        service.withdraw(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),key,request.reauthProof());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,ResponseCookie.from("refreshToken","")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1/auth").maxAge(0).build().toString()).build();
    }
}
