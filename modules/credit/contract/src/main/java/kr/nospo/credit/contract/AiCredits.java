package kr.nospo.credit.contract;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public interface AiCredits {
    record Balance(int balance, int reserved, int available) {}
    record Transaction(UUID transactionId, String type, int amount, int balanceAfter,
                       String reason, UUID jobId, UUID submissionId, UUID originalTransactionId, Instant createdAt) {}

    record Page(List<Transaction> items, String nextCursor, boolean hasNext) {}
    Page transactions(UUID member, String type, Instant from, Instant to, String cursor, int limit);
    Transaction transaction(UUID member, UUID transactionId);
    Transaction refund(UUID actor, UUID transactionId, UUID key, String reason);

    Balance balance(UUID member);
    Transaction adjust(UUID actor, UUID member, UUID key, String type, int amount, String reason);
    void reserve(UUID member);
    void settle(UUID member, UUID jobId, boolean charge, String reason);
}
