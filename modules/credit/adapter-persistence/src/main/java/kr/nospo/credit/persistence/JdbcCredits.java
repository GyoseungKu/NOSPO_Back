package kr.nospo.credit.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.credit.application.CreditStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcCredits implements CreditStore {
    private final JdbcTemplate jdbc;

    public JdbcCredits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createSignupWallet(UUID member, int amount, Instant now) {
        jdbc.update(
                "INSERT INTO credit_wallet(member_id,balance,reserved) VALUES (?,?,0)",
                id(member),
                amount);
        jdbc.update(
                "INSERT INTO credit_transaction(transaction_id,member_id,kind,amount,created_at)"
                        + " VALUES (?,?,'SIGNUP',?,?)",
                id(UUID.randomUUID()),
                id(member),
                amount,
                at(now));
    }
}
