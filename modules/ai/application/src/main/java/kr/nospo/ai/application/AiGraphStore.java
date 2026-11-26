package kr.nospo.ai.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.ai.application.AiBookContext.Position;
import kr.nospo.ai.application.AiQuestionStore.Cursor;

public interface AiGraphStore {
    record Graph(UUID job, UUID id, UUID member, UUID book, UUID source, Integer requestedValue,
                 Position position, int version, long generation, String status, String payload,
                 String error, Instant created, Instant completed, Instant deadline) {}

    Optional<Graph> find(UUID id, boolean job);
    Optional<Graph> request(UUID member, UUID key);
    void clearKey(UUID id);
    void insert(Graph graph, UUID key);
    List<UUID> queued();
    void finish(UUID id, Instant now, String payload, String error);
    List<Graph> list(UUID member, UUID book, Cursor cursor);
}
