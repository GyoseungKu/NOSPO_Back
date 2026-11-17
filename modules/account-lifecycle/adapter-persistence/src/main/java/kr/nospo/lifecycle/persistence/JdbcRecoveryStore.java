package kr.nospo.lifecycle.persistence;

import kr.nospo.lifecycle.application.*;
import kr.nospo.support.ApiException;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcRecoveryStore implements RecoveryStore {
    private final JdbcTemplate db;
    public JdbcRecoveryStore(JdbcTemplate db) {this.db=db;}
    public WithdrawalArchiveCrypto.Encrypted archive(UUID member,UUID operation,Instant now) {
        return db.query("SELECT a.payload_ciphertext,a.wrapped_data_key FROM withdrawal_operation o"
                +" JOIN withdrawal_archive a ON a.operation_id=o.operation_id JOIN member m ON m.member_id=o.member_id"
                +" WHERE o.operation_id=? AND o.member_id=? AND o.status='RETAINED' AND m.status='WITHDRAWN'"
                +" AND m.withdrawal_generation=o.generation AND o.recoverable_until>? AND m.recoverable_until>? AND a.format_version=1 FOR UPDATE",
                (r,n)->new WithdrawalArchiveCrypto.Encrypted(r.getBytes(1),r.getBytes(2)),id(operation),id(member),at(now),at(now))
                .stream().findFirst().orElseThrow(()->new ApiException(409,"ACCOUNT_NOT_RECOVERABLE"));
    }
    public void complete(UUID operation,Instant now) {
        if(db.update("UPDATE withdrawal_operation SET status='RESTORED' WHERE operation_id=? AND status='RETAINED' AND recoverable_until>?",id(operation),at(now))!=1)
            throw new ApiException(409,"ACCOUNT_NOT_RECOVERABLE");
        db.update("DELETE FROM withdrawal_identity WHERE operation_id=?",id(operation));
        db.update("DELETE FROM withdrawal_archive WHERE operation_id=?",id(operation));
    }
}
