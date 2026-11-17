package kr.nospo.terms.persistence;

import kr.nospo.terms.contract.WithdrawalConsents;
import kr.nospo.support.ArchiveRows;
import kr.nospo.support.ArchivedRow;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

@Repository
public class JdbcWithdrawalConsents implements WithdrawalConsents {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    public JdbcWithdrawalConsents(JdbcTemplate db,ObjectMapper mapper){this.db=db;this.mapper=mapper;}
    public byte[] snapshot(UUID member){return ArchiveRows.read(db,mapper,"SELECT * FROM member_consent WHERE member_id=?",id(member));}
    public void remove(UUID member){db.update("DELETE FROM member_consent WHERE member_id=?",id(member));}
    public void restore(UUID member,byte[] snapshot) {
        var rows = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        mapper.readTree(snapshot).forEach(rows::add);
        // V1 archives created before V8 have no event_order. Their original tie order is
        // unknowable; use the same deterministic time/ID order as the V8 backfill.
        boolean ordered = rows.stream().allMatch(row -> row.path("event_order").asLong(0) > 0);
        rows.sort((a,b) -> {
            if (ordered) return Long.compare(a.path("event_order").asLong(),b.path("event_order").asLong());
            int time = ArchivedRow.time(a,"agreed_at").compareTo(ArchivedRow.time(b,"agreed_at"));
            return time != 0 ? time : java.util.Arrays.compareUnsigned(ArchivedRow.binary(a,"consent_id"),ArchivedRow.binary(b,"consent_id"));
        });
        long order = 0;
        for(var row:rows)
            db.update("INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at,event_order) VALUES (?,?,?,?,?,?)",
                    ArchivedRow.binary(row,"consent_id"),id(member),ArchivedRow.binary(row,"term_version_id"),row.path("agreed").asBoolean(),ArchivedRow.time(row,"agreed_at"),++order);
    }
}
