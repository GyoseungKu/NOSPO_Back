package kr.nospo.member.persistence;

import kr.nospo.member.contract.WithdrawalMembers;
import kr.nospo.support.*;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcWithdrawalMembers implements WithdrawalMembers {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    public JdbcWithdrawalMembers(JdbcTemplate db, ObjectMapper mapper) { this.db=db; this.mapper=mapper; }
    public long lock(UUID member) {
        return db.query("SELECT withdrawal_generation FROM member WHERE member_id=? FOR UPDATE",
                (r,n)->r.getLong(1), id(member)).stream().findFirst()
                .orElseThrow(()->new ApiException(401,"INVALID_CREDENTIALS"));
    }
    public byte[] snapshot(UUID member) {
        return mapper.writeValueAsBytes(Map.of(
                "member",ArchiveRows.read(db,mapper,"SELECT * FROM member WHERE member_id=?",id(member)),
                "images",ArchiveRows.read(db,mapper,"SELECT * FROM member_profile_image WHERE member_id=?",id(member))));
    }
    public void retain(UUID member, Instant now, Instant deadline) {
        db.update("DELETE FROM member_profile_image WHERE member_id=?",id(member));
        if(db.update("UPDATE member SET status='WITHDRAWN',nickname=NULL,nickname_key=NULL,phone_number=NULL,"
                +"profile_image_key=NULL,withdrawal_generation=withdrawal_generation+1,withdrawn_at=?,"
                +"recoverable_until=?,updated_at=? WHERE member_id=? AND status='ACTIVE'",
                at(now),at(deadline),at(now),id(member))!=1) throw new ApiException(401,"INVALID_CREDENTIALS");
    }
}
