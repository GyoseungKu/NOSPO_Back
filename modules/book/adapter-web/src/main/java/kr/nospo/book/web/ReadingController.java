package kr.nospo.book.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.UUID;
import kr.nospo.book.application.ProgressTags;
import kr.nospo.book.application.ReadingRecordService;
import kr.nospo.book.application.ShelfService;
import kr.nospo.book.contract.*;
import kr.nospo.support.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "독서 진도와 기록")
@SecurityRequirement(name = "bearerAuth")
public class ReadingController {
    private static final String PROGRESS = "/shelf/{bookId}/progress";
    private final ShelfService shelf;
    private final ReadingRecordService records;
    private final ProgressTags tags;

    public ReadingController(ShelfService shelf, ReadingRecordService records, ProgressTags tags) {
        this.shelf = shelf;
        this.records = records;
        this.tags = tags;
    }

    @GetMapping(PROGRESS)
    @Operation(summary = "내 독서 진도 조회", description = "필수 약관에 동의한 본인의 활성 서재 진도와 현재 ETag를 반환합니다.",
            responses = {
                @ApiResponse(responseCode = "200", description = "현재 진도", useReturnTypeSchema = true,
                    headers = @Header(name = "ETag", description = "회원·판본·revision에 결합된 강한 ETag")),
                @ApiResponse(responseCode = "400", description = "잘못된 UUID"),
                @ApiResponse(responseCode = "401", description = "인증 또는 세션 오류"),
                @ApiResponse(responseCode = "403", description = "TERMS_RECONSENT_REQUIRED"),
                @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")})
    public ResponseEntity<kr.nospo.support.ApiResponse<Progress>> get(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String bookId) {
        UUID owner = owner(jwt);
        UUID edition = BookRequest.id(bookId);
        var snapshot = shelf.activeProgress(owner, edition)
                .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
        return respond(owner, edition, snapshot.revision(), snapshot.progress());
    }

    @PatchMapping(PROGRESS)
    @Operation(summary = "내 독서 진도 변경", description = "필수 약관 동의 및 현재 ETag가 필요합니다. "
            + "위치가 바뀔 때만 revision과 이동 기록을 추가합니다. 412이면 최신 진도를 다시 조회합니다.",
            parameters = @Parameter(name = "If-Match", in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
                    required = true, description = "GET 응답에서 받은 단일 강한 ETag를 그대로 전송합니다."),
            responses = {
                @ApiResponse(responseCode = "200", description = "갱신 결과", useReturnTypeSchema = true,
                    headers = @Header(name = "ETag", description = "갱신 후 현재 ETag. 동일 위치면 기존 값")),
                @ApiResponse(responseCode = "400", description = "잘못된 UUID, JSON 또는 If-Match 형식"),
                @ApiResponse(responseCode = "401", description = "인증 또는 세션 오류"),
                @ApiResponse(responseCode = "403", description = "재동의 또는 Origin 제한"),
                @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND"),
                @ApiResponse(responseCode = "412", description = "VERSION_MISMATCH"),
                @ApiResponse(responseCode = "422", description = "INVALID_POSITION"),
                @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED")})
    public ResponseEntity<kr.nospo.support.ApiResponse<Change>> patch(
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String bookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = UpdateRequest.class), examples = {
                    @ExampleObject(name = "PAGE", value = "{\"position\":{\"unit\":\"PAGE\",\"value\":20}}"),
                    @ExampleObject(name = "EPISODE", value = "{\"position\":{\"unit\":\"EPISODE\",\"value\":45}}")}))
            @RequestBody UpdateRequest input) {
        UUID owner = owner(jwt);
        UUID edition = BookRequest.id(bookId);
        String ifMatch = tags.requireSingle(Collections.list(request.getHeaders(HttpHeaders.IF_MATCH)));
        var update = shelf.changeProgress(owner, UUID.fromString(jwt.getClaimAsString("sid")), edition,
                ifMatch, BookRequest.position(input.position()));
        var progress = update.snapshot().progress();
        return respond(owner, edition, update.snapshot().revision(),
                new Change(progress.bookId(), progress.currentPosition(), progress.highestPosition(),
                        progress.positionContext(), progress.progressPercent(), progress.updatedAt(),
                        update.addedUnits(), update.recordId()));
    }

    @GetMapping("/reading/records")
    @Operation(summary = "내 독서 기록 조회", description = "필수 약관 동의 후 서재 제거 전 기록도 조회합니다. "
            + "날짜는 Asia/Seoul 기준 양 끝 포함입니다. 두 날짜를 함께 지정하면 최대 366일입니다. "
            + "커서는 회원·필터·limit에 결합되며 15분간 유효합니다.",
            responses = {
                @ApiResponse(responseCode = "200", description = "최신 기록순 목록", useReturnTypeSchema = true),
                @ApiResponse(responseCode = "400", description = "잘못된 날짜·UUID·커서"),
                @ApiResponse(responseCode = "401", description = "인증 또는 세션 오류"),
                @ApiResponse(responseCode = "403", description = "TERMS_RECONSENT_REQUIRED"),
                @ApiResponse(responseCode = "422", description = "날짜 기간 또는 limit 범위 오류")})
    public kr.nospo.support.ApiResponse<Page<ReadingRecord>> records(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "string", format = "date")) @RequestParam(required = false) String from,
            @Parameter(schema = @Schema(type = "string", format = "date")) @RequestParam(required = false) String to,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String limit) {
        return kr.nospo.support.ApiResponse.of(records.list(owner(jwt), date(from), date(to),
                bookId == null ? null : BookRequest.id(bookId), cursor, BookRequest.limit(limit)));
    }

    private <T> ResponseEntity<kr.nospo.support.ApiResponse<T>> respond(UUID owner, UUID book, long revision, T body) {
        return ResponseEntity.ok().eTag(tags.tag(owner, book, revision)).body(kr.nospo.support.ApiResponse.of(body));
    }

    private static LocalDate date(String raw) {
        if (raw == null) return null;
        try {
            if (raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate result = LocalDate.parse(raw);
                if (result.getYear() > 0) return result;
            }
        } catch (DateTimeParseException invalid) {
            throw new ApiException(400, "INVALID_REQUEST");
        }
        throw new ApiException(400, "INVALID_REQUEST");
    }

    private static UUID owner(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record UpdateRequest(
            @Schema(implementation = Position.class, requiredMode = Schema.RequiredMode.REQUIRED) JsonNode position) {}

    public record Change(UUID bookId, Position currentPosition, Position highestPosition, PositionContext positionContext,
            BigDecimal progressPercent, Instant updatedAt, int addedUnits, @Schema(nullable = true) UUID recordId) {}
}
