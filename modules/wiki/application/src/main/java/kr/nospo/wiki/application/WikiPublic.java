package kr.nospo.wiki.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import kr.nospo.wiki.application.WikiBookAccess.Position;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static kr.nospo.wiki.application.WikiDrafts.*;

@Service
public class WikiPublic {
    private final WikiStore store;
    private final WikiDrafts drafts;
    private final ObjectMapper json;

    public WikiPublic(WikiStore store, WikiDrafts drafts, ObjectMapper json) {
        this.store = store;
        this.drafts = drafts;
        this.json = json;
    }

    public record Snapshot(UUID wikiId, int version, Position position, JsonNode content) {}
    public record Info(boolean exists, UUID wikiId, Integer version, Position appliedPosition) {}
    public record Page(List<JsonNode> items, String nextCursor, boolean hasNext, Position appliedPosition, int wikiVersion) {}

    public Info info(UUID member, UUID book, Integer value) {
        Position position = resolve(member, book, value);
        var latest = store.latestVersion(book).orElse(null);
        return latest == null ? new Info(false, null, null, position)
                : new Info(true, latest.wikiId(), latest.version(), position);
    }

    public Snapshot snapshot(UUID book, Position position) {
        return findSnapshot(book, position).orElseThrow(WikiDrafts::missing);
    }

    public java.util.Optional<Snapshot> findSnapshot(UUID book, Position position) {
        return store.latestVersion(book).map(version -> visibleSnapshot(version, position));
    }

    private Snapshot visibleSnapshot(WikiStore.PublishedVersion version, Position position) {
        var content = drafts.snapshot(version.snapshot());
        drafts.restrictCopy(content, position);
        var relations = snapshotArray(content, "relations");
        for (int i = relations.size() - 1; i >= 0; i--) {
            var relation = (ObjectNode) relations.get(i);
            var events = new ArrayList<JsonNode>();
            relation.path("events").forEach(events::add);
            events.sort(Comparator.comparingInt(event -> drafts.position(event.path("revealAt")).value()));
            for (var event : events) {
                if ("END".equals(event.path("kind").asString())) {
                    relations.remove(i);
                    break;
                }
                for (String key : List.of("type", "description", "sourcePosition"))
                    if (event.hasNonNull(key)) relation.set(key, event.get(key));
            }
            relation.remove("events");
        }
        for (String kind : List.of("characters", "organizations")) {
            for (var entity : content.path(kind)) {
                JsonNode latest = null;
                for (var profile : entity.path("profiles"))
                    if (latest == null || drafts.position(profile.path("revealAt")).value()
                            > drafts.position(latest.path("revealAt")).value()) latest = profile;
                var profiles = snapshotArray((ObjectNode) entity, "profiles");
                profiles.removeAll();
                if (latest != null) profiles.add(latest);
            }
        }
        return new Snapshot(version.wikiId(), version.version(), position, content);
    }

    public Page list(UUID member, UUID book, Integer value, String kind, UUID organization, String cursor, int limit) {
        var snapshot = snapshot(book, resolve(member, book, value));
        var rows = new ArrayList<JsonNode>();
        for (var entity : snapshot.content().path(kind)) {
            String id = entity.path("entityId").asString();
            if (organization == null || matches(snapshot, orgRelation(id, organization.toString())))
                rows.add(summary(entity, snapshot.position()));
        }
        return page(snapshot, rows, "entityId", cursor, limit);
    }

    public JsonNode entity(UUID member, UUID book, Integer value, String kind, UUID id) {
        var snapshot = snapshot(book, resolve(member, book, value));
        var entity = lookup(snapshot.content().path(kind), "entityId", id);
        var result = summary(entity, snapshot.position());
        result.set("profiles", entity.path("profiles"));
        result.set("entries", entity.path("entries"));
        result.set("appliedPosition", json.valueToTree(snapshot.position()));
        result.put("wikiVersion", snapshot.version());
        var nodes = entitiesById(snapshot);
        var relations = result.putArray("relations");
        var affiliations = result.putArray("characters".equals(kind) ? "affiliations" : "members");
        for (var relation : snapshot.content().path("relations")) {
            boolean forward = id.toString().equals(relation.path("from").path("id").asString());
            boolean reverse = id.toString().equals(relation.path("to").path("id").asString());
            if (forward || reverse) relations.add(visibleRelation(nodes, relation));
            if ("AFFILIATION".equals(relation.path("type").asString())
                    && ("characters".equals(kind) ? forward : reverse))
                affiliations.add(visibleRelation(nodes, relation));
        }
        return result;
    }

    public Page relations(UUID member, UUID book, Integer value, String type, String entityType, UUID entityId,
                          String direction, String cursor, int limit) {
        if (direction != null && !List.of("IN", "OUT", "BOTH").contains(direction)) throw invalid();
        if (type != null && !WikiEntities.RELATION_TYPES.contains(type)) throw invalid();
        if (entityType != null && !List.of("CHARACTER", "ORGANIZATION").contains(entityType)) throw invalid();
        var snapshot = snapshot(book, resolve(member, book, value));
        var rows = new ArrayList<JsonNode>();
        var nodes = entitiesById(snapshot);
        for (var relation : snapshot.content().path("relations")) {
            if (type != null && !type.equals(relation.path("type").asString())) continue;
            if (entityId != null || entityType != null) {
                boolean outgoing = endpoint(relation.path("from"), entityType, entityId);
                boolean incoming = endpoint(relation.path("to"), entityType, entityId);
                if (!(outgoing && !"IN".equals(direction) || incoming && !"OUT".equals(direction))) continue;
            }
            rows.add(visibleRelation(nodes, relation));
        }
        return page(snapshot, rows, "relationId", cursor, limit);
    }

    public Position current(UUID member, UUID book) { return resolve(member, book, null); }

    public WikiBookAccess.Edition edition(UUID book) {
        return drafts.bookAccess().find(book).orElseThrow(WikiDrafts::missing);
    }

    boolean assetVisible(UUID member, UUID book, UUID asset, Integer value) {
        var snapshot = snapshot(book, resolve(member, book, value));
        for (String kind : List.of("characters", "organizations"))
            for (var entity : snapshot.content().path(kind))
                for (var profile : entity.path("profiles"))
                    if (asset.toString().equals(profile.path("imageAssetId").asString())) return true;
        return false;
    }

    private Page page(Snapshot snapshot, List<JsonNode> rows, String key, String cursor, int limit) {
        WikiReviews.validateLimit(limit);
        UUID after = null;
        if (cursor != null) {
            try {
                String[] parts = cursor.split(":", -1);
                if (parts.length != 2 || Integer.parseInt(parts[0]) != snapshot.version()) throw new IllegalArgumentException();
                after = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException error) {
                throw new kr.nospo.support.ApiException(400, "INVALID_CURSOR");
            }
        }
        String afterId = after == null ? "" : after.toString();
        rows.sort(Comparator.comparing(row -> row.path(key).asString()));
        var selected = rows.stream().filter(row -> row.path(key).asString().compareTo(afterId) > 0)
                .limit(limit + 1L).toList();
        boolean more = selected.size() > limit;
        var items = selected.subList(0, Math.min(selected.size(), limit));
        String next = more ? snapshot.version() + ":" + items.getLast().path(key).asString() : null;
        return new Page(items, next, more, snapshot.position(), snapshot.version());
    }

    private ObjectNode summary(JsonNode entity, Position position) {
        var profile = entity.path("profiles").path(0);
        var node = json.createObjectNode().put("entityId", entity.path("entityId").asString());
        for (String key : List.of("name", "description")) node.set(key, profile.has(key) ? profile.get(key) : json.nullNode());
        if (profile.hasNonNull("imageAssetId")) node.put("imageUrl", "/api/v1/wiki/assets/" + profile.path("imageAssetId").asString()
                + "?positionValue=" + position.value());
        else node.putNull("imageUrl");
        return node;
    }

    private ObjectNode visibleRelation(Map<String, JsonNode> nodes, JsonNode relation) {
        var result = json.createObjectNode().put("relationId", relation.path("relationId").asString())
                .put("type", relation.path("type").asString());
        result.set("description", relation.get("description"));
        for (String end : List.of("from", "to")) {
            var target = relation.path(end);
            var entity = nodes.get(target.path("id").asString());
            var endpoint = result.putObject(end).put("type", target.path("type").asString())
                    .put("id", target.path("id").asString());
            endpoint.set("name", entity.path("profiles").path(0).path("name"));
        }
        return result;
    }

    private static Map<String, JsonNode> entitiesById(Snapshot snapshot) {
        var nodes = new HashMap<String, JsonNode>();
        for (String kind : List.of("characters", "organizations"))
            for (var entity : snapshot.content().path(kind)) nodes.put(entity.path("entityId").asString(), entity);
        return nodes;
    }

    private Position resolve(UUID member, UUID book, Integer value) {
        var scope = drafts.scope();
        if (value == null) return scope.resolve(member, book, null);
        var edition = edition(book);
        return scope.resolve(member, book, new Position(edition.positionUnit(), value));
    }

    private static boolean endpoint(JsonNode target, String type, UUID id) {
        return (type == null || type.equals(target.path("type").asString()))
                && (id == null || id.toString().equals(target.path("id").asString()));
    }

    private static boolean matches(Snapshot snapshot, Predicate<JsonNode> predicate) {
        for (var relation : snapshot.content().path("relations")) if (predicate.test(relation)) return true;
        return false;
    }

    private static Predicate<JsonNode> orgRelation(String character, String organization) {
        return relation -> "AFFILIATION".equals(relation.path("type").asString())
                && character.equals(relation.path("from").path("id").asString())
                && organization.equals(relation.path("to").path("id").asString());
    }
}
