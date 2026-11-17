package kr.nospo.identity.persistence;

import kr.nospo.identity.application.RecoveryEvidenceStore;
import kr.nospo.identity.contract.RecoveryIdentity.Evidence;
import kr.nospo.support.*;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcRecoveryEvidenceStore implements RecoveryEvidenceStore {
    private final JdbcTemplate db;
    private final Secrets secrets;
    public JdbcRecoveryEvidenceStore(JdbcTemplate db,Secrets secrets) {this.db=db;this.secrets=secrets;}
    public Optional<Target> target(String kind,String identifier) {
        byte[] hash=secrets.fingerprint("withdrawal-identity:v1:"+kind+":"+identifier);
        return db.query("SELECT o.member_id,o.operation_id,o.recoverable_until FROM withdrawal_identity i"
                +" JOIN withdrawal_operation o ON o.operation_id=i.operation_id WHERE i.kind=? AND i.identifier_hash=? AND o.status='RETAINED'",
                (r,n)->new Target(uuid(r.getBytes(1)),uuid(r.getBytes(2)),r.getTimestamp(3).toInstant()),kind,hash).stream().findFirst();
    }
    public void lock(Target target,Instant now) {
        if(db.query("SELECT member_id FROM member WHERE member_id=? AND status='WITHDRAWN' AND recoverable_until>?"
                +" AND withdrawal_generation=(SELECT generation FROM withdrawal_operation WHERE operation_id=? AND status='RETAINED' AND member_id=?) FOR UPDATE",
                (r,n)->r.getBytes(1),id(target.member()),at(now),id(target.operation()),id(target.member())).isEmpty())
            throw new ApiException(409,"ACCOUNT_NOT_RECOVERABLE");
    }
    public void request(Target target,UUID challenge,String email,byte[] hash,Instant now) {
        var resend=db.query("SELECT resend_available_at FROM auth_challenge WHERE withdrawal_operation_id=?"
                +" AND purpose='RECOVERY' AND resend_available_at>?",(r,n)->r.getTimestamp(1).toInstant(),id(target.operation()),at(now));
        if(!resend.isEmpty()) throw new ApiException(429,"RATE_LIMITED",60);
        db.update("UPDATE auth_challenge SET invalidated_at=? WHERE withdrawal_operation_id=? AND purpose='RECOVERY' AND invalidated_at IS NULL",at(now),id(target.operation()));
        db.update("UPDATE email_delivery SET status='CANCELLED',payload_ciphertext=NULL,payload_key_id=NULL,finished_at=?,"
                +"cancellation_requested_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE challenge_id IN"
                +" (SELECT challenge_id FROM auth_challenge WHERE withdrawal_operation_id=? AND purpose='RECOVERY') AND status IN ('PENDING','PROCESSING')",
                at(now),at(now),at(now),id(target.operation()));
        db.update("UPDATE auth_proof SET invalidated_at=? WHERE withdrawal_operation_id=? AND purpose='RECOVERY_EMAIL' AND consumed_at IS NULL",at(now),id(target.operation()));
        db.update("INSERT INTO auth_challenge(challenge_id,member_id,purpose,target_email,target_email_key,secret_digest,created_at,expires_at,resend_available_at,withdrawal_operation_id)"
                +" VALUES (?,?,'RECOVERY',?,?,?,?,?,?,?)",id(challenge),id(target.member()),email,email,hash,at(now),at(now.plusSeconds(600)),at(now.plusSeconds(60)),id(target.operation()));
    }
    public Optional<Challenge> challenge(byte[] hash) {
        return db.query("SELECT c.*,o.recoverable_until FROM auth_challenge c JOIN withdrawal_operation o ON o.operation_id=c.withdrawal_operation_id"
                +" WHERE c.purpose='RECOVERY' AND c.secret_digest=?",(r,n)->new Challenge(uuid(r.getBytes("challenge_id")),
                new Target(uuid(r.getBytes("member_id")),uuid(r.getBytes("withdrawal_operation_id")),r.getTimestamp("recoverable_until").toInstant()),
                r.getTimestamp("expires_at").toInstant(),r.getTimestamp("verified_at")==null?null:r.getTimestamp("verified_at").toInstant(),
                r.getTimestamp("invalidated_at")==null?null:r.getTimestamp("invalidated_at").toInstant()),hash).stream().findFirst();
    }
    public void verify(UUID challenge,Instant now) {db.update("UPDATE auth_challenge SET verified_at=?,target_email='',target_email_key='' WHERE challenge_id=?",at(now),id(challenge));}
    public void proof(Target target,String method,byte[] hash,Instant now,Instant expiry) {
        db.update("INSERT INTO auth_proof(proof_id,member_id,purpose,proof_hash,created_at,expires_at,withdrawal_operation_id) VALUES (?,?,?,?,?,?,?)",
                id(UUID.randomUUID()),id(target.member()),"RECOVERY_"+method,hash,at(now),at(expiry),id(target.operation()));
    }
    public Evidence inspect(byte[] hash) {
        return db.query("SELECT member_id,withdrawal_operation_id,purpose FROM auth_proof WHERE proof_hash=? AND purpose IN ('RECOVERY_EMAIL','RECOVERY_KAKAO')",
                (r,n)->new Evidence(uuid(r.getBytes(1)),uuid(r.getBytes(2)),r.getString(3).substring(9)),hash).stream().findFirst()
                .orElseThrow(()->new ApiException(400,"INVALID_PROOF"));
    }
    public void consume(byte[] hash,Evidence e,Instant now) {
        if(db.update("UPDATE auth_proof SET consumed_at=? WHERE proof_hash=? AND member_id=? AND withdrawal_operation_id=? AND purpose=?"
                +" AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at>?",at(now),hash,id(e.member()),id(e.operation()),"RECOVERY_"+e.method(),at(now))!=1)
            throw new ApiException(400,"INVALID_PROOF");
    }
}
