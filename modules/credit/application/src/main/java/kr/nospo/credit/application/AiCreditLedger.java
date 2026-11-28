package kr.nospo.credit.application;

import kr.nospo.credit.contract.AiCredits.Balance;
import kr.nospo.credit.contract.AiCredits.Transaction;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface AiCreditLedger {
    record Usage(UUID member, int amount, UUID job) {}
    record Refund(UUID actor, UUID key, Transaction transaction) {}

    Optional<Usage> usage(UUID transaction);
    Optional<Refund> refund(UUID original);
    void recordRefund(UUID actor, UUID key, Transaction transaction);
    Optional<Transaction> transaction(UUID member, UUID id);
    List<Transaction> transactions(UUID member, String type, Instant from, Instant to, Transaction cursor, int limit);

    Optional<Balance> wallet(UUID member);
    void expireAdjustmentKey(UUID member, UUID actor, UUID key, Instant cutoff);
    Optional<Transaction> adjustment(UUID member, UUID actor, UUID key);
    void updateBalance(UUID member, int balance);
    boolean reserveOne(UUID member);
    boolean settleOne(UUID member, boolean charge);
    void record(UUID member, UUID actor, UUID key, Transaction transaction);
}
