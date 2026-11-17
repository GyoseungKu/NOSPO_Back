package kr.nospo.terms.persistence;

import static kr.nospo.support.SqlIds.*;
import kr.nospo.terms.application.ConsentStore;
import kr.nospo.terms.contract.MemberConsents.Event;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcConsentStore implements ConsentStore {
    private final JdbcTemplate db;
    public JdbcConsentStore(JdbcTemplate db) { this.db=db; }

    private Event event(ResultSet rs) throws SQLException {
        return new Event(uuid(rs.getBytes("consent_id")),uuid(rs.getBytes("term_version_id")),
                rs.getBoolean("agreed"),rs.getTimestamp("agreed_at").toInstant());
    }

    public List<Ordered> events(UUID member) {
        return db.query("SELECT * FROM member_consent WHERE member_id=? ORDER BY event_order DESC",
                (rs,n)->new Ordered(event(rs),rs.getLong("event_order")),id(member));
    }

    public long ceiling(UUID member) {
        return db.queryForObject("SELECT COALESCE(MAX(event_order),0) FROM member_consent WHERE member_id=?",Long.class,id(member));
    }

    public List<Event> history(UUID member, Position position, int count) {
        String sql="SELECT * FROM member_consent WHERE member_id=? AND event_order<=?";
        List<Object> args=new ArrayList<>(List.of(id(member),position.ceiling()));
        if(position.agreedAt()!=null) {
            sql+=" AND (agreed_at<? OR (agreed_at=? AND consent_id<?))";
            args.add(at(position.agreedAt()));args.add(at(position.agreedAt()));args.add(id(position.consentId()));
        }
        sql+=" ORDER BY agreed_at DESC,consent_id DESC LIMIT ?";
        args.add(count);
        return db.query(sql,(rs,n)->event(rs),args.toArray());
    }

    public Event append(UUID member, UUID version, boolean agreed, Instant now) {
        UUID consent=UUID.randomUUID();
        db.update("INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at,event_order)"
                +" SELECT ?,?,?,?,?,COALESCE(MAX(event_order),0)+1 FROM member_consent WHERE member_id=?",
                id(consent),id(member),id(version),agreed,at(now),id(member));
        return db.queryForObject("SELECT * FROM member_consent WHERE consent_id=?",(rs,n)->event(rs),id(consent));
    }
}
