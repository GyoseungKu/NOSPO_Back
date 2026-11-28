package kr.nospo.terms.web;

import java.security.Principal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.nospo.support.ApiResponse;
import kr.nospo.terms.application.AdminTerms;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/admin/terms/versions", produces = "application/json")
@Tag(name = "관리자 약관")
@SecurityRequirement(name = "bearerAuth")
class AdminTermsController {
    private final AdminTerms terms;

    AdminTermsController(AdminTerms terms) { this.terms = terms; }

    @GetMapping
    @Operation(summary = "약관 버전 목록", description = "관리자가 초안과 발행된 버전을 등록 시각의 역순으로 조회합니다.")
    ApiResponse<AdminTerms.TermVersionPage> list(@RequestParam(required = false) UUID termId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(terms.list(termId, cursor, limit));
    }

    @GetMapping("/{termVersionId}")
    @Operation(summary = "약관 버전 상세", description = "약관 본문과 시행 시각 및 발행 상태를 조회합니다.")
    ApiResponse<AdminTerms.TermVersionDetail> detail(@PathVariable UUID termVersionId) {
        return ApiResponse.of(terms.detail(termVersionId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "약관 버전 등록", description = "기존 약관의 새 버전 또는 새 종류의 약관을 초안으로 등록합니다.")
    ApiResponse<AdminTerms.TermVersionView> create(Principal principal, @RequestBody AdminTerms.TermVersionCreate input) {
        return ApiResponse.of(terms.create(UUID.fromString(principal.getName()), input));
    }

    @PutMapping("/{termVersionId}/publication")
    @Operation(summary = "약관 시행 설정", description = "시행일과 재동의 여부를 설정합니다. 시행이 시작된 버전은 409를 반환합니다.")
    ApiResponse<AdminTerms.TermVersionView> publish(Principal principal, @PathVariable UUID termVersionId, @RequestBody AdminTerms.TermPublication input) {
        return ApiResponse.of(terms.publish(UUID.fromString(principal.getName()), termVersionId, input));
    }
}
