package kr.nospo.ai.worker;

import kr.nospo.ai.application.AiGraphStore;
import kr.nospo.ai.application.AiGraphs;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AiGraphWorker {
    private final AiGraphStore store;
    private final AiGraphs graphs;

    AiGraphWorker(AiGraphStore store, AiGraphs graphs) {
        this.store = store;
        this.graphs = graphs;
    }

    @Scheduled(fixedDelayString = "${nospo.ai.poll-ms:1000}")
    void poll() {
        for (var job : store.queued()) {
            try {
                graphs.process(job);
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(AiGraphWorker.class).warn("AI graph job {} could not be processed ({})",
                        job, error.getClass().getSimpleName());
            }
        }
    }
}
