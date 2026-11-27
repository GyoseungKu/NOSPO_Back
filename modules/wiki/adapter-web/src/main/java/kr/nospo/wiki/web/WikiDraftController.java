package kr.nospo.wiki.web;

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
    public ResponseEntity<ApiResponse<Detail>> create(Principal principal, @RequestBody DraftInput input) {
        var result = drafts.create(member(principal), new Create(input.bookId(), input.mode(),
                position(input.position()), input.wikiId(), input.baseWikiVersion()));
        return response(HttpStatus.CREATED, result, result.revision());
    }

    @GetMapping("/me/wiki/drafts")
    public ApiResponse<Page> list(Principal principal, @RequestParam(required = false) UUID bookId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(drafts.list(member(principal), bookId, cursor, limit));
    }

    @GetMapping("/wiki/drafts/{draftId}")
    public ResponseEntity<ApiResponse<Detail>> detail(Principal principal, @PathVariable UUID draftId) {
        var result = drafts.detail(member(principal), draftId);
        return response(HttpStatus.OK, result, result.revision());
    }

    @PatchMapping("/wiki/drafts/{draftId}")
    public ResponseEntity<?> changePosition(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody PositionInput input) {
        var result = drafts.changePosition(member(principal), draftId, tag, position(input.position()));
        return response(HttpStatus.OK, result, result.revision());
    }

    @DeleteMapping("/wiki/drafts/{draftId}")
    public ResponseEntity<Void> delete(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        drafts.delete(member(principal), draftId, tag);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}")
    public ResponseEntity<?> addCharacter(Principal principal, @PathVariable String kind, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody CharacterInput input) {
        return edited(HttpStatus.CREATED,
                drafts.addCharacter(member(principal), draftId, tag, position(input.firstRevealAt()),
                        parseProfile(input.profile(), kind), kind));
    }

    @GetMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}")
    public ApiResponse<?> character(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId) {
        return ApiResponse.of(drafts.character(member(principal), draftId, entityId, kind));
    }

    @PatchMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}")
    public ResponseEntity<?> moveCharacter(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody AppearanceInput input) {
        return edited(HttpStatus.OK,
                drafts.moveCharacter(member(principal), draftId, tag, entityId, position(input.firstRevealAt()), kind));
    }

    @DeleteMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}")
    public ResponseEntity<?> deleteCharacter(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, drafts.deleteCharacter(member(principal), draftId, tag, entityId, kind));
    }

    @PostMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}/profiles")
    public ResponseEntity<?> addProfile(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody JsonNode input) {
        return edited(HttpStatus.CREATED, drafts.saveProfile(member(principal), draftId, tag, entityId, null, parseProfile(input, kind), kind));
    }

    @PutMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}/profiles/{profileId}")
    public ResponseEntity<?> replaceProfile(Principal principal, @PathVariable String kind, @PathVariable UUID draftId, @PathVariable UUID entityId,
            @PathVariable UUID profileId, @RequestHeader(name = "If-Match", required = false) String tag,
            @RequestBody JsonNode input) {
        return edited(HttpStatus.OK, drafts.saveProfile(member(principal), draftId, tag, entityId, profileId, parseProfile(input, kind), kind));
    }

    @DeleteMapping("/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}/profiles/{profileId}")
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
