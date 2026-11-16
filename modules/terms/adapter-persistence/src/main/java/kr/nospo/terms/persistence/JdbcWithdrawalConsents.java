package kr.nospo.terms.persistence;

import kr.nospo.terms.contract.WithdrawalConsents;
import kr.nospo.support.ArchiveRows;
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
}
