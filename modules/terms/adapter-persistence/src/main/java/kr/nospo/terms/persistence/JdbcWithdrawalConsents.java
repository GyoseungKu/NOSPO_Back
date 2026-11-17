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
        for(var row:mapper.readTree(snapshot))
            db.update("INSERT INTO member_consent(consent_id,member_id,term_version_id,agreed,agreed_at) VALUES (?,?,?,?,?)",
                    ArchivedRow.binary(row,"consent_id"),id(member),ArchivedRow.binary(row,"term_version_id"),row.path("agreed").asBoolean(),ArchivedRow.time(row,"agreed_at"));
    }
}
