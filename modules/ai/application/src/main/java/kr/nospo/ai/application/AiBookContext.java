package kr.nospo.ai.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration boundary for catalog, shelf progress and approved wiki data.
 * Both question and graph workers can consume a snapshot pinned at admission.
 */
public interface AiBookContext {
    enum Unit { PAGE, EPISODE }
    record Position(Unit unit, int value) {}
    record Book(Unit unit, Integer totalUnits) {}
    record Entity(UUID id, String type, String name, String profile, String imageUrl) {}
    record Entry(UUID id, UUID entityId, String content, Position sourcePosition) {}
    record Relation(UUID id, UUID from, UUID to, String type, String description, Position sourcePosition) {}
    record Snapshot(int version, List<Entity> entities, List<Entry> entries, List<Relation> relations) {}

    boolean ready();
    Optional<Book> book(UUID bookId);
    Optional<Position> currentPosition(UUID memberId, UUID bookId);
    Optional<Snapshot> visibleSnapshot(UUID bookId, Position position);
}
