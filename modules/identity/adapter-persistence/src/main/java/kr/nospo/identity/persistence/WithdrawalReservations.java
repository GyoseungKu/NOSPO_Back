package kr.nospo.identity.persistence;

import kr.nospo.support.*;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Stage 1 intentionally reserves expired identities too, until stage 2 safely purges them. */
@Component
public class WithdrawalReservations {
    private final JdbcTemplate db;
    private final Secrets secrets;
    private final DatabaseGuard guard;
    public WithdrawalReservations(JdbcTemplate db,Secrets secrets,DatabaseGuard guard){this.db=db;this.secrets=secrets;this.guard=guard;}
    private byte[] hash(String kind,String value){return secrets.fingerprint("withdrawal-identity:v1:"+kind+":"+value);}
    public Optional<UUID> owner(String kind,String value){
        return db.query("SELECT o.member_id FROM withdrawal_identity i JOIN withdrawal_operation o"
                +" ON o.operation_id=i.operation_id WHERE i.kind=? AND i.identifier_hash=?",
                (r,n)->uuid(r.getBytes(1)),kind,hash(kind,value)).stream().findFirst();
    }
    public void lock(String kind,String value,Instant now){
        guard.lock("withdrawal-reservation:"+kind+":"+HexFormat.of().formatHex(hash(kind,value)),now);
    }
    public void reserve(UUID operation,String kind,String value,Instant now){
        lock(kind,value,now);
        db.update("INSERT INTO withdrawal_identity(kind,identifier_hash,operation_id) VALUES (?,?,?)",
                kind,hash(kind,value),id(operation));
    }
}
