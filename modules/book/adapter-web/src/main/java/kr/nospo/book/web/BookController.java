package kr.nospo.book.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import kr.nospo.book.application.BookService;
import kr.nospo.book.application.SearchHistoryService;
import kr.nospo.book.contract.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/books")
@Tag(name = "도서")
public class BookController {
    private final BookService books;
    private final SearchHistoryService history;

    public BookController(BookService books, SearchHistoryService history) {
        this.books = books;
        this.history = history;
    }

    @GetMapping("/search")
    @Operation(summary = "도서 검색", description = "제목·저자·ISBN으로 판본을 검색한다. limit은 기본 20, 1~100이다. 외부 도서 검색 커서는 일시적으로 유효하다.",
            responses = {@ApiResponse(responseCode = "200", description = "검색 결과", useReturnTypeSchema = true),
                @ApiResponse(responseCode = "400", description = "잘못된 파라미터 또는 커서"),
                @ApiResponse(responseCode = "422", description = "검색어 길이 또는 limit 범위 오류"),
                @ApiResponse(responseCode = "503", description = "BOOK_PROVIDER_UNAVAILABLE")})
    public kr.nospo.support.ApiResponse<Page<BookSummary>> search(
            @RequestParam String query, @RequestParam SearchType type,
            @RequestParam(required = false) BookType bookType,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit,
            @AuthenticationPrincipal Jwt jwt) {
        String normalized = BookRequest.query(query);
        var page = books.search(normalized, type, bookType, cursor, BookRequest.limit(limit));
        if (jwt != null) {
            history.record(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")),
                    normalized, type);
        }
        return kr.nospo.support.ApiResponse.of(page);
    }

    @GetMapping("/{bookId}")
    @Operation(summary = "도서 판본 상세", description = "내부 판본 UUID로 서지와 독서 단위를 조회한다. 외부 검색 결과의 상세 조회는 일시적으로 제공된다.", responses = {
            @ApiResponse(responseCode = "200", description = "판본 상세", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "잘못된 UUID"),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")})
    public kr.nospo.support.ApiResponse<Book> detail(@PathVariable String bookId) {
        return kr.nospo.support.ApiResponse.of(books.detail(BookRequest.id(bookId)));
    }

    @GetMapping("/{bookId}/structure")
    @Operation(summary = "웹소설 회차 조회", description = "전역 회차 순서로 조회한다. limit은 기본 20, 1~100이다.",
            responses = {@ApiResponse(responseCode = "200", description = "회차 목록", useReturnTypeSchema = true),
                @ApiResponse(responseCode = "400", description = "잘못된 파라미터 또는 커서"),
                @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND"),
                @ApiResponse(responseCode = "409", description = "BOOK_TYPE_MISMATCH"),
                @ApiResponse(responseCode = "422", description = "limit 범위 오류")})
    public kr.nospo.support.ApiResponse<Page<Episode>> structure(@PathVariable String bookId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        return kr.nospo.support.ApiResponse.of(books.structure(BookRequest.id(bookId), cursor, BookRequest.limit(limit)));
    }
}
