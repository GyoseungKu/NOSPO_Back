package kr.nospo.lifecycle.persistence;

import kr.nospo.lifecycle.application.*;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcWithdrawalStore implements WithdrawalStore {
    private final JdbcTemplate db;
    public JdbcWithdrawalStore(JdbcTemplate db){this.db=db;}
    public Optional<Saved> saved(UUID member,UUID key){
        return db.query("SELECT session_id,request_fingerprint FROM withdrawal_operation WHERE member_id=? AND idempotency_key=?",
                (r,n)->new Saved(uuid(r.getBytes(1)),r.getBytes(2)),id(member),id(key)).stream().findFirst();
    }
    public void save(UUID operation,UUID member,long generation,UUID session,UUID key,byte[] fingerprint,
            Instant now,Instant deadline,WithdrawalArchiveCrypto.Encrypted archive){
        db.update("INSERT INTO withdrawal_operation(operation_id,member_id,generation,session_id,idempotency_key,"
                +"request_fingerprint,status,withdrawn_at,recoverable_until) VALUES (?,?,?,?,?,?,'RETAINED',?,?)",
                id(operation),id(member),generation,id(session),id(key),fingerprint,at(now),at(deadline));
        db.update("INSERT INTO withdrawal_archive(operation_id,format_version,payload_ciphertext,wrapped_data_key,wrapping_key_id)"
                +" VALUES (?,1,?,?,'auth-encryption-v1')",id(operation),archive.ciphertext(),archive.wrappedKey());
    }
}
