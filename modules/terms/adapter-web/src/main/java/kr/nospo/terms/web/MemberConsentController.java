package kr.nospo.terms.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.nospo.support.ApiResponse;
import kr.nospo.terms.contract.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/me/consents")
public class MemberConsentController {
    public record Input(@NotNull UUID termVersionId, @NotNull Boolean agreed) {}
    public record Submission(@NotEmpty @Size(max=100) List<@NotNull @Valid Input> consents) {}
    private final MemberConsents consents;
    public MemberConsentController(MemberConsents consents) { this.consents=consents; }

    @GetMapping
    public ApiResponse<MemberConsents.Page> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int limit) {
        return ApiResponse.of(consents.history(UUID.fromString(jwt.getSubject()),cursor,limit));
    }

    @GetMapping("/pending")
    public ApiResponse<MemberConsents.Pending> pending(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(consents.pending(UUID.fromString(jwt.getSubject()),session(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,List<MemberConsents.Event>>>> submit(
            @AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Submission input) {
        var values=input.consents().stream().map(c->new Terms.Consent(c.termVersionId(),c.agreed())).toList();
        var events=consents.submit(UUID.fromString(jwt.getSubject()),session(jwt),values);
        return ResponseEntity.status(201).body(ApiResponse.of(Map.of("items",events)));
    }

    private static UUID session(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("sid")); }

}
