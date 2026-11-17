package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import kr.nospo.terms.persistence.JdbcWithdrawalConsents;
import kr.nospo.support.ArchiveRows;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Required isolated MySQL; no developer database is ever used. */
@Testcontainers
class MySqlConsentMigrationTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.7");

    @Test void version8PreservesEventsAndRestoresPreV8ArchivesDeterministically() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("7").load().migrate();
        var db=new JdbcTemplate(source);
        UUID member=UUID.randomUUID(),term=UUID.randomUUID(),version=UUID.randomUUID();
        Instant now=Instant.parse("2026-11-17T01:00:00Z");
        db.update("INSERT INTO member(member_id,status,role,created_at,updated_at) VALUES (?,'ACTIVE','USER',?,?)",id(member),at(now),at(now));
        db.update("INSERT INTO term(term_id,type,created_at) VALUES (?,'OPTIONAL',?)",id(term),at(now));
        db.update("INSERT INTO term_version(term_version_id,term_id,version,title,content,required,publication_status,effective_at,created_at)"
                +" VALUES (?,?,'1','fixture','fixture',false,'PUBLISHED',?,?)",id(version),id(term),at(now),at(now));
        var ids=List.of(new UUID(0,1),new UUID(0,2),new UUID(0,3));
        for(UUID consent:List.of(ids.get(2),ids.get(0),ids.get(1)))
            db.update("INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at) VALUES (?,?,?,?,?)",
                    id(consent),id(member),id(version),consent.equals(ids.get(1)),at(now));
        ObjectMapper mapper=new ObjectMapper();
        byte[] legacy=ArchiveRows.read(db,mapper,"SELECT * FROM member_consent WHERE member_id=?",id(member));
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(db.queryForList("SELECT event_order FROM member_consent ORDER BY consent_id",Long.class)).containsExactly(1L,2L,3L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM member_consent",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_consent' AND REFERENCED_TABLE_NAME IS NOT NULL",Integer.class)).isEqualTo(2);
        var archive=new JdbcWithdrawalConsents(db,mapper);
        db.update("DELETE FROM member_consent");
        archive.restore(member,legacy);
        assertThat(db.query("SELECT consent_id FROM member_consent ORDER BY event_order",(rs,n)->uuid(rs.getBytes(1)))).containsExactlyElementsOf(ids);
        // New snapshots preserve semantic event order even when timestamps move backwards.
        db.update("UPDATE member_consent SET agreed_at=? WHERE event_order=3",at(now.minusSeconds(5)));
        byte[] modern=archive.snapshot(member);
        db.update("DELETE FROM member_consent");
        archive.restore(member,modern);
        assertThat(db.query("SELECT consent_id FROM member_consent ORDER BY event_order",(rs,n)->uuid(rs.getBytes(1)))).containsExactlyElementsOf(ids);
        var members=new kr.nospo.member.application.MemberService(new kr.nospo.member.persistence.JdbcMembers(db));
        var store=new kr.nospo.terms.persistence.JdbcConsentStore(db);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        String encoded=Base64.getEncoder().encodeToString(key);
        var cursor=new kr.nospo.terms.application.ConsentCursor(new kr.nospo.support.Secrets(encoded,encoded));
        var history=new kr.nospo.terms.application.ConsentHistory(members,store,cursor);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        var first=tx.execute(s->history.history(member,null,1));
        assertThat(first.items().get(0).consentId()).isEqualTo(ids.get(1));
        assertThat(first.hasNext()).isTrue();
        tx.executeWithoutResult(s->{members.lockActive(member);store.append(member,version,true,now.plusSeconds(10));});
        var next=tx.execute(s->history.history(member,first.nextCursor(),100));
        assertThat(next.items()).extracting(kr.nospo.terms.contract.MemberConsents.Event::consentId).containsExactly(ids.get(0),ids.get(2));
        assertThat(next.hasNext()).isFalse();assertThat(next.nextCursor()).isNull();
        db.update("DELETE FROM member WHERE member_id=?",id(member));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM member_consent",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM term_version",Integer.class)).isEqualTo(1);
    }
}
