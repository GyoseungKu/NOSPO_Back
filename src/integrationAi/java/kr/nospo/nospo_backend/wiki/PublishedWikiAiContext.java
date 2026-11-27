package kr.nospo.nospo_backend.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.ai.application.AiBookContext;
import kr.nospo.wiki.application.WikiBookAccess;
import kr.nospo.wiki.application.WikiPublic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@Primary
@Profile("!ai-local")
class PublishedWikiAiContext implements AiBookContext {
    private final ObjectProvider<WikiBookAccess> books;
    private final WikiPublic wiki;

    PublishedWikiAiContext(ObjectProvider<WikiBookAccess> books, WikiPublic wiki) {
        this.books = books;
        this.wiki = wiki;
    }

    public boolean ready() { return books.getIfAvailable() != null; }

    public Optional<Book> book(UUID book) {
        return Optional.ofNullable(books.getIfAvailable()).flatMap(access -> access.find(book))
                .map(edition -> new Book(Unit.valueOf(edition.positionUnit().name()), edition.totalUnits()));
    }

    public Optional<Position> currentPosition(UUID member, UUID book) {
        return Optional.ofNullable(books.getIfAvailable()).flatMap(access -> access.currentPosition(member, book))
                .map(position -> new Position(Unit.valueOf(position.unit().name()), position.value()));
    }

    public Optional<Snapshot> visibleSnapshot(UUID book, Position position) {
        return wiki.findSnapshot(book, new WikiBookAccess.Position(
                WikiBookAccess.Unit.valueOf(position.unit().name()), position.value())).map(this::convert);
    }

    private Snapshot convert(WikiPublic.Snapshot snapshot) {
        List<Entity> entities = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        for (String kind : List.of("characters", "organizations")) {
            for (var entity : snapshot.content().path(kind)) {
                UUID id = uuid(entity, "entityId");
                var profile = entity.path("profiles").path(0);
                String image = profile.hasNonNull("imageAssetId")
                        ? "/api/v1/wiki/assets/" + profile.path("imageAssetId").asString() + "?positionValue=" + snapshot.position().value() : null;
                String details = String.join("\n", List.of("description", "role", "gender", "personality").stream()
                        .filter(profile::hasNonNull).map(key -> profile.path(key).asString()).toList());
                entities.add(new Entity(id, entity.path("kind").asString(), profile.path("name").asString(), details, image));
                for (var entry : entity.path("entries"))
                    entries.add(new Entry(uuid(entry, "entryId"), id,
                            entry.path("title").asString() + "\n" + entry.path("content").asString(),
                            position(entry.path("sourcePosition"))));
            }
        }
        for (var relation : snapshot.content().path("relations"))
            relations.add(new Relation(uuid(relation, "relationId"), uuid(relation.path("from"), "id"),
                    uuid(relation.path("to"), "id"), relation.path("type").asString(),
                    relation.path("description").asString(), position(relation.path("sourcePosition"))));
        return new Snapshot(snapshot.version(), entities, entries, relations);
    }

    private static UUID uuid(JsonNode node, String key) { return UUID.fromString(node.path(key).asString()); }

    private static Position position(JsonNode node) {
        return new Position(Unit.valueOf(node.path("unit").asString()), node.path("value").asInt());
    }
}
