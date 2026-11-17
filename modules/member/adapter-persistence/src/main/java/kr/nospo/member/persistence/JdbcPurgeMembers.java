package kr.nospo.member.persistence;

import kr.nospo.member.contract.PurgeMembers;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class JdbcPurgeMembers implements PurgeMembers {
    private final JdbcTemplate db;
    public JdbcPurgeMembers(JdbcTemplate db) { this.db = db; }
    public void delete(UUID member, long generation) {
        if (db.update("DELETE FROM member WHERE member_id=? AND status='WITHDRAWN' AND withdrawal_generation=?",
                id(member), generation) != 1)
            throw new IllegalStateException("PURGE_MEMBER_FENCE_FAILED");
    }
}
