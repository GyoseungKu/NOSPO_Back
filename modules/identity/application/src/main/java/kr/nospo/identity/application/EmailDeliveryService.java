package kr.nospo.identity.application;

import kr.nospo.support.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;

@Service
public class EmailDeliveryService {
    private final IdentityStore store;
    private final EmailSender sender;
    private final Secrets secrets;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final DatabaseGuard guard;

    public EmailDeliveryService(
            IdentityStore store,
            EmailSender sender,
            Secrets secrets,
            Clock clock,
            PlatformTransactionManager manager,
            DatabaseGuard guard) {
        this.store = store;
        this.sender = sender;
        this.secrets = secrets;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.guard = guard;
    }

    public void processDue() {
        for (var id : store.dueDeliveries(clock.instant())) {
            var claimed = tx.execute(s -> store.claim(id, UUID.randomUUID(), clock.instant()));
            if (claimed == null || claimed.isEmpty()) continue;
            var job = claimed.get();
            if (job.attempts() > 5) {
                tx.executeWithoutResult(
                        s ->
                                store.finish(
                                        job,
                                        "FAILED",
                                        "MAIL_RETRY_EXHAUSTED",
                                        null,
                                        clock.instant()));
                continue;
            }
            if (!store.deliverable(job, clock.instant())) {
                tx.executeWithoutResult(
                        s -> store.finish(job, "CANCELLED", null, null, clock.instant()));
                continue;
            }
            try {
                String[] payload =
                        new String(
                                        secrets.decrypt(job.payload(), "mail:" + job.id()),
                                        StandardCharsets.UTF_8)
                                .split("\n", 2);
                sender.send(payload[0], payload[1]);
                tx.executeWithoutResult(
                        s -> store.finish(job, "ACCEPTED", null, null, clock.instant()));
            } catch (RuntimeException ex) {
                Instant now = clock.instant(),
                        next = now.plusSeconds(Math.min(120, 15L * job.attempts()));
                boolean permanent = ex instanceof EmailSender.DeliveryFailure f && f.permanent();
                boolean retry = !permanent && job.attempts() < 5 && next.isBefore(job.expiresAt());
                tx.executeWithoutResult(
                        s ->
                                store.finish(
                                        job,
                                        retry ? "PENDING" : "FAILED",
                                        permanent
                                                ? "MAIL_PERMANENT_FAILURE"
                                                : "MAIL_TEMPORARY_FAILURE",
                                        retry ? next : null,
                                        now));
            }
        }
    }

    public void cleanup() {
        tx.executeWithoutResult(
                s -> {
                    store.cleanup(clock.instant());
                    guard.cleanup(clock.instant());
                });
    }
}
