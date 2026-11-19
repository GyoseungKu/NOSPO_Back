package kr.nospo.terms.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import kr.nospo.support.ApiResponse;
import kr.nospo.terms.contract.Terms;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "약관", description = "현재 약관 목록 및 버전별 상세 조회")
@RestController
@RequestMapping("/api/v1/terms")
public class TermsController {
    private final Terms terms;

    public TermsController(Terms terms) {
        this.terms = terms;
    }

    @Operation(summary = "현재 약관 목록 조회", description = "회원가입 및 약관 동의에 사용할 현재 약관 버전 목록을 반환합니다. 로그인 없이 조회할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping
    public ApiResponse<Map<String, List<Terms.Version>>> current() {
        return ApiResponse.of(Map.of("items", terms.current()));
    }

    @Operation(summary = "약관 상세 조회", description = "약관 버전 UUID로 상세 내용을 조회합니다. 로그인 없이 조회할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 또는 처리 성공")
    @GetMapping("/{id}")
    public ApiResponse<Terms.Detail> detail(@PathVariable UUID id) {
        return ApiResponse.of(terms.detail(id));
    }
}
