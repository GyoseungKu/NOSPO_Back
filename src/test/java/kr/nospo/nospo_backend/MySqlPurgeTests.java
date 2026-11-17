package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import kr.nospo.lifecycle.application.*;
import kr.nospo.member.contract.PurgeMembers;
import kr.nospo.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.util.*;
import java.util.concurrent.*;

/** Required Docker, isolated MySQL only: never run destructive purge tests against H2 or a developer DB. */
@Testcontainers
class MySqlPurgeTests extends RecoveryTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.7");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);
        p.add("spring.datasource.password",MYSQL::getPassword);p.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
    }
    @Autowired PurgeService purger;
    @Autowired PurgeStore purgeStore;
    @Autowired kr.nospo.identity.contract.PurgeIdentity purgeIdentity;
    @Autowired kr.nospo.terms.contract.WithdrawalConsents purgeConsents;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean PurgeMembers purgeMembers;

    PurgeStore.Candidate candidate(Account a) {
        return jdbc.queryForObject("SELECT member_id,operation_id,generation FROM withdrawal_operation WHERE member_id=? AND status='RETAINED'",
                (r,n)->new PurgeStore.Candidate(uuid(r.getBytes(1)),uuid(r.getBytes(2)),r.getLong(3)),id(a.member()));
    }
    void expired(Account a) { withdraw(a);clock.advance(30*86400); }
    int owned(String table,UUID member) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE member_id=?",Integer.class,id(member));}
    void absent(UUID member) {
        for(String table:List.of("member","account","password_credential","social_identity","auth_session","auth_proof","auth_challenge",
                "email_delivery","idempotency_record","member_profile_image","member_consent","credit_wallet","credit_transaction","withdrawal_operation"))
            assertThat(owned(table,member)).as(table).isZero();
    }
    @Test void purgeBoundaryAndOldCredentialsAreRejectedBeforeAndAfterDeletion() throws Exception {
        var a=emailAccount("purge@example.com","purge");withdraw(a);var c=candidate(a);
        clock.advance(30*86400-1);String proof=recoveryProof("purge@example.com");
        clock.now.set(clock.instant().plusNanos(999999000));
        assertThat(purger.processBatch()).isZero();assertThat(purger.claim(c)).isEmpty();
        clock.now.set(clock.instant().plusNanos(1000));
        code(()->recover(proof),"ACCOUNT_NOT_RECOVERABLE");
        assertThat(purgeStore.due(clock.instant(),50)).contains(c);
        assertThat(purger.processBatch()).isEqualTo(1);absent(a.member());
        for(String table:List.of("withdrawal_archive","withdrawal_identity","withdrawal_target","refresh_token","oauth_flow","oauth_exchange","social_signup_context"))
            assertThat(count(table)).as(table).isZero();
        code(()->recover(proof),"INVALID_PROOF");code(()->auth.refresh(a.signup().refreshToken()),"INVALID_CREDENTIALS");
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+a.signup().data().accessToken())).andExpect(status().isUnauthorized());
        assertThat(purger.claim(c)).isEmpty();assertThat(purger.processBatch()).isZero();
    }
    @Test void purgingCommitIrrevocablyFencesRecoveryWhileDeletionIsDelayed() {
        var a=emailAccount("delayed@example.com","delayed");withdraw(a);
        clock.advance(30*86400-1);String proof=recoveryProof("delayed@example.com");clock.advance(1);
        var lease=purger.claim(candidate(a)).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT status FROM withdrawal_operation",String.class)).isEqualTo("PURGING");
        code(()->recover(proof),"ACCOUNT_NOT_RECOVERABLE");
        assertThat(count("withdrawal_archive")).isEqualTo(1);
        assertThat(purger.complete(lease)).isTrue();absent(a.member());
    }
    @Test void emailRejoinCreatesNewWalletAndExactlyOneRewardWithoutChangingSignup() {
        var a=emailAccount("rejoin@example.com","rejoin");expired(a);assertThat(purger.processBatch()).isEqualTo(1);
        var command=command(proof("rejoin@example.com"),"rejoin");UUID key=UUID.randomUUID();
        var fresh=signup.signup(key,command);var replay=signup.signup(key,command);
        UUID member=fresh.data().member().memberId();assertThat(member).isNotEqualTo(a.member());
        assertThat(replay.data().member().memberId()).isEqualTo(member);absent(a.member());
        assertThat(owned("credit_wallet",member)).isEqualTo(1);assertThat(owned("credit_transaction",member)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE member_id=?",Integer.class,id(member))).isEqualTo(5);
        assertThat(purger.processBatch()).isZero();assertThat(signup.me(member).email()).isEqualTo("rejoin@example.com");
    }
    @Test void kakaoRejoinUsesFreshMemberIdAndOneReward() {
        var first=kakaoSignup.signup(UUID.randomUUID(),kakaoCommand(signupProof("12345","kakaopurge@example.com",true),"kakaopurge",null));
        var a=new Account(first,first.data().member().memberId(),UUID.fromString(decoder.decode(first.data().accessToken()).getClaimAsString("sid")));
        user("12345","kakaopurge@example.com",true);
        String proof=(String)finish(actions.reauthenticate(a.member(),a.session(),"WITHDRAWAL"),a).data().get("reauthProof");
        withdrawals.withdraw(a.member(),a.session(),UUID.randomUUID(),proof);clock.advance(30*86400);
        assertThat(purger.processBatch()).isEqualTo(1);
        var command=kakaoCommand(signupProof("12345","kakaopurge@example.com",true),"kakaopurge",null);UUID key=UUID.randomUUID();
        var fresh=kakaoSignup.signup(key,command);assertThat(fresh.data().member().memberId()).isNotEqualTo(a.member());
        assertThat(kakaoSignup.signup(key,command).data().member().memberId()).isEqualTo(fresh.data().member().memberId());
        assertThat(count("credit_transaction")).isEqualTo(1);assertThat(count("credit_wallet")).isEqualTo(1);absent(a.member());
        user("12345","kakaopurge@example.com",true);
        assertThat(oauth.exchange(callback(begin()),null,null,"test").data().get("result")).isEqualTo("LOGIN_SUCCESS");
    }
    @Test void restoredMemberAndStaleGenerationAreNeverDeleted() {
        var a=emailAccount("restored@example.com","restored");withdraw(a);var stale=candidate(a);
        var restored=recover(recoveryProof("restored@example.com"));clock.advance(30*86400);
        assertThat(purger.claim(stale)).isEmpty();assertThat(purger.processBatch()).isZero();
        assertThat(signup.me(a.member()).nickname()).isEqualTo("restored");
        // Obtain a fresh session because the recovery session expired while clock advanced.
        var login=auth.login("restored@example.com","replacement password","test");
        UUID session=UUID.fromString(decoder.decode(login.accessToken()).getClaimAsString("sid"));
        String proof=changes.reauthenticate(a.member(),session,"replacement password","WITHDRAWAL").reauthProof();
        withdrawals.withdraw(a.member(),session,UUID.randomUUID(),proof);
        assertThat(purger.claim(stale)).isEmpty();assertThat(purger.processBatch()).isZero();
        assertThat(jdbc.queryForObject("SELECT withdrawal_generation FROM member",Long.class)).isEqualTo(2);
        clock.advance(30*86400);assertThat(purger.processBatch()).isEqualTo(1);absent(a.member());
    }
    @Test void crashedWorkerLeaseExpiresAndOldWorkerCannotDeleteAfterReclaim() {
        var a=emailAccount("restart@example.com","restart");expired(a);var c=candidate(a);
        var first=purger.claim(c).orElseThrow();assertThat(purger.claim(c)).isEmpty();
        clock.advance(120);
        var restarted=new PurgeService(purgeStore,purgeIdentity,purgeMembers,purgeConsents,clock,transactionManager);
        var second=restarted.claim(c).orElseThrow();assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.attempts()).isEqualTo(2);assertThat(purger.complete(first)).isFalse();
        assertThat(restarted.complete(second)).isTrue();assertThat(purger.complete(second)).isFalse();absent(a.member());
    }
    @Test void failureAfterFinalMemberDeleteRollsBackEveryTableAndRetriesAfterRestart() {
        var a=emailAccount("rollbackpurge@example.com","rollbackpurge");expired(a);
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("INJECTED_PURGE_FAILURE");}).when(purgeMembers).delete(any(),anyLong());
        assertThat(purger.processBatch()).isZero();
        assertThat(owned("member",a.member())).isEqualTo(1);assertThat(owned("credit_wallet",a.member())).isEqualTo(1);
        assertThat(owned("credit_transaction",a.member())).isEqualTo(1);assertThat(owned("account",a.member())).isEqualTo(1);
        assertThat(owned("auth_session",a.member())).isEqualTo(1);assertThat(count("refresh_token")).isEqualTo(1);
        assertThat(count("withdrawal_archive")).isEqualTo(1);assertThat(count("withdrawal_identity")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM withdrawal_operation",String.class)).isEqualTo("PURGING");
        assertThat(jdbc.queryForObject("SELECT status FROM withdrawal_target",String.class)).isEqualTo("PENDING");
        assertThat(purger.processBatch()).isZero();doCallRealMethod().when(purgeMembers).delete(any(),anyLong());
        clock.advance(60);assertThat(purger.processBatch()).isEqualTo(1);absent(a.member());
    }
    @Test void duplicateWorkersAndDuplicateCompletionDeleteOnlyOnce() throws Exception {
        var a=emailAccount("duplicate@example.com","duplicate");expired(a);var c=candidate(a);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return purger.claim(c);});var second=pool.submit(()->{start.await();return purger.claim(c);});
            start.countDown();var leases=List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            assertThat(leases.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            var lease=leases.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            var one=pool.submit(()->purger.complete(lease));var two=pool.submit(()->purger.complete(lease));
            assertThat(List.of(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            absent(a.member());
        }
    }
    @Test void recoveryAndPurgeAtDeadlineCannotReactivateTheAccount() throws Exception {
        var a=emailAccount("recoveryrace@example.com","recoveryrace");withdraw(a);
        clock.advance(30*86400-1);String proof=recoveryProof("recoveryrace@example.com");clock.advance(1);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var restore=pool.submit(()->{start.await();try{recover(proof);return "UNEXPECTED_SUCCESS";}catch(ApiException e){return e.code();}});
            var purge=pool.submit(()->{start.await();return purger.processBatch();});start.countDown();
            assertThat(restore.get(30,TimeUnit.SECONDS)).isIn("ACCOUNT_NOT_RECOVERABLE","INVALID_PROOF","INVALID_CREDENTIALS");
            assertThat(purge.get(30,TimeUnit.SECONDS)).isEqualTo(1);absent(a.member());
        }
    }
    @Test void signupAndPurgeRacePreservesNewMemberAndReward() throws Exception {
        var a=emailAccount("signuprace@example.com","signuprace");expired(a);
        var old=purger.claim(candidate(a)).orElseThrow();assertThat(purger.complete(old)).isTrue();
        var command=command(proof("signuprace@example.com"),"freshsignup");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var join=pool.submit(()->{start.await();return signup.signup(UUID.randomUUID(),command);});
            var purge=pool.submit(()->{start.await();return purger.complete(old);});start.countDown();
            var fresh=join.get(30,TimeUnit.SECONDS);assertThat(purge.get(30,TimeUnit.SECONDS)).isFalse();UUID member=fresh.data().member().memberId();
            assertThat(member).isNotEqualTo(a.member());assertThat(signup.me(member).email()).isEqualTo("signuprace@example.com");
            assertThat(owned("credit_transaction",member)).isEqualTo(1);assertThat(count("credit_transaction")).isEqualTo(1);absent(a.member());
        }
    }
    @Test void signupVerificationRacingReservationReleaseCanRetryNormally() throws Exception {
        var a=emailAccount("reservation@example.com","reservation");expired(a);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var verify=pool.submit(()->{start.await();try{verification.request("reservation@example.com","test");return "ACCEPTED";}catch(ApiException e){return e.code();}});
            var purge=pool.submit(()->{start.await();return purger.processBatch();});start.countDown();
            String result=verify.get(30,TimeUnit.SECONDS);assertThat(result).isIn("ACCEPTED","EMAIL_ALREADY_EXISTS");
            assertThat(purge.get(30,TimeUnit.SECONDS)).isEqualTo(1);
            if(result.equals("EMAIL_ALREADY_EXISTS")) verification.request("reservation@example.com","test");
            delivery.processDue();String proof=verification.confirm(sent.get("reservation@example.com")).emailProof();
            var fresh=signup.signup(UUID.randomUUID(),command(proof,"reservation"));
            assertThat(fresh.data().member().memberId()).isNotEqualTo(a.member());assertThat(count("credit_transaction")).isEqualTo(1);absent(a.member());
        }
    }
    @Test void unrelatedActiveMemberAndSharedTermsSurviveWithoutOrphans() {
        var a=emailAccount("removed@example.com","removed");var b=emailAccount("protected@example.com","protected");
        loginMethods.recordVerifiedKakao(b.member(),"67890");expired(a);assertThat(purger.processBatch()).isEqualTo(1);absent(a.member());
        assertThat(signup.me(b.member()).email()).isEqualTo("protected@example.com");assertThat(count("term_version")).isEqualTo(3);
        assertThat(owned("credit_transaction",b.member())).isEqualTo(1);
        var edges=jdbc.query("SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                +" WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL",(r,n)->List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));
        assertThat(edges).isNotEmpty();
        for(var e:edges) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `"+e.get(0)+"` c LEFT JOIN `"+e.get(2)+"` p ON c.`"+e.get(1)+"`=p.`"+e.get(3)+"`"
                    +" WHERE c.`"+e.get(1)+"` IS NOT NULL AND p.`"+e.get(3)+"` IS NULL",Integer.class)).as(e.get(0)+" FK").isZero();
        }
    }
    @Test void unknownExternalTargetsAreRetriedWithoutDeletingDataOrPretendingSuccess() {
        var a=emailAccount("external@example.com","external");expired(a);var c=candidate(a);
        jdbc.update("INSERT INTO withdrawal_target(target_id,operation_id,resource_type,locator_ciphertext,status,next_attempt_at) VALUES (?,?,'UNKNOWN',?,'PENDING',?)",
                id(UUID.randomUUID()),id(c.operation()),new byte[]{1},at(clock.instant()));
        assertThat(purger.processBatch()).isZero();assertThat(owned("member",a.member())).isEqualTo(1);
        assertThat(count("withdrawal_archive")).isEqualTo(1);assertThat(count("withdrawal_target")).isEqualTo(2);
    }
    @Test void inconsistentCrossMemberReferencesFailClosedInsteadOfDeletingOtherMembersProof() {
        var a=emailAccount("corruptold@example.com","corruptold");var b=emailAccount("corruptnew@example.com","corruptnew");expired(a);var c=candidate(a);
        UUID proof=UUID.randomUUID();
        jdbc.update("INSERT INTO auth_proof(proof_id,member_id,purpose,proof_hash,created_at,expires_at,withdrawal_operation_id) VALUES (?,?,'RECOVERY_EMAIL',?,?,?,?)",
                id(proof),id(b.member()),secrets.hash(secrets.token()),at(clock.instant()),at(clock.instant().plusSeconds(600)),id(c.operation()));
        assertThat(purger.processBatch()).isZero();assertThat(owned("member",a.member())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_proof WHERE proof_id=?",Integer.class,id(proof))).isEqualTo(1);
        assertThat(signup.me(b.member()).nickname()).isEqualTo("corruptnew");
    }
    @Test void recoveryWinningBeforeDeadlineRemainsActiveDespiteConcurrentPurgeCandidate() throws Exception {
        var a=emailAccount("winning@example.com","winning");withdraw(a);var c=candidate(a);
        clock.advance(30*86400-1);String proof=recoveryProof("winning@example.com");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var restore=pool.submit(()->{start.await();return recover(proof);});
            var purge=pool.submit(()->{start.await();return purger.claim(c);});start.countDown();
            assertThat(restore.get(30,TimeUnit.SECONDS).data().member().memberId()).isEqualTo(a.member());
            assertThat(purge.get(30,TimeUnit.SECONDS)).isEmpty();clock.advance(1);
            assertThat(purger.processBatch()).isZero();assertThat(signup.me(a.member()).nickname()).isEqualTo("winning");
        }
    }
    @Test void residualOwnedOAuthMailProfileAndCredentialRowsAreRemovedChildFirst() {
        var a=emailAccount("residual@example.com","residual");expired(a);var c=candidate(a);
        UUID challenge=UUID.randomUUID(),proof=UUID.randomUUID(),flow=UUID.randomUUID();
        // Seed isolated, already invalid historical rows to exercise every RESTRICT edge, not authentication bypass.
        jdbc.update("INSERT INTO auth_challenge(challenge_id,member_id,session_id,purpose,target_email,target_email_key,secret_digest,created_at,expires_at,resend_available_at,invalidated_at,withdrawal_operation_id)"
                +" VALUES (?,?,?,'RECOVERY','historical@example.com','historical@example.com',?,?,?,?,?,?)",
                id(challenge),id(a.member()),id(a.session()),secrets.hash(secrets.token()),at(clock.instant().minusSeconds(700)),at(clock.instant().minusSeconds(100)),at(clock.instant().minusSeconds(600)),at(clock.instant().minusSeconds(100)),id(c.operation()));
        jdbc.update("INSERT INTO auth_proof(proof_id,challenge_id,member_id,session_id,purpose,proof_hash,target_email,provider_subject,created_at,expires_at,invalidated_at,withdrawal_operation_id)"
                +" VALUES (?,?,?,?,'RECOVERY_EMAIL',?,'historical@example.com','12345',?,?,?,?)",
                id(proof),id(challenge),id(a.member()),id(a.session()),secrets.hash(secrets.token()),at(clock.instant().minusSeconds(700)),at(clock.instant().minusSeconds(100)),at(clock.instant().minusSeconds(100)),id(c.operation()));
        jdbc.update("INSERT INTO oauth_flow(flow_id,state_hash,cookie_hash,member_id,session_id,return_path,created_at,expires_at,reauth_proof_id) VALUES (?,?,?,?,?,'/',?,?,?)",
                id(flow),secrets.hash(secrets.token()),secrets.hash(secrets.token()),id(a.member()),id(a.session()),at(clock.instant().minusSeconds(700)),at(clock.instant().minusSeconds(100)),id(proof));
        jdbc.update("INSERT INTO oauth_exchange(exchange_id,flow_id,code_hash,payload_ciphertext,expires_at) VALUES (?,?,?,?,?)",id(UUID.randomUUID()),id(flow),secrets.hash(secrets.token()),new byte[]{1},at(clock.instant().minusSeconds(100)));
        jdbc.update("INSERT INTO social_signup_context(proof_id,flow_id,payload_ciphertext) VALUES (?,?,?)",id(proof),id(flow),new byte[]{1});
        jdbc.update("INSERT INTO email_delivery(delivery_id,challenge_id,member_id,status,payload_expires_at,attempt_count,created_at,updated_at) VALUES (?,?,?,'CANCELLED',?,0,?,?)",
                id(UUID.randomUUID()),id(challenge),id(a.member()),at(clock.instant().minusSeconds(100)),at(clock.instant().minusSeconds(700)),at(clock.instant()));
        jdbc.update("INSERT INTO member_profile_image(member_id,png_data,updated_at) VALUES (?,?,?)",id(a.member()),new byte[]{1,2},at(clock.instant()));
        assertThat(purger.processBatch()).isEqualTo(1);absent(a.member());
        for(String table:List.of("oauth_flow","oauth_exchange","social_signup_context","withdrawal_archive","withdrawal_identity","withdrawal_target","refresh_token"))
            assertThat(count(table)).as(table).isZero();
    }
}
