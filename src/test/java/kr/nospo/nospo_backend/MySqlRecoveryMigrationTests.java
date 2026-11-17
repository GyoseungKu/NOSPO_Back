package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.util.*;

/** Upgrade a retained V6 fixture without touching any developer database. */
@Testcontainers(disabledWithoutDocker=true)
class MySqlRecoveryMigrationTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.7");
    @Test void version7PreservesRetainedOperationArchiveReservationsAndWallet() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("6").load().migrate();
        var db=new JdbcTemplate(source);UUID member=UUID.randomUUID(),operation=UUID.randomUUID();
        db.update("INSERT INTO member(member_id,status,role,created_at,updated_at,withdrawal_generation,withdrawn_at,recoverable_until)"
                +" VALUES (?,'WITHDRAWN','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 DAY))",id(member));
        db.update("INSERT INTO account(member_id,email_verified_at,created_at,updated_at) VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id(member));
        db.update("INSERT INTO withdrawal_operation(operation_id,member_id,generation,session_id,idempotency_key,request_fingerprint,status,withdrawn_at,recoverable_until)"
                +" VALUES (?,?,1,?,?,?,'RETAINED',CURRENT_TIMESTAMP,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 DAY))",id(operation),id(member),id(UUID.randomUUID()),id(UUID.randomUUID()),new byte[32]);
        db.update("INSERT INTO withdrawal_archive(operation_id,format_version,payload_ciphertext,wrapped_data_key,wrapping_key_id) VALUES (?,1,?,?,'fixture')",id(operation),new byte[]{1,2},new byte[]{3,4});
        db.update("INSERT INTO withdrawal_identity(kind,identifier_hash,operation_id) VALUES ('EMAIL',?,?)",new byte[32],id(operation));
        db.update("INSERT INTO credit_wallet(member_id,balance,reserved) VALUES (?,5,0)",id(member));
        var before=new LinkedHashMap<String,Map<String,Object>>();
        for(String table:List.of("member","account","withdrawal_operation","withdrawal_archive","withdrawal_identity","credit_wallet")) before.put(table,db.queryForMap("SELECT * FROM "+table));
        Flyway.configure().dataSource(source).load().migrate();
        before.forEach((table,row)->assertThat(db.queryForMap("SELECT * FROM "+table)).containsAllEntriesOf(row));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='withdrawal_operation_id'",Integer.class)).isEqualTo(2);
    }
}
