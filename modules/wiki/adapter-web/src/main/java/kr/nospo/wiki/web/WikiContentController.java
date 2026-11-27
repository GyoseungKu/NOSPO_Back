package kr.nospo.wiki.web;

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
    ApiResponse<?> info(Principal principal, @PathVariable UUID bookId,
                        @RequestParam(required = false) Integer positionValue) {
        return ApiResponse.of(wiki.info(member(principal), bookId, positionValue));
    }

    @GetMapping("/books/{bookId}/wiki/{kind:characters|organizations}")
    ApiResponse<?> list(Principal principal, @PathVariable UUID bookId, @PathVariable String kind,
                        @RequestParam(required = false) Integer positionValue,
                        @RequestParam(required = false) UUID organizationId, @RequestParam(required = false) String cursor,
                        @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(wiki.list(member(principal), bookId, positionValue, kind,
                "characters".equals(kind) ? organizationId : null, cursor, limit));
    }

    @GetMapping("/books/{bookId}/wiki/{kind:characters|organizations}/{entityId}")
    ApiResponse<?> entity(Principal principal, @PathVariable UUID bookId, @PathVariable String kind,
                          @PathVariable UUID entityId, @RequestParam(required = false) Integer positionValue) {
        return ApiResponse.of(wiki.entity(member(principal), bookId, positionValue, kind, entityId));
    }

    @GetMapping("/books/{bookId}/wiki/relations")
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
    ResponseEntity<?> addEntry(Principal principal, @PathVariable UUID draftId, @PathVariable String kind,
            @PathVariable UUID entityId, @RequestHeader(name = "If-Match", required = false) String tag,
            @RequestBody EntryRequest input) {
        return edited(HttpStatus.CREATED, entities.entry(member(principal), draftId, tag, kind, entityId, null, input.parse()));
    }

    @PutMapping(ENTRIES + "/{entryId}")
    ResponseEntity<?> replaceEntry(Principal principal, @PathVariable UUID draftId, @PathVariable String kind,
            @PathVariable UUID entityId, @PathVariable UUID entryId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody EntryRequest input) {
        return edited(HttpStatus.OK, entities.entry(member(principal), draftId, tag, kind, entityId, entryId, input.parse()));
    }

    @DeleteMapping(ENTRIES + "/{entryId}")
    ResponseEntity<?> deleteEntry(Principal principal, @PathVariable UUID draftId, @PathVariable String kind,
            @PathVariable UUID entityId, @PathVariable UUID entryId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, entities.entry(member(principal), draftId, tag, kind, entityId, entryId, null));
    }

    @PostMapping(RELATIONS)
    ResponseEntity<?> addRelation(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody RelationRequest input) {
        return edited(HttpStatus.CREATED, entities.relation(member(principal), draftId, tag, null, input.parse()));
    }

    @PutMapping(RELATIONS + "/{relationId}")
    ResponseEntity<?> replaceRelation(Principal principal, @PathVariable UUID draftId, @PathVariable UUID relationId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestBody RelationRequest input) {
        return edited(HttpStatus.OK, entities.relation(member(principal), draftId, tag, relationId, input.parse()));
    }

    @DeleteMapping(RELATIONS + "/{relationId}")
    ResponseEntity<?> deleteRelation(Principal principal, @PathVariable UUID draftId, @PathVariable UUID relationId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, entities.relation(member(principal), draftId, tag, relationId, null));
    }

    @PostMapping(value = "/wiki/drafts/{draftId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(Principal principal, @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String tag, @RequestParam("file") List<MultipartFile> files)
            throws IOException {
        if (files.size() != 1 || files.getFirst().isEmpty()) throw new ApiException(400, "INVALID_REQUEST");
        return edited(HttpStatus.CREATED, assets.upload(member(principal), draftId, tag, files.getFirst().getBytes()));
    }

    @DeleteMapping("/wiki/drafts/{draftId}/assets/{assetId}")
    ResponseEntity<?> remove(Principal principal, @PathVariable UUID draftId, @PathVariable UUID assetId,
            @RequestHeader(name = "If-Match", required = false) String tag) {
        return edited(HttpStatus.NO_CONTENT, assets.remove(member(principal), draftId, tag, assetId));
    }

    @GetMapping(value = "/wiki/assets/{assetId}", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> image(Principal principal, @PathVariable UUID assetId,
                                 @RequestParam(required = false) Integer positionValue) {
        var asset = assets.image(member(principal), assetId, positionValue);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(asset.bytes());
    }
}
