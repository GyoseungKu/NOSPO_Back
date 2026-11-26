package kr.nospo.book.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import kr.nospo.book.application.SearchHistoryService;
import kr.nospo.book.contract.Page;
import kr.nospo.book.contract.SearchHistory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/search-history")
@Tag(name = "최근 도서 검색")
@SecurityRequirement(name = "bearerAuth")
public class SearchHistoryController {
    private final SearchHistoryService history;

    public SearchHistoryController(SearchHistoryService history) {
        this.history = history;
    }

    @GetMapping
    @Operation(summary = "내 최근 도서 검색 목록", description = "최신 검색 순서로 조회한다. limit은 기본 20, 1~100이다.",
            responses = {@ApiResponse(responseCode = "200", description = "최근 검색 목록", useReturnTypeSchema = true),
                @ApiResponse(responseCode = "400", description = "잘못된 파라미터 또는 커서"),
                @ApiResponse(responseCode = "401", description = "인증 필요"),
                @ApiResponse(responseCode = "403", description = "약관 재동의 필요"),
                @ApiResponse(responseCode = "422", description = "limit 범위 오류")})
    public kr.nospo.support.ApiResponse<Page<SearchHistory>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        return kr.nospo.support.ApiResponse.of(history.list(owner(jwt), cursor, BookRequest.limit(limit)));
    }

    @DeleteMapping("/{historyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "내 최근 도서 검색 단건 삭제", description = "본인 소유의 이력 UUID 한 건을 삭제한다.", responses = {
            @ApiResponse(responseCode = "204", description = "삭제 완료"),
            @ApiResponse(responseCode = "400", description = "잘못된 UUID"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "403", description = "약관 재동의 또는 Origin 제한"),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")})
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String historyId) {
        history.delete(owner(jwt), BookRequest.id(historyId));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "내 최근 도서 검색 전체 삭제", description = "인증된 회원의 모든 도서 검색 이력을 삭제한다.", responses = {
            @ApiResponse(responseCode = "204", description = "삭제 완료"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "403", description = "약관 재동의 또는 Origin 제한")})
    public void deleteAll(@AuthenticationPrincipal Jwt jwt) {
        history.deleteAll(owner(jwt));
    }

    private static UUID owner(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
