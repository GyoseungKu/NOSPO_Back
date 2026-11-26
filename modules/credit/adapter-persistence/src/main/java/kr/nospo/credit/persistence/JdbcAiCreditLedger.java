package kr.nospo.credit.persistence;

import kr.nospo.credit.application.AiCreditLedger;
import kr.nospo.credit.contract.AiCredits.Balance;
import kr.nospo.credit.contract.AiCredits.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import java.util.UUID;

import static kr.nospo.support.SqlIds.*;

@Repository
public class JdbcAiCreditLedger implements AiCreditLedger {
    private static final String TRANSACTIONS = """
            SELECT transaction_id, kind, amount, balance_after, reason, job_id,
                   NULL AS original_transaction_id, created_at
            FROM credit_operation WHERE member_id=?
            UNION ALL
            SELECT transaction_id, 'GRANT', amount, amount, 'SIGNUP', NULL, NULL, created_at
            FROM credit_transaction WHERE member_id=?
            UNION ALL
            SELECT r.transaction_id, 'REFUND', r.amount, r.balance_after, r.reason, o.job_id,
                   r.original_transaction_id, r.created_at
            FROM credit_refund r JOIN credit_operation o ON r.original_transaction_id=o.transaction_id
            WHERE o.member_id=?
            """;
    private static final RowMapper<Transaction> TRANSACTION = (row, index) -> new Transaction(
            uuid(row.getBytes("transaction_id")), row.getString("kind"), row.getInt("amount"),
            row.getInt("balance_after"), row.getString("reason"), uuid(row.getBytes("job_id")),
            null, uuid(row.getBytes("original_transaction_id")), row.getTimestamp("created_at").toInstant());

    private final JdbcTemplate db;

    public JdbcAiCreditLedger(JdbcTemplate db) { this.db = db; }

    @Override
    public Optional<Usage> usage(UUID transaction) {
        return db.query("SELECT member_id,amount,job_id FROM credit_operation WHERE transaction_id=? AND kind='USE'",
                (row, index) -> new Usage(uuid(row.getBytes(1)), row.getInt(2), uuid(row.getBytes(3))),
                id(transaction)).stream().findFirst();
    }

    @Override
    public Optional<Refund> refund(UUID original) {
        return db.query("""
                SELECT r.*, 'REFUND' AS kind, o.job_id
                FROM credit_refund r JOIN credit_operation o ON r.original_transaction_id=o.transaction_id
                WHERE r.original_transaction_id=?
                """, (row, index) -> new Refund(uuid(row.getBytes("actor_id")), uuid(row.getBytes("request_key")),
                TRANSACTION.mapRow(row, index)), id(original)).stream().findFirst();
    }

    @Override
    public void recordRefund(UUID actor, UUID key, Transaction transaction) {
        db.update("""
                INSERT INTO credit_refund (transaction_id,original_transaction_id,actor_id,request_key,
                    amount,balance_after,reason,created_at) VALUES (?,?,?,?,?,?,?,?)
                """, id(transaction.transactionId()), id(transaction.originalTransactionId()), id(actor), id(key),
                transaction.amount(), transaction.balanceAfter(), transaction.reason(), at(transaction.createdAt()));
    }

    @Override
    public Optional<Transaction> transaction(UUID member, UUID value) {
        return db.query("SELECT * FROM (" + TRANSACTIONS + ") ledger WHERE transaction_id=?",
                TRANSACTION, id(member), id(member), id(member), id(value)).stream().findFirst();
    }

    @Override
    public List<Transaction> transactions(UUID member, String type, Instant from, Instant to,
                                          Transaction cursor, int limit) {
        String sql = "SELECT * FROM (" + TRANSACTIONS + ") ledger WHERE 1=1";
        var args = new ArrayList<Object>(List.of(id(member), id(member), id(member)));
        if (type != null) {
            sql += " AND kind=?";
            args.add(type);
        }
        if (from != null) {
            sql += " AND created_at>=?";
            args.add(at(from));
        }
        if (to != null) {
            sql += " AND created_at<=?";
            args.add(at(to));
        }
        if (cursor != null) {
            sql += " AND (created_at<? OR (created_at=? AND transaction_id<?))";
            args.add(at(cursor.createdAt()));
            args.add(at(cursor.createdAt()));
            args.add(id(cursor.transactionId()));
        }
        args.add(limit);
        return db.query(sql + " ORDER BY created_at DESC,transaction_id DESC LIMIT ?", TRANSACTION, args.toArray());
    }

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
