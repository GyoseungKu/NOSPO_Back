package kr.nospo.lifecycle.application;

import kr.nospo.identity.contract.PurgeIdentity;
import kr.nospo.member.contract.PurgeMembers;
import kr.nospo.terms.contract.WithdrawalConsents;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service
public class PurgeService {
    private final PurgeStore store;
    private final PurgeIdentity identity;
    private final PurgeMembers members;
    private final WithdrawalConsents consents;
    private final Clock clock;
    private final TransactionTemplate tx;
    public PurgeService(PurgeStore store, PurgeIdentity identity, PurgeMembers members,
            WithdrawalConsents consents, Clock clock, PlatformTransactionManager manager) {
        this.store=store; this.identity=identity; this.members=members; this.consents=consents; this.clock=clock;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** Each account has independent claim / deletion / failure transactions. No exception detail is logged. */
    public int processBatch() {
        int completed=0;
        List<PurgeStore.Candidate> due;
        try { due=store.due(clock.instant(),50); }
        catch(RuntimeException failure) {
            System.getLogger(PurgeService.class.getName()).log(System.Logger.Level.WARNING,"WITHDRAWAL_PURGE_RETRY_REQUIRED");
            return 0;
        }
        for (var candidate : due) {
            try {
                var lease=claim(candidate);
                if (lease.isPresent()) {
                    try { if (complete(lease.get())) completed++; }
                    catch (RuntimeException failure) { retry(lease.get()); }
                }
            } catch (RuntimeException failure) {
                // A failed claim leaves the operation eligible on the next poll. No PII in diagnostics.
                System.getLogger(PurgeService.class.getName()).log(System.Logger.Level.WARNING,"WITHDRAWAL_PURGE_RETRY_REQUIRED");
            }
        }
        return completed;
    }
    public Optional<PurgeStore.Lease> claim(PurgeStore.Candidate candidate) {
        return tx.execute(s -> {
            // lockEligible locks member first. Re-read clock after any member-lock wait in the store.
            if (!store.lockEligible(candidate,clock.instant())) return Optional.empty();
            return store.claim(candidate,clock.instant());
        });
    }
    public boolean complete(PurgeStore.Lease lease) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (!store.lockEligible(lease.candidate(),clock.instant()) || !store.owns(lease,clock.instant())) return false;
            identity.delete(lease.candidate().member());
            consents.remove(lease.candidate().member());
            store.deleteRecords(lease);
            members.delete(lease.candidate().member(),lease.candidate().generation());
            return true;
        }));
    }
    private void retry(PurgeStore.Lease lease) {
        Instant now=clock.instant();
        long delay=Math.min(3600,60L << Math.min(lease.attempts()-1,6));
        tx.executeWithoutResult(s -> {
            if (store.lockEligible(lease.candidate(),clock.instant())) store.retry(lease,now,now.plusSeconds(delay));
        });
        System.getLogger(PurgeService.class.getName()).log(System.Logger.Level.WARNING,"WITHDRAWAL_PURGE_RETRY_REQUIRED");
    }
}
