package kr.nospo.identity.worker;

import kr.nospo.identity.application.EmailDeliveryService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "nospo.mail.worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailWorker {
    private final EmailDeliveryService delivery;

    public EmailWorker(EmailDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${nospo.mail.poll-ms:3000}")
    public void send() {
        delivery.processDue();
    }

    @Scheduled(fixedDelayString = "${nospo.mail.cleanup-ms:60000}")
    public void cleanup() {
        delivery.cleanup();
    }
}
