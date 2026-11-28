package kr.nospo.nospo_backend.wiki;

import java.time.Instant;
import java.util.UUID;
import java.util.Collections;
import java.util.HashMap;

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
            var grants = page.items().stream().filter(row -> "GRANT".equals(row.type()))
                    .map(row -> id(row.transactionId())).toArray();
            if (grants.length == 0) return page;
            var submissions = new HashMap<UUID, UUID>();
            String parameters = String.join(",", Collections.nCopies(grants.length, "?"));
            db.query("SELECT reward_transaction_id,submission_id FROM wiki_submission WHERE reward_transaction_id IN ("
                            + parameters + ")", row -> {
                        submissions.put(uuid(row.getBytes(1)), uuid(row.getBytes(2)));
                    }, grants);
            return new Page(page.items().stream().map(row -> withSubmission(row, submissions.get(row.transactionId())))
                    .toList(), page.nextCursor(), page.hasNext());
        }

        public Transaction transaction(UUID member, UUID id) {
            var row = target.transaction(member, id);
            if (!"GRANT".equals(row.type())) return row;
            var submission = db.query("SELECT submission_id FROM wiki_submission WHERE reward_transaction_id=?",
                    (result, index) -> uuid(result.getBytes(1)), id(row.transactionId())).stream().findFirst();
            return withSubmission(row, submission.orElse(null));
        }

        private static Transaction withSubmission(Transaction row, UUID submission) {
            if (submission == null) return row;
            return new Transaction(row.transactionId(), row.type(), row.amount(), row.balanceAfter(),
                    row.reason(), row.jobId(), submission, row.originalTransactionId(), row.createdAt());
        }
    }
}
