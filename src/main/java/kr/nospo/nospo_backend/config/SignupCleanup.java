package kr.nospo.nospo_backend.config;

import kr.nospo.lifecycle.application.SignupRecords;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        name = "nospo.mail.worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SignupCleanup {
    private final SignupRecords records;
    private final Clock clock;

    public SignupCleanup(SignupRecords records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        records.cleanup(clock.instant());
    }
}
