package kr.nospo.wiki.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import kr.nospo.support.ApiException;
import kr.nospo.support.ApiResponse;
import kr.nospo.wiki.application.WikiAssets;
import kr.nospo.wiki.application.WikiEntities;
import kr.nospo.wiki.application.WikiPublic;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static kr.nospo.wiki.web.WikiDraftController.*;

@RestController
@Tag(name = "위키 콘텐츠")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1")
class WikiContentController {
    private final WikiPublic wiki;
    private final WikiEntities entities;
    private final WikiAssets assets;

    WikiContentController(WikiPublic wiki, WikiEntities entities, WikiAssets assets) {
        this.wiki = wiki;
        this.entities = entities;
        this.assets = assets;
    }

    @GetMapping("/books/{bookId}/wiki")
    @Operation(summary = "판본 위키 존재 여부", description = "현재 서재 진도 범위에서 승인 위키의 존재 여부와 버전을 확인합니다.")
    ApiResponse<?> info(Principal principal, @PathVariable UUID bookId,
                        @RequestParam(required = false) Integer positionValue) {
        return ApiResponse.of(wiki.info(member(principal), bookId, positionValue));
    }

    @GetMapping("/books/{bookId}/wiki/{kind:characters|organizations}")
    @Operation(summary = "공개 인물·단체 목록", description = "조회 지점에서 공개된 인물 또는 단체를 페이지 단위로 반환합니다.")
    ApiResponse<?> list(Principal principal, @PathVariable UUID bookId, @PathVariable String kind,
                        @RequestParam(required = false) Integer positionValue,
                        @RequestParam(required = false) UUID organizationId, @RequestParam(required = false) String cursor,
                        @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(wiki.list(member(principal), bookId, positionValue, kind,
                "characters".equals(kind) ? organizationId : null, cursor, limit));
    }

    @GetMapping("/books/{bookId}/wiki/{kind:characters|organizations}/{entityId}")
    @Operation(summary = "공개 인물·단체 상세", description = "선택 지점에서 공개된 프로필, 세부 항목과 관계를 반환합니다.")
    ApiResponse<?> entity(Principal principal, @PathVariable UUID bookId, @PathVariable String kind,
                          @PathVariable UUID entityId, @RequestParam(required = false) Integer positionValue) {
        return ApiResponse.of(wiki.entity(member(principal), bookId, positionValue, kind, entityId));
    }

    @GetMapping("/books/{bookId}/wiki/relations")
    @Operation(summary = "공개 위키 관계 목록", description = "양쪽 대상이 공개된 관계 중 조회 조건을 만족하는 관계를 반환합니다.")
    ApiResponse<?> relations(Principal principal, @PathVariable UUID bookId,
            @RequestParam(required = false) Integer positionValue, @RequestParam(required = false) String type,
            @RequestParam(required = false) String entityType, @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(wiki.relations(member(principal), bookId, positionValue,
                type, entityType, entityId, direction, cursor, limit));
    }

    private static final String ENTRIES = "/wiki/drafts/{draftId}/{kind:characters|organizations}/{entityId}/entries";
    private static final String RELATIONS = "/wiki/drafts/{draftId}/relations";

    record EntryRequest(String type, String title, String content, PositionValue revealAt, PositionValue sourcePosition) {
        WikiEntities.Entry parse() {
            return new WikiEntities.Entry(type, title, content, position(revealAt), position(sourcePosition));
        }
    }

    record EventRequest(String kind, String type, String description, PositionValue revealAt, PositionValue sourcePosition) {
        WikiEntities.Event parse() {
            return new WikiEntities.Event(kind, type, description, position(revealAt), position(sourcePosition));
        }
    }

    record RelationRequest(WikiEntities.Target from, WikiEntities.Target to, String type, String description,
                           PositionValue revealAt, PositionValue sourcePosition, List<EventRequest> events) {
        WikiEntities.Relation parse() {
            return new WikiEntities.Relation(from, to, type, description, position(revealAt), position(sourcePosition),
                    events == null ? null : events.stream().map(event -> event == null ? null : event.parse()).toList());
        }
    }

    @PostMapping(ENTRIES)
    @Operation(summary = "위키 초안 세부 항목 추가", description = "인물 또는 단체에 공개 지점과 출처가 있는 항목을 추가합니다. If-Match가 필요합니다.")
    ResponseEntity<?> addEntry(Principal principal, @PathVariable UUID draftId, @PathVariable String kind,
            @PathVariable UUID entityId, @RequestHeader(name = "If-Match", required = false) String tag,
            @RequestBody EntryRequest input) {
        return edited(HttpStatus.CREATED, entities.entry(member(principal), draftId, tag, kind, entityId, null, input.parse()));
    }

    @PutMapping(ENTRIES + "/{entryId}")
    @Operation(summary = "위키 초안 세부 항목 교체", description = "지정한 항목의 내용을 전체 교체합니다. If-Match가 필요합니다.")
    ResponseEntity<?> replaceEntry(Principal principal, @PathVariable UUID draftId, @PathVariable String kind,
            @PathVariable UUID entityId, @PathVariable UUID entryId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody EntryRequest input) {
        return edited(HttpStatus.OK, entities.entry(member(principal), draftId, tag, kind, entityId, entryId, input.parse()));
    }

    @DeleteMapping(ENTRIES + "/{entryId}")
    @Operation(summary = "위키 초안 세부 항목 삭제", description = "인물 또는 단체의 지정한 세부 항목을 삭제합니다. If-Match가 필요합니다.")
    ResponseEntity<?> deleteEntry(Principal principal, @PathVariable UUID draftId, @PathVariable String kind,
            @PathVariable UUID entityId, @PathVariable UUID entryId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, entities.entry(member(principal), draftId, tag, kind, entityId, entryId, null));
    }

    @PostMapping(RELATIONS)
    @Operation(summary = "위키 초안 관계 추가", description = "같은 초안 안의 두 대상 사이 관계와 변화 이력을 추가합니다. If-Match가 필요합니다.")
    ResponseEntity<?> addRelation(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody RelationRequest input) {
        return edited(HttpStatus.CREATED, entities.relation(member(principal), draftId, tag, null, input.parse()));
    }

    @PutMapping(RELATIONS + "/{relationId}")
    @Operation(summary = "위키 초안 관계 교체", description = "지정한 관계의 방향, 공개 지점 및 변화 이력을 전체 교체합니다. If-Match가 필요합니다.")
    ResponseEntity<?> replaceRelation(Principal principal, @PathVariable UUID draftId, @PathVariable UUID relationId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody RelationRequest input) {
        return edited(HttpStatus.OK, entities.relation(member(principal), draftId, tag, relationId, input.parse()));
    }

    @DeleteMapping(RELATIONS + "/{relationId}")
    @Operation(summary = "위키 초안 관계 삭제", description = "지정한 관계와 변화 이력을 삭제합니다. If-Match가 필요합니다.")
    ResponseEntity<?> deleteRelation(Principal principal, @PathVariable UUID draftId, @PathVariable UUID relationId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, entities.relation(member(principal), draftId, tag, relationId, null));
    }

    @PostMapping(value = "/wiki/drafts/{draftId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "위키 초안 이미지 업로드", description = "정지 이미지 파일을 검증한 뒤 PNG로 저장하고 자산 ID를 반환합니다. If-Match가 필요합니다.")
    ResponseEntity<?> upload(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestParam("file") List<MultipartFile> files)
            throws IOException {
        if (files.size() != 1 || files.getFirst().isEmpty()) throw new ApiException(400, "INVALID_REQUEST");
        return edited(HttpStatus.CREATED, assets.upload(member(principal), draftId, tag, files.getFirst().getBytes()));
    }

    @DeleteMapping("/wiki/drafts/{draftId}/assets/{assetId}")
    @Operation(summary = "위키 초안 이미지 삭제", description = "프로필이 참조하지 않는 초안 이미지의 연결을 삭제합니다. If-Match가 필요합니다.")
    ResponseEntity<?> remove(Principal principal, @PathVariable UUID draftId, @PathVariable UUID assetId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, assets.remove(member(principal), draftId, tag, assetId));
    }

    @GetMapping(value = "/wiki/assets/{assetId}", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "위키 이미지 조회", description = "초안 소유권, 관리자 권한 또는 공개 범위가 확인된 이미지의 PNG를 반환합니다.")
    ResponseEntity<byte[]> image(Principal principal, @PathVariable UUID assetId,
                                 @RequestParam(required = false) Integer positionValue) {
        var asset = assets.image(member(principal), assetId, positionValue);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(asset.bytes());
    }
}
