package kr.nospo.credit.application;

import kr.nospo.credit.contract.AiCredits.Balance;
import kr.nospo.credit.contract.AiCredits.Transaction;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiCreditLedger {
    Optional<Balance> wallet(UUID member);
    void expireAdjustmentKey(UUID member, UUID actor, UUID key, Instant cutoff);
    Optional<Transaction> adjustment(UUID member, UUID actor, UUID key);
    void updateBalance(UUID member, int balance);
    boolean reserveOne(UUID member);
    boolean settleOne(UUID member, boolean charge);
    void record(UUID member, UUID actor, UUID key, Transaction transaction);
}
