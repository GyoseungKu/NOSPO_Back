package kr.nospo.wiki.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import kr.nospo.support.ApiErrors;
import kr.nospo.support.ApiException;
import kr.nospo.support.ApiResponse;
import kr.nospo.wiki.application.WikiBookAccess.Position;
import kr.nospo.wiki.application.WikiBookAccess.Unit;
import kr.nospo.wiki.application.WikiStore.Mode;
import kr.nospo.wiki.application.WikiDrafts;
import kr.nospo.wiki.application.WikiDrafts.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import static kr.nospo.wiki.application.WikiDrafts.etag;

@RestController
@Tag(name = "위키 초안과 편집")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1")
class WikiDraftController {
    private final WikiDrafts drafts;
    private final tools.jackson.databind.ObjectMapper json;

    WikiDraftController(WikiDrafts drafts, tools.jackson.databind.ObjectMapper json) {
        this.drafts = drafts;
        this.json = json;
    }

    record PositionValue(String unit, JsonNode value) {
        Position parse() {
            if (unit == null || value == null || !value.isIntegralNumber() || !value.canConvertToInt())
                throw new ApiException(422, "INVALID_POSITION");
            try {
                return new Position(Unit.valueOf(unit), value.intValue());
            } catch (IllegalArgumentException error) {
                throw new ApiException(422, "INVALID_POSITION");
            }
        }
    }

    record DraftInput(UUID bookId, Mode mode, PositionValue position, UUID wikiId, Integer baseWikiVersion) {}
    record PositionInput(PositionValue position) {}
    record CharacterInput(PositionValue firstRevealAt, JsonNode profile) {}
    record AppearanceInput(PositionValue firstRevealAt) {}
    record ProfileRequest(String name, String gender, String personality, String description, String role,
                          UUID imageAssetId, PositionValue revealAt) {
        ProfileInput parse() {
            return new ProfileInput(name, gender, personality, description, role, imageAssetId, position(revealAt));
        }
    }

    @PostMapping("/wiki/drafts")
    @Operation(summary = "위키 초안 생성", description = "신규 초안을 만들거나 지정한 공개 버전을 작성 지점 범위에서 복제합니다.")
    public ResponseEntity<ApiResponse<Detail>> create(Principal principal, @RequestBody DraftInput input) {
        var result = drafts.create(member(principal), new Create(input.bookId(), input.mode(),
                position(input.position()), input.wikiId(), input.baseWikiVersion()));
        return response(HttpStatus.CREATED, result, result.revision());
    }

    @GetMapping("/me/wiki/drafts")
    @Operation(summary = "내 위키 초안 목록", description = "편집 가능한 본인 초안을 판본 기준으로 필터링하고 페이지 단위로 조회합니다.")
    public ApiResponse<Page> list(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(drafts.list(member(principal), bookId, cursor, limit));
    }

    @GetMapping("/wiki/drafts/{draftId}")
    @Operation(summary = "위키 초안 상세", description = "본인 초안과 현재 편집 버전 ETag를 반환합니다.")
    public ResponseEntity<ApiResponse<Detail>> detail(Principal principal, @PathVariable UUID draftId) {
        var result = drafts.detail(member(principal), draftId);
        return response(HttpStatus.OK, result, result.revision());
    }

    @PatchMapping("/wiki/drafts/{draftId}")
    @Operation(summary = "위키 초안 작성 지점 변경", description = "현재 진도 안에서 작성 지점을 변경하고 범위를 벗어나는 항목을 알려줍니다. If-Match가 필요합니다.")
    public ResponseEntity<?> changePosition(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody PositionInput input) {
        var result = drafts.changePosition(member(principal), draftId, tag, position(input.position()));
        return response(HttpStatus.OK, result, result.revision());
    }

    @DeleteMapping("/wiki/drafts/{draftId}")
    @Operation(summary = "위키 초안 삭제", description = "편집 가능한 본인 초안을 삭제합니다. If-Match가 필요합니다.")
    public ResponseEntity<Void> delete(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        drafts.delete(member(principal), draftId, tag);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}")
    @Operation(summary = "위키 초안 인물·단체 추가", description = "최초 등장 지점과 최초 프로필을 저장합니다. If-Match가 필요합니다.")
    public ResponseEntity<?> addCharacter(Principal principal, @PathVariable String kind, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody CharacterInput input) {
        return edited(HttpStatus.CREATED,
                drafts.addCharacter(member(principal), draftId, tag, position(input.firstRevealAt()),
                        parseProfile(input.profile(), kind), kind));
    }

    @GetMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}")
    @Operation(summary = "위키 초안 인물·단체 조회", description = "본인 초안의 인물 또는 단체 편집 정보를 조회합니다.")
    public ApiResponse<?> character(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId) {
        return ApiResponse.of(drafts.character(member(principal), draftId, entityId, kind));
    }

    @PatchMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}")
    @Operation(summary = "위키 초안 최초 등장 지점 변경", description = "연결 정보의 공개 지점과 충돌하지 않는 범위에서 변경합니다. If-Match가 필요합니다.")
    public ResponseEntity<?> moveCharacter(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody AppearanceInput input) {
        return edited(HttpStatus.OK,
                drafts.moveCharacter(member(principal), draftId, tag, entityId, position(input.firstRevealAt()), kind));
    }

    @DeleteMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}")
    @Operation(summary = "위키 초안 인물·단체 삭제", description = "초안의 인물 또는 단체와 연결된 관계를 삭제합니다. If-Match가 필요합니다.")
    public ResponseEntity<?> deleteCharacter(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, drafts.deleteCharacter(member(principal), draftId, tag, entityId, kind));
    }

    @PostMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}/profiles")
    @Operation(summary = "위키 초안 프로필 추가", description = "기존 프로필과 다른 공개 지점의 프로필을 추가합니다. If-Match가 필요합니다.")
    public ResponseEntity<?> addProfile(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody JsonNode input) {
        return edited(HttpStatus.CREATED, drafts.saveProfile(member(principal), draftId, tag, entityId, null, parseProfile(input, kind), kind));
    }

    @PutMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}/profiles/{profileId}")
    @Operation(summary = "위키 초안 프로필 교체", description = "지정한 프로필의 전체 내용을 교체합니다. If-Match가 필요합니다.")
    public ResponseEntity<?> replaceProfile(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @PathVariable UUID profileId, @RequestHeader(name = "If-Match", required = false) String tag,
            @RequestBody JsonNode input) {
        return edited(HttpStatus.OK, drafts.saveProfile(member(principal), draftId, tag, entityId, profileId, parseProfile(input, kind), kind));
    }

    @DeleteMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}/profiles/{profileId}")
    @Operation(summary = "위키 초안 프로필 삭제", description = "최초 프로필을 유지하며 지정한 프로필을 삭제합니다. If-Match가 필요합니다.")
    public ResponseEntity<?> deleteProfile(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @PathVariable UUID profileId, @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, drafts.deleteProfile(member(principal), draftId, tag, entityId, profileId, kind));
    }

    @ExceptionHandler(PositionConflict.class)
    ResponseEntity<?> positionConflict(PositionConflict conflict) {
        return ResponseEntity.status(conflict.status()).body(Map.of(
                "error", Map.of("code", conflict.code(), "message", "작성 지점보다 뒤에 공개되는 항목이 있습니다.",
                        "details", List.of(Map.of("ids", conflict.ids()))),
                "meta", ApiErrors.body(conflict.code()).get("meta")));
    }

    private ProfileInput parseProfile(JsonNode input, String kind) {
        if (input == null || !input.isObject() || ("organizations".equals(kind)
                && (input.has("gender") || input.has("personality")))) throw new ApiException(422, "VALIDATION_ERROR");
        return json.treeToValue(input, ProfileRequest.class).parse();
    }

    static Position position(PositionValue input) {
        return input == null ? null : input.parse();
    }

    static UUID member(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    static ResponseEntity<?> edited(HttpStatus status, Edit result) {
        if (status == HttpStatus.NO_CONTENT) return ResponseEntity.noContent().eTag(etag(result.revision())).build();
        return response(status, result.data(), result.revision());
    }

    static <T> ResponseEntity<ApiResponse<T>> response(HttpStatus status, T body, long revision) {
        return ResponseEntity.status(status).eTag(etag(revision)).body(ApiResponse.of(body));
    }
}
