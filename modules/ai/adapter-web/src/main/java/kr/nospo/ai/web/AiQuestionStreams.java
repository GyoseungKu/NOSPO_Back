package kr.nospo.ai.web;

import kr.nospo.ai.application.AiQuestionStore;
import kr.nospo.ai.application.AiQuestions;
import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.support.ApiException;
import kr.nospo.terms.contract.MemberConsents;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
class AiQuestionStreams {
    private final AiQuestions questions;
    private final AiQuestionStore store;
    private final SignupIdentity identity;
    private final MemberConsents consents;
    private final ObjectMapper json;
    private final Semaphore streams = new Semaphore(64);

    AiQuestionStreams(AiQuestions questions, AiQuestionStore store, SignupIdentity identity, MemberConsents consents,
                      ObjectMapper json) {
        this.questions = questions;
        this.store = store;
        this.identity = identity;
        this.consents = consents;
        this.json = json;
    }

    SseEmitter open(Jwt jwt, UUID questionId, String lastEventId) {
        UUID member = AiQuestionController.member(jwt);
        UUID session = UUID.fromString(jwt.getClaimAsString("sid"));
        var question = questions.visibleQuestion(member, questionId, false);
        if (question.completed() != null && !question.completed().isAfter(questions.now().minus(1, ChronoUnit.DAYS)))
            throw new ApiException(410, "STREAM_REPLAY_EXPIRED");
        int after = parseEventId(lastEventId);
        int last = "QUEUED".equals(question.status()) ? 0
                : store.lastDelta(questionId) + (question.pending() ? 0 : 1);
        if (after > last) throw new ApiException(400, "INVALID_REQUEST");
        if (!streams.tryAcquire()) throw new ApiException(429, "RATE_LIMITED", 1);
        var emitter = new SseEmitter(195_000L);
        var closed = new AtomicBoolean();
        emitter.onTimeout(() -> closed.set(true));
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        Thread.startVirtualThread(() -> {
            try {
                send(emitter, jwt, member, session, questionId, after, closed);
            } catch (IOException ignored) {
                // The job is independent of the HTTP connection.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (ApiException ignored) {
                // A change in access closes the stream without sending more question data.
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(AiQuestionStreams.class)
                        .warn("AI stream closed ({})", error.getClass().getSimpleName());
            } finally {
                streams.release();
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, Jwt jwt, UUID member, UUID session, UUID id, int after,
                      AtomicBoolean closed) throws IOException, InterruptedException {
        Instant nextHeartbeat = Instant.EPOCH;
        while (!closed.get()) {
            Instant now = questions.now();
            if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                    || !identity.sessionActive(member, session, now) || consents.requiresReconsent(member, session))
                return;
            var q = questions.visibleQuestion(member, id, false);
            if (q.completed() != null && !q.completed().isAfter(now.minus(1, ChronoUnit.DAYS))) return;
            if (!"QUEUED".equals(q.status())) {
                if (after == 0) {
                    emit(emitter, 1, "started", Map.of("questionId", id, "jobId", q.job(),
                            "appliedPosition", q.position()));
                    after = 1;
                }
                var batch = store.deltas(id, after);
                for (var delta : batch) {
                    if (closed.get()) return;
                    questions.visibleQuestion(member, id, false);
                    emit(emitter, delta.sequence(), "delta", Map.of("text", delta.content()));
                    after = delta.sequence();
                }
                if (!q.pending() && batch.size() < 100) {
                    int terminal = store.lastDelta(id) + 1;
                    if (after < terminal) {
                        questions.visibleQuestion(member, id, false);
                        if ("SUCCEEDED".equals(q.status())) {
                            emit(emitter, terminal, "completed", Map.of("questionId", id, "chargedCredits", 1,
                                    "citations", json.readTree(q.citations())));
                        } else {
                            emit(emitter, terminal, "failed", Map.of("code", q.error(),
                                    "message", "질문을 완료하지 못했습니다.", "chargedCredits", 0));
                        }
                    }
                    return;
                }
            }
            if (!now.isBefore(nextHeartbeat)) {
                emitter.send(SseEmitter.event().comment("keep-alive"));
                nextHeartbeat = now.plusSeconds(10);
            }
            Thread.sleep(350);
        }
    }

    private static void emit(SseEmitter emitter, int id, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().id(Integer.toString(id)).name(event).data(data));
    }

    private static int parseEventId(String value) {
        if (value == null) return 0;
        try {
            int id = Integer.parseInt(value);
            if (id >= 0) return id;
        } catch (NumberFormatException ignored) {}
        throw new ApiException(400, "INVALID_REQUEST");
    }
}
