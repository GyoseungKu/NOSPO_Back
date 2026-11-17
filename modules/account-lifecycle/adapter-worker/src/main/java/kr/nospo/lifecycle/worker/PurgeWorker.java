package kr.nospo.lifecycle.worker;

import kr.nospo.lifecycle.application.PurgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="nospo.withdrawal.purge-enabled",havingValue="true")
public class PurgeWorker {
    private final PurgeService service;
    public PurgeWorker(PurgeService service) { this.service=service; }
    @Scheduled(fixedDelayString="${nospo.withdrawal.purge-poll-ms:60000}",initialDelayString="${nospo.withdrawal.purge-poll-ms:60000}")
    public void purge() { service.processBatch(); }
}
