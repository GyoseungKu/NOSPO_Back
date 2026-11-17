package kr.nospo.lifecycle.persistence;

import kr.nospo.lifecycle.application.PurgeStore;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

@Repository
public class JdbcPurgeStore implements PurgeStore {
    private final JdbcTemplate db;
    private final Clock clock;
    public JdbcPurgeStore(JdbcTemplate db,Clock clock) { this.db=db; this.clock=clock; }
    public List<Candidate> due(Instant now,int limit) {
        return db.query("SELECT o.member_id,o.operation_id,o.generation FROM withdrawal_operation o JOIN member m ON m.member_id=o.member_id"
                + " WHERE o.status IN ('RETAINED','PURGING') AND m.status='WITHDRAWN' AND m.withdrawal_generation=o.generation"
                + " AND o.recoverable_until<=? AND m.recoverable_until<=? AND NOT EXISTS (SELECT 1 FROM withdrawal_target t"
                + " WHERE t.operation_id=o.operation_id AND t.resource_type='DATABASE' AND ((t.status='PROCESSING' AND t.lease_until>?)"
                + " OR (t.status='PENDING' AND t.next_attempt_at>?) OR t.status='COMPLETED')) ORDER BY o.recoverable_until,o.operation_id LIMIT ?",
                (r,n)->new Candidate(uuid(r.getBytes(1)),uuid(r.getBytes(2)),r.getLong(3)),at(now),at(now),at(now),at(now),limit);
    }
    public boolean lockEligible(Candidate c,Instant ignored) {
        // Always acquire the member lock BEFORE any operation or job lock, like RecoveryService.
        var member=db.query("SELECT status,withdrawal_generation,recoverable_until FROM member WHERE member_id=? FOR UPDATE",
                (r,n)->new State(r.getString(1),r.getLong(2),r.getTimestamp(3)==null?null:r.getTimestamp(3).toInstant()),id(c.member()));
        if(member.isEmpty()) return false;
        Instant now=clock.instant();
        var m=member.getFirst();
        if(!"WITHDRAWN".equals(m.status()) || m.generation()!=c.generation() || m.deadline()==null || m.deadline().isAfter(now)) return false;
        return !db.query("SELECT operation_id FROM withdrawal_operation WHERE operation_id=? AND member_id=? AND generation=?"
                + " AND status IN ('RETAINED','PURGING') AND recoverable_until<=? FOR UPDATE",
                (r,n)->r.getBytes(1),id(c.operation()),id(c.member()),c.generation(),at(now)).isEmpty();
    }
    public Optional<Lease> claim(Candidate c,Instant now) {
        var targets=db.query("SELECT target_id,status,attempt_count,next_attempt_at,lease_until FROM withdrawal_target"
                + " WHERE operation_id=? AND resource_type='DATABASE' FOR UPDATE",(r,n)->new Target(uuid(r.getBytes(1)),r.getString(2),r.getInt(3),
                r.getTimestamp(4).toInstant(),r.getTimestamp(5)==null?null:r.getTimestamp(5).toInstant()),id(c.operation()));
        if(targets.size()>1) throw new IllegalStateException("PURGE_DUPLICATE_DATABASE_TARGET");
        UUID token=UUID.randomUUID(),target;
        int attempts;
        if(targets.isEmpty()) {
            target=UUID.randomUUID(); attempts=1;
            // DATABASE targets need no external locator; the operation FK is the complete locator.
            db.update("INSERT INTO withdrawal_target(target_id,operation_id,resource_type,locator_ciphertext,status,attempt_count,next_attempt_at,lease_token,lease_until)"
                    + " VALUES (?,?,'DATABASE',?,'PROCESSING',1,?,?,?)",id(target),id(c.operation()),new byte[0],at(now),id(token),at(now.plusSeconds(120)));
        } else {
            var t=targets.getFirst();
            if("COMPLETED".equals(t.status()) || ("PENDING".equals(t.status()) && t.next().isAfter(now))
                    || ("PROCESSING".equals(t.status()) && t.until()!=null && t.until().isAfter(now))) return Optional.empty();
            target=t.id(); attempts=t.attempts()==Integer.MAX_VALUE?Integer.MAX_VALUE:t.attempts()+1;
            db.update("UPDATE withdrawal_target SET status='PROCESSING',attempt_count=?,lease_token=?,lease_until=? WHERE target_id=?",
                    attempts,id(token),at(now.plusSeconds(120)),id(target));
        }
        db.update("UPDATE withdrawal_operation SET status='PURGING' WHERE operation_id=?",id(c.operation()));
        return Optional.of(new Lease(c,target,token,attempts));
    }
    public boolean owns(Lease lease,Instant now) {
        if(db.queryForObject("SELECT COUNT(*) FROM withdrawal_target WHERE operation_id IN"
                + " (SELECT operation_id FROM withdrawal_operation WHERE member_id=?) AND resource_type<>'DATABASE'",Integer.class,id(lease.candidate().member()))>0)
            throw new IllegalStateException("PURGE_UNSUPPORTED_RESOURCE_TARGET");
        return !db.query("SELECT t.target_id FROM withdrawal_target t JOIN withdrawal_operation o ON o.operation_id=t.operation_id"
                + " WHERE t.target_id=? AND t.operation_id=? AND t.lease_token=? AND t.status='PROCESSING' AND t.lease_until>? AND o.status='PURGING' FOR UPDATE",
                (r,n)->r.getBytes(1),id(lease.target()),id(lease.candidate().operation()),id(lease.token()),at(now)).isEmpty();
    }
    public void deleteRecords(Lease lease) {
        byte[] owner=id(lease.candidate().member());
        db.update("DELETE FROM idempotency_record WHERE member_id=?",owner);
        // Auth children with V7 RESTRICT references have already been deleted by identity's contract.
        // Includes older RESTORED operations, archives, HMAC indices and all locator/lease material.
        db.update("DELETE FROM withdrawal_operation WHERE member_id=?",owner);
    }
    public void retry(Lease lease,Instant now,Instant next) {
        db.update("UPDATE withdrawal_target SET status='PENDING',next_attempt_at=?,lease_token=NULL,lease_until=NULL"
                + " WHERE target_id=? AND operation_id=? AND lease_token=? AND status='PROCESSING'",
                at(next),id(lease.target()),id(lease.candidate().operation()),id(lease.token()));
    }
    private record State(String status,long generation,Instant deadline) {}
    private record Target(UUID id,String status,int attempts,Instant next,Instant until) {}
}
