package kr.nospo.ai.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static kr.nospo.ai.application.AiBookContext.*;

public interface AiQuestionStore {
    record Conversation(UUID id, UUID book, Position scope, String title, Instant created, Instant updated) {}
    record Question(UUID id, UUID job, UUID conversation, UUID member, UUID book, Position position,
                    long generation, int version, String context, String text, String answer, String citations,
                    String status, String error, Instant created, Instant completed, Instant deadline,
                    UUID requestedConversation, String requestedUnit, Integer requestedValue) {
        public boolean pending() { return "QUEUED".equals(status) || "RUNNING".equals(status); }
    }
    record Delta(int sequence, String content) {}
    record Cursor(Instant time, UUID id) {}
    record Message(UUID messageId, UUID questionId, String role, String content, Position appliedPosition,
                   Instant createdAt, String citations) {}

    Optional<Question> question(UUID value, boolean job);
    Optional<Question> request(UUID member, UUID key);
    void expireRequest(UUID question);
    Optional<Conversation> conversation(UUID member, UUID conversation);
    List<Conversation> conversations(UUID member, UUID book, Cursor cursor, int limit);
    boolean pending(UUID conversation);
    void createConversation(UUID member, UUID conversation, UUID book, Position position, String title, Instant now);
    void touchConversation(UUID conversation, Position position, Instant now);
    void insert(Question question, UUID key);
    List<UUID> expired(Instant now);
    List<UUID> queued(Instant now);
    boolean start(UUID question, Instant now);
    void finish(Question question, Instant now, String answer, String citations, String error);
    void delta(UUID question, int sequence, String content);
    List<Delta> deltas(UUID question, int after);
    int lastDelta(UUID question);
    void pruneDeltas(Instant cutoff);
    void discardDeltas(UUID question);
    List<Question> previous(Question question);
    List<Message> messages(UUID conversation, Unit unit, Cursor cursor, int limit);
}
