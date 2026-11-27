package kr.nospo.wiki.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static kr.nospo.wiki.application.WikiDrafts.*;

@Component
class WikiSnapshots {
    private static final Map<String, String> IDS = Map.of(
            "characters", "entityId", "organizations", "entityId", "profiles", "profileId", "entries", "entryId",
            "relations", "relationId", "assets", "assetId");
    private final WikiDrafts drafts;

    WikiSnapshots(WikiDrafts drafts) { this.drafts = drafts; }

    ObjectNode publish(ObjectNode proposal, ObjectNode base, WikiBookAccess.Position originalScope) {
        var canonical = proposal.deepCopy();
        var idMap = canonical.remove("baseIds");
        canonical.remove("conflicts");
        var copiedThrough = canonical.remove("copiedThrough");
        if (copiedThrough != null) originalScope = drafts.position(copiedThrough);
        if (idMap != null) restoreIds(canonical, idMap);
        if (base != null) {
            var visibleBase = base.deepCopy();
            drafts.restrictCopy(visibleBase, originalScope);
            preserveHidden(canonical, base, visibleBase);
        }
        var entities = new HashSet<String>();
        var images = new HashSet<String>();
        for (String kind : List.of("characters", "organizations")) {
            for (var entity : canonical.path(kind)) {
                entities.add(entity.path("entityId").asString());
                for (var profile : entity.path("profiles"))
                    if (profile.hasNonNull("imageAssetId")) images.add(profile.path("imageAssetId").asString());
            }
        }
        var relations = snapshotArray(canonical, "relations");
        removeRelationsWithMissingEndpoints(relations, entities);
        var assets = snapshotArray(canonical, "assets");
        for (int i = assets.size() - 1; i >= 0; i--)
            if (!images.contains(assets.get(i).path("assetId").asString())) assets.remove(i);
        return canonical;
    }

    ObjectNode rebase(ObjectNode base, ObjectNode proposal, ObjectNode current, ArrayNode conflicts) {
        return mergeObject(base, proposal, current, null, conflicts);
    }

    private ObjectNode mergeObject(ObjectNode base, ObjectNode proposal, ObjectNode current, String id,
                                   ArrayNode conflicts) {
        var merged = current.deepCopy();
        var fields = new java.util.TreeSet<String>();
        base.propertyNames().forEach(fields::add);
        proposal.propertyNames().forEach(fields::add);
        current.propertyNames().forEach(fields::add);
        for (String field : fields) {
            var oldValue = base.path(field);
            var proposed = proposal.path(field);
            var latest = current.path(field);
            if (IDS.containsKey(field)) {
                var result = snapshotArray(merged, field);
                result.removeAll();
                var oldItems = index(oldValue, IDS.get(field));
                var proposedItems = index(proposed, IDS.get(field));
                var latestItems = index(latest, IDS.get(field));
                var itemIds = new java.util.TreeSet<>(oldItems.keySet());
                itemIds.addAll(proposedItems.keySet());
                itemIds.addAll(latestItems.keySet());
                for (String key : itemIds) {
                    var oldItem = oldItems.get(key);
                    var proposedItem = proposedItems.get(key);
                    var latestItem = latestItems.get(key);
                    ObjectNode item;
                    if (java.util.Objects.equals(oldItem, proposedItem)) item = latestItem;
                    else if (java.util.Objects.equals(oldItem, latestItem) || java.util.Objects.equals(proposedItem, latestItem))
                        item = proposedItem;
                    else if (oldItem != null && proposedItem != null && latestItem != null)
                        item = mergeObject(oldItem, proposedItem, latestItem, key, conflicts);
                    else {
                        conflicts.add(key);
                        item = proposedItem == null ? latestItem : proposedItem;
                    }
                    if (item != null) result.add(item.deepCopy());
                }
            } else if (!oldValue.equals(proposed)) {
                if (!oldValue.equals(latest) && !proposed.equals(latest) && id != null) conflicts.add(id);
                if (proposed.isMissingNode()) merged.remove(field);
                else merged.set(field, proposed.deepCopy());
            }
        }
        return merged;
    }

    private void preserveHidden(ObjectNode result, JsonNode base, JsonNode visibleBase) {
        for (var field : IDS.entrySet()) {
            String key = field.getKey();
            if (!base.path(key).isArray()) continue;
            String idKey = field.getValue();
            var next = snapshotArray(result, key);
            var visible = index(visibleBase.path(key), idKey);
            var present = index(next, idKey);
            for (var original : base.path(key)) {
                String id = original.path(idKey).asString();
                ObjectNode edited = present.get(id);
                if (edited == null && !visible.containsKey(id)) {
                    next.add(original.deepCopy());
                } else if (edited != null && visible.containsKey(id)) {
                    preserveHidden(edited, original, visible.get(id));
                    if ("relations".equals(key)) {
                        var events = snapshotArray(edited, "events");
                        for (var event : original.path("events"))
                            if (!contains(visible.get(id).path("events"), event) && !contains(events, event))
                                events.add(event.deepCopy());
                    }
                }
            }
        }
    }

    private static Map<String, ObjectNode> index(JsonNode array, String key) {
        var result = new HashMap<String, ObjectNode>();
        for (var node : array) result.put(node.path(key).asString(), (ObjectNode) node);
        return result;
    }

    private static boolean contains(JsonNode array, JsonNode wanted) {
        for (var node : array) if (node.equals(wanted)) return true;
        return false;
    }

    private static void removeRelationsWithMissingEndpoints(ArrayNode relations, Set<String> entities) {
        for (int i = relations.size() - 1; i >= 0; i--) {
            var relation = relations.get(i);
            if (!entities.contains(relation.path("from").path("id").asString())
                    || !entities.contains(relation.path("to").path("id").asString())) relations.remove(i);
        }
    }

    private static void restoreIds(JsonNode node, JsonNode ids) {
        if (node instanceof ObjectNode object) {
            for (String key : List.of("entityId", "profileId", "entryId", "relationId", "id")) {
                String current = object.path(key).asString();
                if (ids.hasNonNull(current)) object.put(key, ids.path(current).asString());
            }
        }
        if (node.isArray() || node.isObject()) for (var child : node) restoreIds(child, ids);
    }

    record Change(String entityType, String entityId, String field, JsonNode before, JsonNode after) {}

    List<Change> changes(ObjectNode before, ObjectNode after) {
        var out = new ArrayList<Change>();
        changes(before, after, null, null, out);
        return List.copyOf(out);
    }

    private static void changes(JsonNode before, JsonNode after, String type, String id, List<Change> out) {
        for (var field : IDS.entrySet()) {
            var left = index(before.path(field.getKey()), field.getValue());
            var right = index(after.path(field.getKey()), field.getValue());
            var ids = new java.util.TreeSet<>(left.keySet());
            ids.addAll(right.keySet());
            for (String key : ids) {
                var previous = left.get(key);
                var next = right.get(key);
                if (previous == null || next == null) {
                    out.add(new Change(field.getKey(), key, field.getKey(), previous, next));
                } else {
                    changes(previous, next, field.getKey(), key, out);
                }
            }
        }
        if (id == null) return;
        var fields = new java.util.TreeSet<String>();
        before.propertyNames().forEach(fields::add);
        after.propertyNames().forEach(fields::add);
        for (String field : fields) {
            if (!IDS.containsKey(field) && !before.path(field).equals(after.path(field)))
                out.add(new Change(type, id, field, before.get(field), after.get(field)));
        }
    }
}
