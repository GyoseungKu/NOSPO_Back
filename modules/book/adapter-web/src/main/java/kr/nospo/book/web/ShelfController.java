package kr.nospo.book.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import kr.nospo.book.application.ShelfService;
import kr.nospo.book.contract.*;
import kr.nospo.support.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/me/shelf")
@Tag(name = "내 서재", description = "활성 JWT 세션과 필수 약관 재동의가 필요합니다.")
@SecurityRequirement(name = "bearerAuth")
public class ShelfController {
    private static final String SHELF_EXAMPLE = """
            {"data":{"book":{"bookId":"a01f1d26-5597-4ac4-889b-e60634a2ea34","workId":null,"bookType":"BOOK",
            "title":"예시 도서","authors":[],"publisher":null,"editionLabel":null,"isbn":null,"coverUrl":null},
            "currentPosition":{"unit":"PAGE","value":20},"highestPosition":{"unit":"PAGE","value":20},
            "positionContext":{"volumeLabel":null,"chapterLabel":null,"episodeLabel":null},
            "progressPercent":null,"addedAt":"2026-11-26T03:00:00Z","lastProgressAt":null},
            "meta":{"requestId":"e60f757c-aac7-4fdc-ad95-f77af366faba"}}
            """;
    private final ShelfService shelf;

    public ShelfController(ShelfService shelf) {
        this.shelf = shelf;
    }

    @GetMapping
    @Operation(summary = "내 서재 목록", description = "유형과 정렬 조건으로 본인 서재를 조회합니다. "
            + "기본 RECENT_ACTIVITY는 마지막 진도 변경 또는 등록 시각 순입니다. 커서는 15분간 회원·유형·정렬·limit에 결합됩니다.",
            responses = {@ApiResponse(responseCode = "200", description = "서재 목록", useReturnTypeSchema = true),
                @ApiResponse(responseCode = "400", description = "잘못된 파라미터 또는 INVALID_CURSOR"),
                @ApiResponse(responseCode = "401", description = "인증 또는 세션 오류"),
                @ApiResponse(responseCode = "403", description = "TERMS_RECONSENT_REQUIRED"),
                @ApiResponse(responseCode = "422", description = "limit 허용 범위 초과")})
    public kr.nospo.support.ApiResponse<Page<ShelfItem>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) BookType bookType,
            @RequestParam(defaultValue = "RECENT_ACTIVITY") ShelfSort sort,
            @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String limit) {
        return kr.nospo.support.ApiResponse.of(shelf.list(owner(jwt), bookType, sort, cursor, BookRequest.limit(limit)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "내 서재에 판본 등록", description = "최초 등록은 독서량에 추가되지 않습니다. "
            + "카카오 임시 ID는 유효한 캐시와 같은 출처·ISBN의 승인 판본이 있을 때 연결됩니다. "
            + "캐시를 찾을 수 없으면 다시 검색하세요. 승인 판본이 없으면 현재 저장 정책에 따라 404로 실패합니다. "
            + "이후 상세·진도 조회에는 201 응답의 book.bookId를 사용하세요. 활성 세션과 필수 약관 동의가 필요합니다.",
            responses = {@ApiResponse(responseCode = "201", description = "등록 완료", useReturnTypeSchema = true,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = SHELF_EXAMPLE))),
                @ApiResponse(responseCode = "400", description = "잘못된 JSON 또는 판본 UUID"),
                @ApiResponse(responseCode = "401", description = "인증 또는 세션 오류"),
                @ApiResponse(responseCode = "403", description = "재동의 또는 Origin 제한"),
                @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND. 임시 캐시가 없거나 승인 판본을 확인할 수 없음"),
                @ApiResponse(responseCode = "409", description = "SHELF_ALREADY_EXISTS 또는 쓰기 경합"),
                @ApiResponse(responseCode = "422", description = "INVALID_POSITION")})
    public kr.nospo.support.ApiResponse<ShelfItem> add(@AuthenticationPrincipal Jwt jwt,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = AddRequest.class), examples = {
                        @ExampleObject(name = "BOOK", value = """
                                {"bookId":"a01f1d26-5597-4ac4-889b-e60634a2ea34","position":{"unit":"PAGE","value":20}}
                                """),
                        @ExampleObject(name = "WEB_NOVEL", value = """
                                {"bookId":"a94e0120-ccbe-4834-b747-3f9e431c98af","position":{"unit":"EPISODE","value":2}}
                                """)}))
            @RequestBody AddRequest input) {
        if (input.bookId() == null) throw new ApiException(400, "INVALID_REQUEST");
        return kr.nospo.support.ApiResponse.of(
                shelf.add(owner(jwt), session(jwt), BookRequest.id(input.bookId()), input.validatedPosition()));
    }

    @GetMapping("/{bookId}")
    @Operation(summary = "내 서재 판본 상세", description = "본인 서재 연결의 현재·최고 지점 및 회차 정보를 조회합니다.",
            responses = {@ApiResponse(responseCode = "200", description = "서재 판본", useReturnTypeSchema = true),
                @ApiResponse(responseCode = "400", description = "잘못된 판본 UUID"),
                @ApiResponse(responseCode = "401", description = "인증 또는 세션 오류"),
                @ApiResponse(responseCode = "403", description = "TERMS_RECONSENT_REQUIRED"),
                @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")})
    public kr.nospo.support.ApiResponse<ShelfItem> detail(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String bookId) {
        return kr.nospo.support.ApiResponse.of(shelf.detail(owner(jwt), BookRequest.id(bookId)));
    }

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "내 서재에서 판본 제거", description = "서재 연결을 삭제하고 공용 판본과 개인 진도·기록은 보존합니다.",
            responses = {@ApiResponse(responseCode = "204", description = "제거 완료"),
                @ApiResponse(responseCode = "400", description = "잘못된 판본 UUID"),
                @ApiResponse(responseCode = "401", description = "인증 또는 세션 오류"),
                @ApiResponse(responseCode = "403", description = "재동의 또는 Origin 제한"),
                @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")})
    public void remove(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String bookId) {
        shelf.remove(owner(jwt), session(jwt), BookRequest.id(bookId));
    }

    private static UUID owner(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static UUID session(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }

    public record AddRequest(
            @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String bookId,
            @Schema(implementation = Position.class, requiredMode = Schema.RequiredMode.REQUIRED) JsonNode position) {
        Position validatedPosition() {
            return BookRequest.position(position);
        }
    }
}
