package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.util.UUID;

/** Upgrade an existing V5 account in an isolated MySQL database, never the development volume. */
@Testcontainers(disabledWithoutDocker=true)
class MySqlWithdrawalMigrationTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.7");
    @Test
    void version6PreservesExistingActiveAccountWalletCredentialsAndProvider(){
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("5").load().migrate();
        var db=new JdbcTemplate(source);UUID member=UUID.randomUUID();
        db.update("INSERT INTO member(member_id,nickname,nickname_key,phone_number,status,role,created_at,updated_at)"
                +" VALUES (?,'fixture','fixture','01000000000','ACTIVE','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(member));
        db.update("INSERT INTO account(member_id,email,email_key,email_verified_at,created_at,updated_at)"
                +" VALUES (?,'fixture@example.com','fixture@example.com',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(member));
        db.update("INSERT INTO password_credential(member_id,password_hash,password_changed_at) VALUES (?,'synthetic-hash',CURRENT_TIMESTAMP)",id(member));
        db.update("INSERT INTO social_identity(social_identity_id,member_id,provider,provider_subject,linked_at) VALUES (?,?,'KAKAO','12345',CURRENT_TIMESTAMP)",id(UUID.randomUUID()),id(member));
        db.update("INSERT INTO credit_wallet(member_id,balance,reserved) VALUES (?,5,0)",id(member));
        db.update("INSERT INTO credit_transaction(transaction_id,member_id,kind,amount,created_at) VALUES (?,?,'SIGNUP',5,CURRENT_TIMESTAMP)",id(UUID.randomUUID()),id(member));
        var memberBefore=db.queryForMap("SELECT * FROM member");
        var accountBefore=db.queryForMap("SELECT * FROM account");
        var credentialBefore=db.queryForMap("SELECT * FROM password_credential");
        var providerBefore=db.queryForMap("SELECT * FROM social_identity");
        var walletBefore=db.queryForMap("SELECT * FROM credit_wallet");
        var ledgerBefore=db.queryForMap("SELECT * FROM credit_transaction");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(db.queryForMap("SELECT * FROM member")).containsAllEntriesOf(memberBefore);
        assertThat(db.queryForMap("SELECT * FROM account")).containsAllEntriesOf(accountBefore);
        assertThat(db.queryForMap("SELECT * FROM password_credential")).containsAllEntriesOf(credentialBefore);
        assertThat(db.queryForMap("SELECT * FROM social_identity")).containsAllEntriesOf(providerBefore);
        assertThat(db.queryForMap("SELECT * FROM credit_wallet")).containsAllEntriesOf(walletBefore);
        assertThat(db.queryForMap("SELECT * FROM credit_transaction")).containsAllEntriesOf(ledgerBefore);
        assertThat(db.queryForObject("SELECT withdrawal_generation FROM member",Long.class)).isZero();
        for(String table:new String[]{"withdrawal_operation","withdrawal_archive","withdrawal_identity","withdrawal_target"})
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
    }
}
