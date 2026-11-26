package kr.nospo.credit.persistence;

import kr.nospo.credit.application.AiCreditLedger;
import kr.nospo.credit.contract.AiCredits.Balance;
import kr.nospo.credit.contract.AiCredits.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static kr.nospo.support.SqlIds.*;

@Repository
public class JdbcAiCreditLedger implements AiCreditLedger {
    private final JdbcTemplate db;

    public JdbcAiCreditLedger(JdbcTemplate db) { this.db = db; }

    @Override
    public Optional<Balance> wallet(UUID member) {
        return db.query("SELECT balance,reserved FROM credit_wallet WHERE member_id=?",
                (r, n) -> new Balance(r.getInt(1), r.getInt(2), r.getInt(1) - r.getInt(2)), id(member))
                .stream().findFirst();
    }

    @Override
    public void expireAdjustmentKey(UUID member, UUID actor, UUID key, Instant cutoff) {
        db.update("""
                UPDATE credit_operation SET request_key=NULL
                WHERE member_id=? AND actor_id=? AND request_key=? AND created_at<=?
                """, id(member), id(actor), id(key), at(cutoff));
    }

    @Override
    public Optional<Transaction> adjustment(UUID member, UUID actor, UUID key) {
        return db.query("""
                SELECT transaction_id,kind,amount,balance_after,reason,created_at FROM credit_operation
                WHERE member_id=? AND actor_id=? AND request_key=?
                """, (r, n) -> new Transaction(uuid(r.getBytes(1)), r.getString(2), r.getInt(3),
                r.getInt(4), r.getString(5), null, null, null, r.getTimestamp(6).toInstant()),
                id(member), id(actor), id(key)).stream().findFirst();
    }

    @Override
    public void updateBalance(UUID member, int balance) {
        db.update("UPDATE credit_wallet SET balance=? WHERE member_id=?", balance, id(member));
    }

    @Override
    public boolean reserveOne(UUID member) {
        return db.update("UPDATE credit_wallet SET reserved=reserved+1 WHERE member_id=? AND balance>reserved",
                id(member)) == 1;
    }

    @Override
    public boolean settleOne(UUID member, boolean charge) {
        return db.update("""
                UPDATE credit_wallet SET reserved=reserved-1,balance=balance-?
                WHERE member_id=? AND reserved>0
                """, charge ? 1 : 0, id(member)) == 1;
    }

    @Override
    public void record(UUID member, UUID actor, UUID key, Transaction transaction) {
        db.update("""
                INSERT INTO credit_operation
                  (transaction_id,member_id,kind,amount,balance_after,reason,actor_id,request_key,job_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id(transaction.transactionId()), id(member), transaction.type(), transaction.amount(),
                transaction.balanceAfter(), transaction.reason(), id(actor), id(key), id(transaction.jobId()),
                at(transaction.createdAt()));
    }
}
