package kr.nospo.nospo_backend.wiki;

import java.time.Instant;
import java.util.UUID;

import kr.nospo.credit.application.AiCreditService;
import kr.nospo.credit.application.AiCreditLedger;
import kr.nospo.support.ApiException;
import kr.nospo.credit.contract.AiCredits;
import kr.nospo.wiki.application.WikiRewards;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static kr.nospo.support.SqlIds.*;

@Configuration(proxyBeanMethods = false)
class WikiCreditIntegration {
    @Bean
    WikiRewards wikiRewards(AiCreditLedger ledger) {
        return (member, actor, submission, amount, reason, now) -> {
            var current = ledger.wallet(member).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
            long next = (long) current.balance() + amount;
            if (next > Integer.MAX_VALUE) throw new ApiException(422, "VALIDATION_ERROR");
            UUID transaction = UUID.randomUUID();
            ledger.updateBalance(member, (int) next);
            ledger.record(member, actor, null, new AiCredits.Transaction(transaction, "GRANT", amount, (int) next,
                    reason, null, submission, null, now));
            return transaction;
        };
    }

    @Bean
    @Primary
    AiCredits wikiAwareAiCredits(AiCreditService credits, JdbcTemplate db) {
        return new WikiAwareCredits(credits, db);
    }

    private record WikiAwareCredits(AiCreditService target, JdbcTemplate db) implements AiCredits {
        public Balance balance(UUID member) { return target.balance(member); }

        public Transaction adjust(UUID actor, UUID member, UUID key, String type, int amount, String reason) {
            return target.adjust(actor, member, key, type, amount, reason);
        }

        public void reserve(UUID member) { target.reserve(member); }

        public void settle(UUID member, UUID job, boolean charge, String reason) {
            target.settle(member, job, charge, reason);
        }

        public Transaction refund(UUID actor, UUID transaction, UUID key, String reason) {
            return target.refund(actor, transaction, key, reason);
        }

        public Page transactions(UUID member, String type, Instant from, Instant to, String cursor, int limit) {
            var page = target.transactions(member, type, from, to, cursor, limit);
            return new Page(page.items().stream().map(this::withSubmission).toList(), page.nextCursor(), page.hasNext());
        }

        public Transaction transaction(UUID member, UUID id) { return withSubmission(target.transaction(member, id)); }

        private Transaction withSubmission(Transaction row) {
            if (!"GRANT".equals(row.type())) return row;
            var submission = db.query("SELECT submission_id FROM wiki_submission WHERE reward_transaction_id=?",
                    (result, index) -> uuid(result.getBytes(1)), id(row.transactionId())).stream().findFirst();
            return submission.map(value -> new Transaction(row.transactionId(), row.type(), row.amount(),
                    row.balanceAfter(), row.reason(), row.jobId(), value, row.originalTransactionId(), row.createdAt()))
                    .orElse(row);
        }
    }
}
