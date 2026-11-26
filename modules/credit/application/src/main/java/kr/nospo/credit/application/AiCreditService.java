package kr.nospo.credit.application;

import kr.nospo.credit.contract.AiCredits;
import kr.nospo.member.contract.MemberAccess;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AiCreditService implements AiCredits {
    private final AiCreditLedger ledger;
    private final MemberAccess members;
    private final Clock clock;

    public AiCreditService(AiCreditLedger ledger, MemberAccess members, Clock clock) {
        this.ledger = ledger;
        this.members = members;
        this.clock = clock;
    }

    @Override
    public Balance balance(UUID member) {
        if (members.state(member).filter(MemberAccess.State::active).isEmpty())
            throw new ApiException(404, "RESOURCE_NOT_FOUND");
        return wallet(member);
    }

    @Override
    @Transactional
    public Transaction adjust(UUID actor, UUID member, UUID key, String type, int amount, String reason) {
        if (!("GRANT".equals(type) || "RECOVERY".equals(type)) || amount <= 0
                || reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 1000)
            throw new ApiException(422, "VALIDATION_ERROR");

        // Use the same member-row order for administrator and target across concurrent requests.
        boolean actorFirst = actor.toString().compareTo(member.toString()) <= 0;
        var first = members.lockState(actorFirst ? actor : member);
        var second = actor.equals(member) ? first : members.lockState(actorFirst ? member : actor);
        if ((actorFirst ? first : second).filter(MemberAccess.State::administrator).isEmpty())
            throw new ApiException(403, "FORBIDDEN");
        if ((actorFirst ? second : first).filter(MemberAccess.State::active).isEmpty())
            throw new ApiException(404, "RESOURCE_NOT_FOUND");

        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int change = "GRANT".equals(type) ? amount : -amount;
        ledger.expireAdjustmentKey(member, actor, key, now.minus(1, ChronoUnit.DAYS));
        var previous = ledger.adjustment(member, actor, key);
        if (previous.isPresent()) {
            var replay = previous.get();
            if (replay.amount() != change || !replay.type().equals(type) || !replay.reason().equals(reason))
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
            return replay;
        }
        Balance wallet = wallet(member);
        if (change < 0 && amount > wallet.available())
            throw new ApiException(409, "INSUFFICIENT_AVAILABLE_CREDITS");
        long next = (long) wallet.balance() + change;
        if (next > Integer.MAX_VALUE) throw new ApiException(422, "VALIDATION_ERROR");
        ledger.updateBalance(member, (int) next);
        var transaction = new Transaction(UUID.randomUUID(), type, change, (int) next, reason, null, null, null, now);
        ledger.record(member, actor, key, transaction);
        return transaction;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID member) {
        if (!ledger.reserveOne(member))
            throw new ApiException(409, "INSUFFICIENT_CREDITS");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(UUID member, UUID jobId, boolean charge) {
        if (!ledger.settleOne(member, charge))
            throw new IllegalStateException("AI credit reservation is missing");
        if (charge) ledger.record(member, null, null, new Transaction(UUID.randomUUID(), "USE", -1,
                wallet(member).balance(), "AI_QUESTION", jobId, null, null, clock.instant()));
    }

    private Balance wallet(UUID member) {
        return ledger.wallet(member).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    }
}
