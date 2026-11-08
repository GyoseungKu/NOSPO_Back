package kr.nospo.terms.web;

import kr.nospo.support.ApiResponse;
import kr.nospo.terms.contract.Terms;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/terms")
public class TermsController {
    private final Terms terms;

    public TermsController(Terms terms) {
        this.terms = terms;
    }

    @GetMapping
    public ApiResponse<Map<String, List<Terms.Version>>> current() {
        return ApiResponse.of(Map.of("items", terms.current()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Terms.Detail> detail(@PathVariable UUID id) {
        return ApiResponse.of(terms.detail(id));
    }
}
