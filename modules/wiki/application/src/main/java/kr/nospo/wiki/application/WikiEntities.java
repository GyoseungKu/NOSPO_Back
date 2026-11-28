package kr.nospo.wiki.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import kr.nospo.wiki.application.WikiBookAccess.Position;
import kr.nospo.wiki.application.WikiDrafts.Edit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static kr.nospo.wiki.application.WikiDrafts.*;

@Service
@Transactional
public class WikiEntities {
    static final Set<String> RELATION_TYPES = Set.of("FRIENDLY", "HOSTILE", "NEUTRAL", "AFFILIATION");
    private final WikiDrafts drafts;
    private final ObjectMapper json;

    public WikiEntities(WikiDrafts drafts, ObjectMapper json) {
        this.drafts = drafts;
        this.json = json;
    }

    public record Entry(String type, String title, String content, Position revealAt, Position sourcePosition) {}
    public record Target(String type, UUID id) {}
    public record Event(String kind, String type, String description, Position revealAt, Position sourcePosition) {}
    public record Relation(Target from, Target to, String type, String description,
                           Position revealAt, Position sourcePosition, List<Event> events) {}

    public Edit entry(UUID member, UUID draftId, String tag, String kind, UUID entityId, UUID entryId, Entry input) {
        return drafts.edit(member, draftId, tag, draft -> {
            var entity = entity(draft, kind, entityId);
            var entries = snapshotArray(entity, "entries");
            int index = entryId == null ? -1 : indexOf(entries, "entryId", entryId);
            if (input == null) {
                entries.remove(index);
                return null;
            }
            if (input.type() == null || !Set.of("EVENT", "ABILITY", "OTHER").contains(input.type())
                    || ("organizations".equals(kind) && !"EVENT".equals(input.type()))
                    || !WikiReviews.valid(input.title(), 200) || !WikiReviews.valid(input.content(), 10000))
                throw invalid();
            checkPosition(input.revealAt(), draft.position());
            checkPosition(input.sourcePosition(), draft.position());
            if (input.revealAt().value() < drafts.position(entity.path("firstRevealAt")).value()) throw invalid();
            ObjectNode node = json.valueToTree(input);
            node.put("entryId", (entryId == null ? UUID.randomUUID() : entryId).toString());
            if (index < 0) entries.add(node);
            else entries.set(index, node);
            return node;
        });
    }

    public Edit relation(UUID member, UUID id, String tag, UUID relationId, Relation input) {
        return drafts.edit(member, id, tag, draft -> {
            var relations = snapshotArray(draft.content(), "relations");
            int index = relationId == null ? -1 : indexOf(relations, "relationId", relationId);
            if (input == null) {
                relations.remove(index);
                return null;
            }
            var from = target(draft, input.from());
            var to = target(draft, input.to());
            if (input.from().id().equals(input.to().id()) || input.type() == null || !RELATION_TYPES.contains(input.type())
                    || input.description() == null || input.events() == null) throw invalid();
            checkDirection(input.type(), input.to());
            checkPosition(input.revealAt(), draft.position());
            checkPosition(input.sourcePosition(), draft.position());
            int earliest = Math.max(drafts.position(from.path("firstRevealAt")).value(),
                    drafts.position(to.path("firstRevealAt")).value());
            if (input.revealAt().value() < earliest) throw invalid();
            int previous = input.revealAt().value();
            boolean ended = false;
            for (Event event : input.events()) {
                if (event == null || ended) throw invalid();
                checkPosition(event.revealAt(), draft.position());
                checkPosition(event.sourcePosition(), draft.position());
                if (event.revealAt().value() <= previous || event.description() == null) throw invalid();
                if ("CHANGE".equals(event.kind())) {
                    if (event.type() == null || !RELATION_TYPES.contains(event.type())) throw invalid();
                    checkDirection(event.type(), input.to());
                } else if ("END".equals(event.kind())) {
                    ended = true;
                } else throw invalid();
                previous = event.revealAt().value();
            }
            ObjectNode node = json.valueToTree(input);
            node.put("relationId", (relationId == null ? UUID.randomUUID() : relationId).toString());
            if (index < 0) relations.add(node);
            else relations.set(index, node);
            return node;
        });
    }

    private static ObjectNode target(WikiDrafts.Editing draft, Target input) {
        if (input == null || input.id() == null) throw invalid();
        if ("CHARACTER".equals(input.type())) return entity(draft, "characters", input.id());
        if ("ORGANIZATION".equals(input.type())) return entity(draft, "organizations", input.id());
        throw invalid();
    }

    private static void checkDirection(String type, Target to) {
        if ("AFFILIATION".equals(type) && !"ORGANIZATION".equals(to.type())) throw invalid();
    }
}
