package kr.nospo.ai.worker;

import kr.nospo.ai.application.AiQuestionStore;
import kr.nospo.ai.application.AiQuestions;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class AiQuestionWorker {
    private final AiQuestions questions;
    private final AiQuestionStore store;
    private final Semaphore workers = new Semaphore(2);

    AiQuestionWorker(AiQuestions questions, AiQuestionStore store) {
        this.questions = questions;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${nospo.ai.poll-ms:1000}")
    void poll() {
        var now = questions.now();
        for (UUID id : store.expired(now)) questions.finish(id, null, null, "JOB_TIMEOUT");
        if (workers.availablePermits() == 0) return;
        for (UUID id : store.queued(now)) {
            if (!workers.tryAcquire()) break;
            Thread.startVirtualThread(() -> {
                try {
                    process(id);
                } finally {
                    workers.release();
                }
            });
        }
    }

    void process(UUID id) {
        var question = questions.begin(id);
        if (question == null) return;
        var answer = new StringBuilder();
        var sequence = new AtomicInteger(1);
        try {
            questions.generate(question, delta -> {
                if (answer.length() + delta.length() > 12_000 || sequence.get() >= 5000)
                    throw new IllegalStateException("AI answer exceeds the output limit");
                questions.append(question, sequence.incrementAndGet(), delta);
                answer.append(delta);
            });
            if (answer.toString().isBlank()) throw new IllegalStateException("Empty AI answer");
            questions.finish(id, answer.toString(), questions.citations(question, answer.toString()), null);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            LoggerFactory.getLogger(AiQuestionWorker.class).warn(
                    "AI job {} failed ({})", question.job(), error.getClass().getSimpleName());
            questions.finish(id, null, null, "AI_GENERATION_FAILED");
        }
    }

    @Scheduled(fixedDelay = 3600000L)
    void prune() {
        store.pruneDeltas(questions.now().minus(1, ChronoUnit.DAYS));
    }
}
