package kr.nospo.identity.persistence;

import kr.nospo.identity.contract.PurgeIdentity;
import static kr.nospo.support.SqlIds.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class JdbcPurgeIdentity implements PurgeIdentity {
    private final JdbcTemplate db;
    public JdbcPurgeIdentity(JdbcTemplate db) { this.db = db; }
    public void delete(UUID member) {
        byte[] owner = id(member);
        // Ownership only: never delete by email/provider subject, which can belong to a NEW member.
        String sessions = "SELECT session_id FROM auth_session WHERE member_id=?";
        String challenges = "SELECT challenge_id FROM auth_challenge WHERE member_id=? OR session_id IN (" + sessions + ")"
                + " OR withdrawal_operation_id IN (SELECT operation_id FROM withdrawal_operation WHERE member_id=?)"
                + " OR challenge_id IN (SELECT challenge_id FROM auth_proof WHERE member_id=? OR session_id IN (" + sessions + ")"
                + " OR withdrawal_operation_id IN (SELECT operation_id FROM withdrawal_operation WHERE member_id=?))";
        String proofs = "SELECT proof_id FROM auth_proof WHERE member_id=? OR session_id IN (" + sessions + ")"
                + " OR withdrawal_operation_id IN (SELECT operation_id FROM withdrawal_operation WHERE member_id=?)"
                + " OR challenge_id IN (" + challenges + ")";
        Object[] challengeArgs = {owner, owner, owner, owner, owner, owner};
        Object[] proofArgs = concat(new Object[]{owner,owner,owner},challengeArgs);
        // Inconsistent cross-member references are not permission to erase another member's rows.
        var challengeIds=db.query("SELECT challenge_id,member_id FROM auth_challenge WHERE challenge_id IN ("+challenges+")",
                (r,n)->ownedId(r.getBytes(1),r.getBytes(2),owner),challengeArgs);
        var proofIds=db.query("SELECT proof_id,member_id FROM auth_proof WHERE proof_id IN ("+proofs+")",
                (r,n)->ownedId(r.getBytes(1),r.getBytes(2),owner),proofArgs);
        // Context-owned flows can have NULL member/session. Capture before deleting their contexts.
        var flows = db.query("SELECT flow_id,member_id FROM oauth_flow WHERE member_id=? OR session_id IN (" + sessions + ")"
                + " OR reauth_proof_id IN (" + proofs + ") OR flow_id IN (SELECT flow_id FROM social_signup_context WHERE proof_id IN (" + proofs + "))",
                (r,n) -> ownedId(r.getBytes(1),r.getBytes(2),owner), concat(new Object[]{owner,owner}, proofArgs, proofArgs));
        db.query("SELECT delivery_id,member_id FROM email_delivery WHERE member_id=? OR challenge_id IN ("+challenges+")",
                (r,n)->ownedId(r.getBytes(1),r.getBytes(2),owner),concat(new Object[]{owner},challengeArgs));
        for (byte[] flow : flows) {
            db.query("SELECT c.proof_id,p.member_id FROM social_signup_context c JOIN auth_proof p ON p.proof_id=c.proof_id WHERE c.flow_id=?",
                    (r,n)->ownedId(r.getBytes(1),r.getBytes(2),owner),flow);
            db.update("DELETE FROM social_signup_context WHERE flow_id=?", flow);
            db.update("DELETE FROM oauth_exchange WHERE flow_id=?", flow);
            db.update("DELETE FROM oauth_flow WHERE flow_id=?", flow);
        }
        db.update("DELETE FROM social_signup_context WHERE proof_id IN (" + proofs + ")", proofArgs);
        db.update("DELETE FROM email_delivery WHERE member_id=? OR challenge_id IN (" + challenges + ")",
                concat(new Object[]{owner},challengeArgs));
        // No FK from a flow remains before deleting its reauth proof.
        for(byte[] proof:proofIds) db.update("DELETE FROM auth_proof WHERE proof_id=?",proof);
        for(byte[] challenge:challengeIds) db.update("DELETE FROM auth_challenge WHERE challenge_id=?",challenge);
        db.update("DELETE FROM refresh_token WHERE session_id IN (" + sessions + ")", owner);
        db.update("DELETE FROM auth_session WHERE member_id=?", owner);
        db.update("DELETE FROM social_identity WHERE member_id=?", owner);
        db.update("DELETE FROM password_credential WHERE member_id=?", owner);
        db.update("DELETE FROM account WHERE member_id=?", owner);
    }
    private Object[] concat(Object[]... arrays) {
        return Arrays.stream(arrays).flatMap(Arrays::stream).toArray();
    }
    private byte[] ownedId(byte[] row,byte[] actualOwner,byte[] expectedOwner) {
        if(actualOwner!=null && !Arrays.equals(actualOwner,expectedOwner))
            throw new IllegalStateException("PURGE_CROSS_MEMBER_REFERENCE");
        return row;
    }
}
