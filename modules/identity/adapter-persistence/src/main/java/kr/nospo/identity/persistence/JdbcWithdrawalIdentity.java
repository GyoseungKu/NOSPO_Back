package kr.nospo.identity.persistence;

import kr.nospo.identity.contract.WithdrawalIdentity;
import kr.nospo.support.*;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcWithdrawalIdentity implements WithdrawalIdentity {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final WithdrawalReservations reservations;
    public JdbcWithdrawalIdentity(JdbcTemplate db,ObjectMapper mapper,WithdrawalReservations reservations){
        this.db=db;this.mapper=mapper;this.reservations=reservations;
    }
    public void verify(UUID member,UUID session,byte[] proofHash,Instant now){
        if(db.query("SELECT m.member_id FROM member m JOIN auth_session s ON s.member_id=m.member_id"
                +" WHERE m.member_id=? AND m.status='ACTIVE' AND s.session_id=? AND s.revoked_at IS NULL"
                +" AND s.expires_at>? FOR UPDATE",(r,n)->r.getBytes(1),id(member),id(session),at(now)).isEmpty())
            throw new ApiException(401,"INVALID_CREDENTIALS");
        var valid=db.query("SELECT proof_id FROM auth_proof WHERE proof_hash=? AND member_id=? AND session_id=?"
                +" AND purpose='WITHDRAWAL' AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at>? FOR UPDATE",
                (r,n)->r.getBytes(1),proofHash,id(member),id(session),at(now));
        if(valid.isEmpty())throw new ApiException(400,"INVALID_PROOF");
        db.update("UPDATE auth_proof SET consumed_at=? WHERE proof_id=?",at(now),valid.getFirst());
    }
    public byte[] snapshot(UUID member){
        Map<String,byte[]> result=new LinkedHashMap<>();
        for(String table:List.of("account","password_credential","social_identity"))
            result.put(table,ArchiveRows.read(db,mapper,"SELECT * FROM "+table+" WHERE member_id=?",id(member)));
        return mapper.writeValueAsBytes(result);
    }
    public void retain(UUID operation,UUID member,Instant now){
        String email=db.queryForObject("SELECT email_key FROM account WHERE member_id=?",String.class,id(member));
        reservations.reserve(operation,"EMAIL",email,now);
        for(String subject:db.queryForList("SELECT provider_subject FROM social_identity WHERE member_id=?",String.class,id(member))){
            reservations.reserve(operation,"KAKAO",subject,now);
            // Parallel pre-signup flows can predate the successful signup. Attribute only
            // this exact provider identity, never unrelated proofs with the same email.
            db.update("UPDATE auth_proof SET member_id=? WHERE member_id IS NULL AND purpose='KAKAO_SIGNUP'"
                    +" AND provider='KAKAO' AND provider_subject=?",id(member),subject);
        }
        db.update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,?),revoke_reason='WITHDRAWAL' WHERE member_id=?",at(now),id(member));
        db.update("UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,?) WHERE session_id IN"
                +" (SELECT session_id FROM auth_session WHERE member_id=?)",at(now),id(member));
        // Ownership, not just the current email, includes old addresses and session-bound flows.
        var signupFlows=db.query("SELECT c.flow_id FROM social_signup_context c JOIN auth_proof p"
                +" ON p.proof_id=c.proof_id WHERE p.member_id=?",(r,n)->r.getBytes(1),id(member));
        db.update("DELETE FROM social_signup_context WHERE proof_id IN (SELECT proof_id FROM auth_proof WHERE member_id=?)"
                +" OR flow_id IN (SELECT flow_id FROM oauth_flow WHERE member_id=?)",id(member),id(member));
        for(byte[] flow:signupFlows){
            db.update("DELETE FROM oauth_exchange WHERE flow_id=?",flow);
            db.update("DELETE FROM oauth_flow WHERE flow_id=? AND NOT EXISTS"
                    +" (SELECT 1 FROM social_signup_context c WHERE c.flow_id=oauth_flow.flow_id)",flow);
        }
        db.update("DELETE FROM oauth_exchange WHERE flow_id IN (SELECT flow_id FROM oauth_flow WHERE member_id=?"
                +" OR reauth_proof_id IN (SELECT proof_id FROM auth_proof WHERE member_id=?))",id(member),id(member));
        db.update("DELETE FROM oauth_flow WHERE member_id=? OR reauth_proof_id IN"
                +" (SELECT proof_id FROM auth_proof WHERE member_id=?)",id(member),id(member));
        db.update("UPDATE email_delivery SET status='CANCELLED',payload_ciphertext=NULL,payload_key_id=NULL,"
                +"cancellation_requested_at=?,finished_at=?,lease_token=NULL,lease_until=NULL,updated_at=?"
                +" WHERE member_id=? OR challenge_id IN (SELECT challenge_id FROM auth_challenge WHERE member_id=?)",
                at(now),at(now),at(now),id(member),id(member));
        // A password-reset verification owns its challenge lock before issuing a proof.
        // Lock/invalidate challenges FIRST, then scrub proofs, so a verifier cannot recreate
        // plaintext proof targets behind the withdrawal transaction's scrub pass.
        db.update("UPDATE auth_challenge SET invalidated_at=COALESCE(invalidated_at,?),target_email='',target_email_key=''"
                +" WHERE member_id=?",at(now),id(member));
        db.update("UPDATE auth_proof SET invalidated_at=COALESCE(invalidated_at,?),target_email=NULL,target_email_key=NULL,"
                +"provider_subject=NULL WHERE member_id=?",at(now),id(member));
        db.update("DELETE FROM idempotency_record WHERE member_id=?",id(member));
        db.update("DELETE FROM social_identity WHERE member_id=?",id(member));
        db.update("DELETE FROM password_credential WHERE member_id=?",id(member));
        db.update("UPDATE account SET email=NULL,email_key=NULL,updated_at=? WHERE member_id=?",at(now),id(member));
    }
}
