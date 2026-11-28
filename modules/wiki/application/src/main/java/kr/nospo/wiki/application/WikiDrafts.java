package kr.nospo.wiki.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import kr.nospo.support.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static kr.nospo.wiki.application.WikiBookAccess.Position;
import static kr.nospo.wiki.application.WikiStore.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class WikiDrafts {
    private final WikiStore store;
    private final WikiWorkflowStore workflow;
    private final ObjectProvider<WikiBookAccess> books;
    private final ObjectMapper json;
    private final Clock clock;

    public WikiDrafts(WikiStore store, WikiWorkflowStore workflow, ObjectProvider<WikiBookAccess> books, ObjectMapper json, Clock clock) {
        this.store = store;
        this.workflow = workflow;
        this.books = books;
        this.json = json;
        this.clock = clock;
    }

    public record Create(UUID bookId, Mode mode, Position position, UUID wikiId, Integer baseWikiVersion) {}
    public record ProfileInput(String name, String gender, String personality, String description, String role,
                               UUID imageAssetId, Position revealAt) {}
    public record Summary(UUID draftId, UUID bookId, Mode mode, DraftStatus status, Position position,
                          Integer baseWikiVersion, UUID parentSubmissionId, long revision, Instant updatedAt) {}
    public record Detail(UUID draftId, UUID bookId, Mode mode, DraftStatus status, Position position,
                         Integer baseWikiVersion, UUID parentSubmissionId, long revision, Instant updatedAt,
                         JsonNode characters, JsonNode organizations, JsonNode relations, JsonNode assets, JsonNode conflicts) {}
    public record Page(List<Summary> items, String nextCursor, boolean hasNext) {}
    public record Edit(JsonNode data, long revision) {}

    public static class PositionConflict extends ApiException {
        private final List<String> ids;

        PositionConflict(List<String> ids) {
            super(422, "DRAFT_POSITION_CONFLICT");
            this.ids = List.copyOf(ids);
        }

        public List<String> ids() { return ids; }
    }

    public Detail create(UUID member, Create input) {
        workflow.lockMember(member);
        if (input.bookId() == null || input.mode() == null || input.position() == null) throw invalid();
        Position position = scope().resolve(member, input.bookId(), input.position());
        ObjectNode content = json.createObjectNode();
        if (input.mode() == Mode.CREATE) {
            if (input.wikiId() != null || input.baseWikiVersion() != null) throw invalid();
            if (store.latestVersion(input.bookId()).isPresent())
                throw new ApiException(409, "WIKI_VERSION_CONFLICT");
        } else {
            if (input.wikiId() == null || input.baseWikiVersion() == null || input.baseWikiVersion() < 1) throw invalid();
            var base = store.findVersion(input.bookId(), input.wikiId(), input.baseWikiVersion())
                    .orElseThrow(WikiDrafts::missing);
            content = copy(base.snapshot(), position);
        }
        var draft = new Draft(UUID.randomUUID(), member, input.bookId(), input.mode(), DraftStatus.DRAFT,
                position, input.wikiId(), input.baseWikiVersion(), null, 0, content.toString(), now());
        store.insertDraft(draft);
        activity(draft, "CREATED");
        return detail(draft);
    }

    ObjectNode copy(String payload, Position limit) {
        var content = snapshot(payload);
        restrictCopy(content, limit);
        var ids = new HashMap<String, String>();
        remapDraftIds(content, ids);
        content.set("copiedThrough", json.valueToTree(limit));
        var baseIds = content.putObject("baseIds");
        ids.forEach((original, copied) -> baseIds.put(copied, original));
        return content;
    }

    void activity(Draft draft, String action) {
        workflow.activity(new WikiWorkflowStore.Activity(UUID.randomUUID(), draft.memberId(), draft.bookId(),
                draft.id(), null, action, draft.memberId(), null, null, null, now()));
    }

    public Detail detail(UUID member, UUID id) {
        var draft = find(member, id);
        return detail(draft, readableSnapshot(draft, draft.snapshot()));
    }

    public Page list(UUID member, UUID book, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw invalid();
        var rows = store.drafts(member, book, decode(cursor), limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(WikiDrafts::summary).toList();
        String next = null;
        if (more) {
            Summary last = items.getLast();
            next = encode(last.updatedAt(), last.draftId());
        }
        return new Page(items, next, more);
    }

    public Summary changePosition(UUID member, UUID id, String tag, Position position) {
        workflow.lockMember(member);
        Draft draft = editable(member, id, tag);
        if (position == null) throw invalid();
        scope().resolve(member, draft.bookId(), position);
        var content = snapshot(draft.snapshot());
        var affected = new ArrayList<String>();
        collectConflicts(content, position, null, affected);
        if (!affected.isEmpty()) throw new PositionConflict(affected.stream().distinct().toList());
        return summary(save(draft, position, content));
    }

    public void delete(UUID member, UUID id, String tag) {
        workflow.lockMember(member);
        Draft draft = editable(member, id, tag);
        if (!store.deleteDraft(member, id, draft.revision())) throw mismatch();
    }

    public JsonNode character(UUID member, UUID id, UUID entityId, String kind) {
        var draft = find(member, id);
        return lookup(readableSnapshot(draft, draft.snapshot()).path(kind), "entityId", entityId);
    }

    public Edit addCharacter(UUID member, UUID id, String tag, Position first, ProfileInput input, String kind) {
        return edit(member, id, tag, draft -> {
            var entity = json.createObjectNode();
            entity.put("entityId", UUID.randomUUID().toString());
            entity.put("kind", "characters".equals(kind) ? "CHARACTER" : "ORGANIZATION");
            entity.set("firstRevealAt", json.valueToTree(first));
            entity.putArray("profiles").add(profile(draft, first, input, UUID.randomUUID(), kind));
            entity.putArray("entries");
            if (!first.equals(input.revealAt())) throw invalid();
            snapshotArray(draft.content(), kind).add(entity);
            return entity;
        });
    }

    public Edit moveCharacter(UUID member, UUID id, String tag, UUID entityId, Position first, String kind) {
        return edit(member, id, tag, draft -> {
            var entity = entity(draft, kind, entityId);
            checkPosition(first, draft.position());
            var profiles = snapshotArray(entity, "profiles");
            var previous = position(entity.path("firstRevealAt"));
            for (var profile : profiles) {
                var reveal = position(profile.path("revealAt"));
                if (!previous.equals(reveal) && reveal.value() <= first.value()) throw invalid();
                if (previous.equals(reveal)) ((ObjectNode) profile).set("revealAt", json.valueToTree(first));
            }
            for (var entry : entity.path("entries"))
                if (position(entry.path("revealAt")).value() < first.value()) throw invalid();
            for (var relation : draft.content().path("relations"))
                if (references(relation, entityId) && position(relation.path("revealAt")).value() < first.value())
                    throw invalid();
            entity.set("firstRevealAt", json.valueToTree(first));
            return entity;
        });
    }

    public Edit deleteCharacter(UUID member, UUID id, String tag, UUID entityId, String kind) {
        return edit(member, id, tag, draft -> {
            var characters = snapshotArray(draft.content(), kind);
            characters.remove(indexOf(characters, "entityId", entityId));
            var relations = snapshotArray(draft.content(), "relations");
            for (int i = relations.size() - 1; i >= 0; i--)
                if (references(relations.get(i), entityId)) relations.remove(i);
            return null;
        });
    }

    public Edit saveProfile(UUID member, UUID id, String tag, UUID entityId, UUID profileId, ProfileInput input, String kind) {
        return edit(member, id, tag, draft -> {
            var entity = entity(draft, kind, entityId);
            Position first = position(entity.path("firstRevealAt"));
            var profiles = snapshotArray(entity, "profiles");
            int index = profileId == null ? -1 : indexOf(profiles, "profileId", profileId);
            var updated = profile(draft, first, input, profileId == null ? UUID.randomUUID() : profileId, kind);
            if (index >= 0 && first.equals(position(profiles.get(index).path("revealAt")))
                    && !first.equals(input.revealAt())) throw invalid();
            for (int i = 0; i < profiles.size(); i++)
                if (i != index && input.revealAt().equals(position(profiles.get(i).path("revealAt")))) throw invalid();
            if (index < 0) profiles.add(updated);
            else profiles.set(index, updated);
            return updated;
        });
    }

    public Edit deleteProfile(UUID member, UUID id, String tag, UUID entityId, UUID profileId, String kind) {
        return edit(member, id, tag, draft -> {
            var entity = entity(draft, kind, entityId);
            var profiles = snapshotArray(entity, "profiles");
            int index = indexOf(profiles, "profileId", profileId);
            if (profiles.size() == 1 || entity.path("firstRevealAt").equals(profiles.get(index).path("revealAt")))
                throw invalid();
            profiles.remove(index);
            return null;
        });
    }

    record Editing(Position position, ObjectNode content) {}

    Edit edit(UUID member, UUID id, String tag, Function<Editing, JsonNode> change) {
        workflow.lockMember(member);
        Draft draft = editable(member, id, tag);
        scope().resolve(member, draft.bookId(), draft.position());
        var content = snapshot(draft.snapshot());
        var before = content.path("conflicts").isEmpty() ? null : content.deepCopy();
        JsonNode result = change.apply(new Editing(draft.position(), content));
        if (before != null) {
            var conflicts = snapshotArray(content, "conflicts");
            for (int i = conflicts.size() - 1; i >= 0; i--) {
                String conflict = conflicts.get(i).asString();
                if (!java.util.Objects.equals(findItem(before, conflict), findItem(content, conflict))) conflicts.remove(i);
            }
        }
        return new Edit(result, save(draft, draft.position(), content).revision());
    }

    private static JsonNode findItem(JsonNode node, String id) {
        if (node.isObject()) {
            for (String key : List.of("entityId", "profileId", "entryId", "relationId"))
                if (id.equals(node.path(key).asString())) {
                    if (!"entityId".equals(key)) return node;
                    var entity = ((ObjectNode) node).deepCopy();
                    entity.remove(List.of("profiles", "entries"));
                    return entity;
                }
        }
        if (node.isObject() || node.isArray()) {
            for (var child : node) {
                var result = findItem(child, id);
                if (result != null) return result;
            }
        }
        return null;
    }

    Draft save(Draft draft, Position position, ObjectNode content) {
        var next = new Draft(draft.id(), draft.memberId(), draft.bookId(), draft.mode(), draft.status(), position,
                draft.wikiId(), draft.baseWikiVersion(), draft.parentSubmissionId(), draft.revision() + 1,
                content.toString(), now());
        if (!store.saveDraft(next, draft.revision())) throw mismatch();
        activity(next, "EDITED");
        return next;
    }

    private ObjectNode profile(Editing draft, Position first, ProfileInput input, UUID profileId, String kind) {
        if (input == null) throw invalid();
        checkPosition(first, draft.position());
        checkPosition(input.revealAt(), draft.position());
        if (input.revealAt().value() < first.value() || input.name() == null || input.name().isBlank()
                || input.description() == null || tooLong(input.name(), 100) || tooLong(input.gender(), 50)
                || tooLong(input.personality(), 1000) || tooLong(input.description(), 5000) || tooLong(input.role(), 100))
            throw invalid();
        if ("organizations".equals(kind) && (input.role() != null || input.gender() != null || input.personality() != null))
            throw invalid();
        if (input.imageAssetId() != null) lookup(draft.content().path("assets"), "assetId", input.imageAssetId());
        ObjectNode profile = json.valueToTree(input);
        if ("organizations".equals(kind)) profile.remove(List.of("gender", "personality"));
        return profile.put("profileId", profileId.toString());
    }

    private static boolean tooLong(String text, int max) {
        return text != null && text.codePointCount(0, text.length()) > max;
    }

    static void checkPosition(Position requested, Position ceiling) {
        if (requested == null || requested.unit() != ceiling.unit() || requested.value() < 0
                || requested.value() > ceiling.value()) throw new ApiException(422, "INVALID_POSITION");
    }

    private static boolean references(JsonNode relation, UUID entityId) {
        String id = entityId.toString();
        return id.equals(relation.path("from").path("id").asString())
                || id.equals(relation.path("to").path("id").asString());
    }

    private void collectConflicts(JsonNode node, Position limit, String parentId, List<String> conflicts) {
        if (node.isObject()) {
            String id = parentId;
            for (String key : List.of("entityId", "profileId", "entryId", "relationId"))
                if (node.hasNonNull(key)) id = node.path(key).asString();
            for (String key : List.of("firstRevealAt", "revealAt", "sourcePosition")) {
                if (id != null && node.hasNonNull(key) && position(node.path(key)).value() > limit.value()) conflicts.add(id);
            }
            for (JsonNode child : node) collectConflicts(child, limit, id, conflicts);
        } else if (node.isArray()) {
            for (JsonNode child : node) collectConflicts(child, limit, parentId, conflicts);
        }
    }

    void restrictCopy(ObjectNode content, Position limit) {
        var visibleIds = new java.util.HashSet<String>();
        var imageIds = new java.util.HashSet<String>();
        for (String kind : List.of("characters", "organizations")) {
            var entities = snapshotArray(content, kind);
            for (int i = entities.size() - 1; i >= 0; i--) {
                var entity = (ObjectNode) entities.get(i);
                if (beyond(entity.path("firstRevealAt"), limit)) {
                    entities.remove(i);
                    continue;
                }
                visibleIds.add(entity.path("entityId").asString());
                var profiles = snapshotArray(entity, "profiles");
                removeFuture(profiles, limit);
                removeFuture(snapshotArray(entity, "entries"), limit);
                for (var profile : profiles)
                    if (profile.hasNonNull("imageAssetId")) imageIds.add(profile.path("imageAssetId").asString());
            }
        }
        var relations = snapshotArray(content, "relations");
        removeFuture(relations, limit);
        for (int i = relations.size() - 1; i >= 0; i--) {
            var relation = (ObjectNode) relations.get(i);
            if (!visibleIds.contains(relation.path("from").path("id").asString())
                    || !visibleIds.contains(relation.path("to").path("id").asString())) {
                relations.remove(i);
            } else {
                removeFuture(snapshotArray(relation, "events"), limit);
            }
        }
        var assets = snapshotArray(content, "assets");
        for (int i = assets.size() - 1; i >= 0; i--)
            if (!imageIds.contains(assets.get(i).path("assetId").asString())) assets.remove(i);
    }

    private void removeFuture(ArrayNode entries, Position limit) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            if (beyond(entry.path("revealAt"), limit)
                    || (entry.hasNonNull("sourcePosition") && beyond(entry.path("sourcePosition"), limit)))
                entries.remove(i);
        }
    }

    private boolean beyond(JsonNode node, Position limit) {
        if (!node.isObject()) return true;
        Position target = position(node);
        return target.unit() != limit.unit() || target.value() > limit.value();
    }

    private static void remapDraftIds(JsonNode node, Map<String, String> ids) {
        if (node instanceof ObjectNode object) {
            for (String key : List.of("entityId", "profileId", "entryId", "relationId")) {
                if (object.hasNonNull(key))
                    object.put(key, ids.computeIfAbsent(object.path(key).asString(), ignored -> UUID.randomUUID().toString()));
            }
            for (String endpoint : List.of("from", "to")) {
                if (object.path(endpoint) instanceof ObjectNode target && target.hasNonNull("id"))
                    target.put("id", ids.computeIfAbsent(target.path("id").asString(), ignored -> UUID.randomUUID().toString()));
            }
        }
        if ((node.isObject() || node.isArray())) for (JsonNode child : node) remapDraftIds(child, ids);
    }

    Position position(JsonNode node) { return json.treeToValue(node, Position.class); }

    static ObjectNode entity(Editing draft, String kind, UUID id) {
        return (ObjectNode) lookup(draft.content().path(kind), "entityId", id);
    }

    static JsonNode lookup(JsonNode array, String key, UUID id) { return array.get(indexOf(array, key, id)); }

    static int indexOf(JsonNode array, String key, UUID id) {
        for (int i = 0; i < array.size(); i++)
            if (id.toString().equals(array.get(i).path(key).asString())) return i;
        throw missing();
    }

    static ArrayNode snapshotArray(ObjectNode node, String key) {
        return node.path(key) instanceof ArrayNode array ? array : node.putArray(key);
    }

    ObjectNode snapshot(String payload) {
        var root = (ObjectNode) json.readTree(payload);
        for (String key : List.of("characters", "organizations", "relations", "assets")) snapshotArray(root, key);
        return root;
    }

    Draft find(UUID member, UUID id) {
        return store.findDraft(member, id).orElseThrow(WikiDrafts::missing);
    }

    Draft editable(UUID member, UUID id, String tag) {
        Draft draft = find(member, id);
        if (tag == null) throw new ApiException(428, "PRECONDITION_REQUIRED");
        if (draft.status() != DraftStatus.DRAFT) throw new ApiException(409, "DRAFT_NOT_EDITABLE");
        if (!etag(draft.revision()).equals(tag)) throw mismatch();
        return draft;
    }

    public static String etag(long revision) { return "\"" + revision + "\""; }

    Detail detail(Draft draft) { return detail(draft, snapshot(draft.snapshot())); }

    private Detail detail(Draft draft, ObjectNode data) {
        return new Detail(draft.id(), draft.bookId(), draft.mode(), draft.status(), draft.position(),
                draft.baseWikiVersion(), draft.parentSubmissionId(), draft.revision(), draft.updatedAt(),
                data.path("characters"), data.path("organizations"), data.path("relations"), data.path("assets"),
                data.has("conflicts") ? data.get("conflicts") : json.createArrayNode());
    }

    private static Summary summary(DraftSummary draft) {
        return new Summary(draft.id(), draft.bookId(), draft.mode(), draft.status(), draft.position(),
                draft.baseWikiVersion(), draft.parentSubmissionId(), draft.revision(), draft.updatedAt());
    }

    ObjectNode readableSnapshot(Draft draft, String payload) {
        var content = snapshot(payload);
        if (draft.mode() == Mode.UPDATE) {
            var current = scope().resolve(draft.memberId(), draft.bookId(), null);
            if (draft.position().unit() != current.unit()) throw new ApiException(403, "SPOILER_SCOPE_EXCEEDED");
            if (current.value() < draft.position().value()) {
                restrictCopy(content, current);
                var conflicts = snapshotArray(content, "conflicts");
                for (int i = conflicts.size() - 1; i >= 0; i--)
                    if (findItem(content, conflicts.get(i).asString()) == null) conflicts.remove(i);
            }
        }
        return content;
    }

    private static Summary summary(Draft draft) {
        return new Summary(draft.id(), draft.bookId(), draft.mode(), draft.status(), draft.position(),
                draft.baseWikiVersion(), draft.parentSubmissionId(), draft.revision(), draft.updatedAt());
    }

    WikiReadingScope scope() { return new WikiReadingScope(bookAccess()); }

    WikiBookAccess bookAccess() {
        WikiBookAccess access = books.getIfAvailable();
        if (access == null) throw new ApiException(503, "BOOK_CONTEXT_UNAVAILABLE");
        return access;
    }

    Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    static String encode(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "," + id).getBytes(StandardCharsets.UTF_8));
    }

    static DraftCursor decode(String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 120) throw new IllegalArgumentException();
            var fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(",", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            Instant timestamp = Instant.parse(fields[0]);
            if (timestamp.isBefore(Instant.EPOCH) || timestamp.isAfter(Instant.parse("9999-01-01T00:00:00Z")))
                throw new IllegalArgumentException();
            return new DraftCursor(timestamp, UUID.fromString(fields[1]));
        } catch (RuntimeException error) {
            throw new ApiException(400, "INVALID_CURSOR");
        }
    }

    static ApiException missing() { return new ApiException(404, "RESOURCE_NOT_FOUND"); }
    static ApiException invalid() { return new ApiException(422, "VALIDATION_ERROR"); }
    static ApiException mismatch() { return new ApiException(412, "VERSION_MISMATCH"); }
}
