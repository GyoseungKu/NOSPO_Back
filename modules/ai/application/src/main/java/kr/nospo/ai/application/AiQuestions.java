package kr.nospo.ai.application;

import kr.nospo.credit.contract.AiCredits;
import kr.nospo.member.contract.MemberAccess;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static kr.nospo.ai.application.AiBookContext.*;
import static kr.nospo.ai.application.AiQuestionStore.*;

@Service
public class AiQuestions {
    private static final Pattern CITATION = Pattern.compile("\\[([0-9a-fA-F-]{36})\\]");
    private static final String NO_EVIDENCE = "현재 읽은 범위의 위키 정보만으로는 답변하기 어렵습니다.";
    private final AiQuestionStore store;
    private final MemberAccess members;
    private final AiBookContext books;
    private final AiCredits credits;
    private final AiAnswerProvider provider;
    private final ObjectMapper json;
    private final Clock clock;

    public AiQuestions(AiQuestionStore store, MemberAccess members, AiBookContext books, AiCredits credits,
            AiAnswerProvider provider, ObjectMapper json, Clock clock) {
        this.store = store;
        this.members = members;
        this.books = books;
        this.credits = credits;
        this.provider = provider;
        this.json = json;
        this.clock = clock;
    }

    public record Page<T>(List<T> items, String nextCursor, boolean hasNext) {}
    record Citation(UUID entryId, UUID relationId, Position sourcePosition, int wikiVersion) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Question submit(UUID member, UUID key, UUID bookId, Position requested, String text, UUID conversation) {
        if (bookId == null || text == null || text.isBlank() || text.codePointCount(0, text.length()) > 4000)
            throw new ApiException(422, "VALIDATION_ERROR");
        var owner = members.lockState(member).filter(MemberAccess.State::active)
                .orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS"));
        Instant now = now();
        String unit = requested == null ? null : requested.unit().name();
        Integer value = requested == null ? null : requested.value();
        var previous = store.request(member, key);
        if (previous.isPresent()) {
            Question replay = previous.get();
            if (replay.created().isAfter(now.minus(1, ChronoUnit.DAYS))) {
                if (!replay.book().equals(bookId) || !replay.text().equals(text)
                        || !Objects.equals(replay.requestedConversation(), conversation)
                        || !Objects.equals(replay.requestedUnit(), unit)
                        || !Objects.equals(replay.requestedValue(), value))
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                requireVisible(member, bookId, replay.position());
                return replay;
            }
            store.expireRequest(replay.id());
        }
        if (!books.ready() || !provider.available()) throw new ApiException(503, "AI_UNAVAILABLE");
        Book book = books.book(bookId).orElseThrow(AiQuestions::missing);
        Position current = current(member, bookId);
        Position selected = requested == null ? current : requested;
        if (current.unit() != book.unit() || selected.unit() != book.unit() || selected.value() < 0
                || (book.totalUnits() != null && selected.value() > book.totalUnits()))
            throw new ApiException(422, "INVALID_POSITION");
        if (selected.value() > current.value()) throw new ApiException(422, "POSITION_EXCEEDS_CURRENT");
        var snapshot = books.visibleSnapshot(bookId, selected)
                .orElseThrow(() -> new ApiException(409, "WIKI_NOT_AVAILABLE"));
        String context = json.writeValueAsString(snapshot);
        if (context.getBytes(StandardCharsets.UTF_8).length > 60_000)
            throw new ApiException(503, "AI_UNAVAILABLE");
        UUID conversationId = conversation == null ? UUID.randomUUID() : conversation;
        if (conversation == null) {
            store.createConversation(member, conversationId, bookId, selected, "도서 질문", now);
        } else {
            var existing = store.conversation(member, conversationId)
                    .filter(c -> bookId.equals(c.book())).orElseThrow(AiQuestions::missing);
            if (!inScope(existing.scope(), selected))
                throw new ApiException(409, "CONVERSATION_POSITION_CONFLICT");
            if (store.pending(conversationId)) throw new ApiException(409, "REQUEST_IN_PROGRESS");
            store.touchConversation(conversationId, selected, now);
        }
        credits.reserve(member);
        var question = new Question(UUID.randomUUID(), UUID.randomUUID(), conversationId, member, bookId,
                selected, owner.withdrawalGeneration(), snapshot.version(), context, text, null, null, "QUEUED", null,
                now, null, now.plusSeconds(180), conversation, unit, value);
        store.insert(question, key);
        return question;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Question begin(UUID id) {
        var question = store.question(id, false).orElse(null);
        if (question == null) return null;
        var owner = members.lockState(question.member()).orElse(null);
        if (owner == null) return null;
        if (!sameActiveMember(question, owner)) {
            finish(id, null, null, "ACCOUNT_INACTIVE");
            return null;
        }
        return store.start(id, now()) ? question : null;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void append(Question question, int sequence, String content) {
        var member = members.lockState(question.member()).orElse(null);
        var current = store.question(question.id(), false).orElse(null);
        if (member == null || current == null || !sameActiveMember(question, member)
                || !"RUNNING".equals(current.status()) || !current.deadline().isAfter(now()))
            throw new IllegalStateException("AI job no longer accepts output");
        store.delta(current.id(), sequence, content);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finish(UUID id, String answer, String citations, String error) {
        var initial = store.question(id, false).orElse(null);
        if (initial == null) return;
        var member = members.lockState(initial.member()).orElse(null);
        var current = store.question(id, false).orElse(null);
        if (member == null || current == null || !current.pending()) return;
        Instant now = now();
        if (!sameActiveMember(current, member)) {
            error = "ACCOUNT_INACTIVE";
            store.discardDeltas(current.id());
        }
        else if (!current.deadline().isAfter(now)) error = "JOB_TIMEOUT";
        if (error == null && (!"RUNNING".equals(current.status()) || answer == null)) return;
        credits.settle(current.member(), current.job(), error == null, "AI_QUESTION");
        store.finish(current, now, error == null ? answer : null, error == null ? citations : null, error);
    }

    public Question visibleQuestion(UUID member, UUID id, boolean job) {
        var q = store.question(id, job).filter(found -> member.equals(found.member())).orElseThrow(AiQuestions::missing);
        if (q.pending() && members.state(member).filter(state -> sameActiveMember(q, state)).isEmpty())
            throw missing();
        requireVisible(member, q.book(), q.position());
        return q;
    }

    public Conversation visibleConversation(UUID member, UUID id) {
        var c = store.conversation(member, id).orElseThrow(AiQuestions::missing);
        requireVisible(member, c.book(), c.scope());
        return c;
    }

    public Page<Conversation> conversations(UUID member, UUID book, String cursor, int limit) {
        validLimit(limit);
        Cursor after = decode(cursor);
        var visible = new ArrayList<Conversation>();
        while (visible.size() <= limit && books.ready()) {
            var batch = store.conversations(member, book, after, 50);
            for (Conversation row : batch) {
                if (books.currentPosition(member, row.book()).filter(p -> inScope(row.scope(), p)).isPresent())
                    visible.add(row);
                after = new Cursor(row.created(), row.id());
                if (visible.size() > limit) break;
            }
            if (batch.size() < 50) break;
        }
        var items = List.copyOf(visible.subList(0, Math.min(visible.size(), limit)));
        boolean more = visible.size() > limit;
        var last = more ? items.getLast() : null;
        return new Page<>(items, more ? encode(last.created(), last.id()) : null, more);
    }

    public Page<Message> messages(UUID member, UUID id, String cursor, int limit) {
        validLimit(limit);
        var c = visibleConversation(member, id);
        var rows = store.messages(id, c.scope().unit(), decode(cursor), limit + 1);
        // Recheck against current progress after reading the message page.
        var latest = visibleConversation(member, id);
        if (rows.stream().anyMatch(row -> !inScope(row.appliedPosition(), latest.scope())))
            throw new ApiException(403, "SPOILER_SCOPE_EXCEEDED");
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        var last = more ? items.getLast() : null;
        return new Page<>(items, more ? encode(last.createdAt(), last.messageId()) : null, more);
    }

    public void generate(Question question, java.util.function.Consumer<String> delta) throws Exception {
        Snapshot snapshot = json.readValue(question.context(), Snapshot.class);
        if (snapshot.entries().isEmpty() && snapshot.entities().isEmpty() && snapshot.relations().isEmpty()) {
            delta.accept(NO_EVIDENCE);
            return;
        }
        var history = new ArrayList<AiAnswerProvider.Turn>();
        for (Question previous : store.previous(question).reversed()) {
            history.add(new AiAnswerProvider.Turn("user", previous.text()));
            history.add(new AiAnswerProvider.Turn("assistant", previous.answer()));
        }
        provider.generate(question.context(), history, question.text(),
                java.time.Duration.between(now(), question.deadline()), delta);
    }

    public String citations(Question question, String answer) {
        var references = new java.util.LinkedHashSet<UUID>();
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            try { references.add(UUID.fromString(matcher.group(1))); }
            catch (IllegalArgumentException ignored) {}
        }
        Snapshot snapshot = json.readValue(question.context(), Snapshot.class);
        var citations = new ArrayList<Citation>();
        for (Entry entry : snapshot.entries()) {
            if (references.contains(entry.id()))
                citations.add(new Citation(entry.id(), null, entry.sourcePosition(), question.version()));
        }
        for (Relation relation : snapshot.relations()) {
            if (references.contains(relation.id()))
                citations.add(new Citation(null, relation.id(), relation.sourcePosition(), question.version()));
        }
        return json.writeValueAsString(citations);
    }

    Position current(UUID member, UUID book) {
        if (!books.ready()) throw new ApiException(503, "AI_UNAVAILABLE");
        return books.currentPosition(member, book).orElseThrow(() -> new ApiException(403, "SHELF_REQUIRED"));
    }

    void requireVisible(UUID member, UUID book, Position scope) {
        if (!inScope(scope, current(member, book))) throw new ApiException(403, "SPOILER_SCOPE_EXCEEDED");
    }

    static boolean inScope(Position scope, Position ceiling) {
        return scope.unit() == ceiling.unit() && scope.value() <= ceiling.value();
    }

    private static boolean sameActiveMember(Question question, MemberAccess.State member) {
        return member.active() && question.generation() == member.withdrawalGeneration();
    }

    static void validLimit(int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(422, "VALIDATION_ERROR");
    }

    static Cursor decode(String value) {
        if (value == null) return null;
        try {
            if (value.length() > 120) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(",", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            Instant time = Instant.parse(fields[0]);
            if (time.isBefore(Instant.EPOCH) || time.isAfter(Instant.parse("9999-01-01T00:00:00Z")))
                throw new IllegalArgumentException();
            return new Cursor(time, UUID.fromString(fields[1]));
        } catch (RuntimeException error) {
            throw new ApiException(400, "INVALID_CURSOR");
        }
    }

    static String encode(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((time + "," + id).getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException missing() { return new ApiException(404, "RESOURCE_NOT_FOUND"); }

    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
