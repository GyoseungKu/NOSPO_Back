package kr.nospo.credit.contract;

import java.time.Instant;
import java.util.UUID;

public interface AiCredits {
    record Balance(int balance, int reserved, int available) {}
    record Transaction(UUID transactionId, String type, int amount, int balanceAfter,
                       String reason, UUID jobId, UUID submissionId, UUID originalTransactionId, Instant createdAt) {}

    Balance balance(UUID member);
    Transaction adjust(UUID actor, UUID member, UUID key, String type, int amount, String reason);
    void reserve(UUID member);
    void settle(UUID member, UUID jobId, boolean charge);
}
