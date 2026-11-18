package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import kr.nospo.lifecycle.application.*;
import kr.nospo.identity.contract.WithdrawalIdentity;
import kr.nospo.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** All identities are isolated fixtures; no SMTP or live Kakao account is used. */
class WithdrawalTests extends KakaoAccountTests {
    @Autowired WithdrawalService withdrawals;
    @MockitoSpyBean WithdrawalArchiveCrypto archiveCrypto;
    @MockitoSpyBean WithdrawalStore withdrawalStore;
    @MockitoSpyBean WithdrawalIdentity withdrawalIdentity;
    @MockitoSpyBean kr.nospo.identity.application.VerificationStore verificationStore;

    void withdraw(Account a,UUID key,String proof){withdrawals.withdraw(a.member(),a.session(),key,proof);}
    UUID operation(){return uuid(jdbc.queryForObject("SELECT operation_id FROM withdrawal_operation",byte[].class));}
    WithdrawalArchiveCrypto.Encrypted encrypted(){
        return jdbc.queryForObject("SELECT payload_ciphertext,wrapped_data_key FROM withdrawal_archive",
                (r,n)->new WithdrawalArchiveCrypto.Encrypted(r.getBytes(1),r.getBytes(2)));
    }
    void retained(Account a){
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE member_id=?",String.class,id(a.member()))).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT withdrawal_generation FROM member WHERE member_id=?",Long.class,id(a.member()))).isEqualTo(1);
        Duration retention=jdbc.queryForObject("SELECT withdrawn_at,recoverable_until FROM member WHERE member_id=?",
                (r,n)->Duration.between(r.getTimestamp(1).toInstant(),r.getTimestamp(2).toInstant()),id(a.member()));
        assertThat(retention).isEqualTo(Duration.ofDays(30));
        assertThat(count("withdrawal_archive")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE member_id=? AND revoked_at IS NULL",Integer.class,id(a.member()))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NULL AND session_id IN"
                +" (SELECT session_id FROM auth_session WHERE member_id=?)",Integer.class,id(a.member()))).isZero();
        assertThat(store.sessionActive(a.member(),a.session(),clock.instant())).isFalse();
    }
    @Test
    void emailWithdrawalHttpArchivesBeforeScrubbingAndRejectsAllOldCredentials() throws Exception {
        var a=emailAccount("withdraw@example.com","withdraw");
        var second=auth.login("withdraw@example.com"," valid password ","test");
        loginMethods.recordVerifiedKakao(a.member(),"12345");
        jdbc.update("INSERT INTO member_profile_image(member_id,png_data,updated_at) VALUES (?,?,?)",id(a.member()),new byte[]{1,2,3},at(clock.instant()));
        jdbc.update("UPDATE member SET profile_image_key='/api/v1/me/profile-image' WHERE member_id=?",id(a.member()));
        changes.requestEmail(a.member(),a.session(),"pending@example.com",passwordProof(a,"CHANGE_EMAIL"));
        actions.reauthenticate(a.member(),a.session(),"ADD_EMAIL_LOGIN");
        String linkProof=passwordProof(a,"LINK_KAKAO"),proof=passwordProof(a,"WITHDRAWAL");
        UUID key=UUID.randomUUID();
        mvc.perform(post("/api/v1/me/withdrawal").header("Authorization","Bearer "+a.signup().data().accessToken())
                .header("Idempotency-Key",key).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("reauthProof",proof,"confirmed",true))))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(cookie().maxAge("refreshToken",0)).andExpect(cookie().httpOnly("refreshToken",true));
        retained(a);
        for(String column:List.of("nickname","phone_number","profile_image_key"))
            assertThat(jdbc.queryForObject("SELECT "+column+" FROM member WHERE member_id=?",String.class,id(a.member()))).isNull();
        assertThat(jdbc.queryForObject("SELECT nickname_key FROM member WHERE member_id=?",String.class,id(a.member()))).isEqualTo("withdraw");
        assertThat(jdbc.queryForObject("SELECT email FROM account WHERE member_id=?",String.class,id(a.member()))).isNull();
        assertThat(jdbc.queryForObject("SELECT email_key FROM account WHERE member_id=?",String.class,id(a.member()))).isNull();
        for(String table:List.of("password_credential","social_identity","member_profile_image","member_consent","oauth_flow","oauth_exchange","social_signup_context","idempotency_record"))
            assertThat(count(table)).as(table).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_proof WHERE target_email IS NOT NULL OR target_email_key IS NOT NULL OR provider_subject IS NOT NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_challenge WHERE target_email<>'' OR target_email_key<>''",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_delivery WHERE payload_ciphertext IS NOT NULL OR payload_key_id IS NOT NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE member_id=?",Integer.class,id(a.member()))).isEqualTo(5);
        assertThat(count("member")).isEqualTo(1);assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(count("withdrawal_identity")).isEqualTo(2);
        byte[] plaintext=archiveCrypto.decrypt(operation(),encrypted());
        try{
            var parts=mapper.readTree(plaintext);
            var member=mapper.readTree(Base64.getDecoder().decode(parts.path("member").asString()));
            var profile=mapper.readTree(Base64.getDecoder().decode(member.path("member").asString())).get(0);
            assertThat(profile.path("nickname").asString()).isEqualTo("withdraw");
            assertThat(mapper.readTree(Base64.getDecoder().decode(member.path("images").asString())).size()).isEqualTo(1);
            var identity=mapper.readTree(Base64.getDecoder().decode(parts.path("identity").asString()));
            assertThat(mapper.readTree(Base64.getDecoder().decode(identity.path("password_credential").asString())).size()).isEqualTo(1);
            assertThat(mapper.readTree(Base64.getDecoder().decode(identity.path("social_identity").asString())).size()).isEqualTo(1);
            assertThat(mapper.readTree(Base64.getDecoder().decode(parts.path("consents").asString())).size()).isEqualTo(3);
        }finally{Arrays.fill(plaintext,(byte)0);}
        assertThat(new String(encrypted().ciphertext(),StandardCharsets.ISO_8859_1)).doesNotContain("withdraw@example.com"," valid password ");
        code(()->auth.login("withdraw@example.com"," valid password ","test"),"INVALID_CREDENTIALS");
        code(()->auth.refresh(a.signup().refreshToken()),"INVALID_CREDENTIALS");
        code(()->auth.refresh(second.refresh()),"INVALID_CREDENTIALS");
        code(()->actions.authorizeLink(a.member(),a.session(),linkProof),"INVALID_CREDENTIALS");
        code(()->actions.reauthenticate(a.member(),a.session(),"WITHDRAWAL"),"INVALID_CREDENTIALS");
        for(String token:List.of(a.signup().data().accessToken(),second.accessToken()))
            mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        // HTTP retries cannot authenticate with the now-revoked JWT; service-level in-flight retries are idempotent.
        mvc.perform(post("/api/v1/me/withdrawal").header("Authorization","Bearer "+a.signup().data().accessToken())
                .header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(Map.of("reauthProof",proof,"confirmed",true))))
                .andExpect(status().isUnauthorized());
        withdraw(a,key,proof);
        code(()->withdraw(a,key,secrets.token()),"IDEMPOTENCY_CONFLICT");
        code(()->withdraw(a,UUID.randomUUID(),proof),"INVALID_CREDENTIALS");
    }
    @Test
    void kakaoOnlyWithdrawalUsesSameLinkedProviderProofAndRetainsAnotherMember() {
        var original=kakaoSignup.signup(UUID.randomUUID(),kakaoCommand(signupProof("12345","kakao@example.com",true),"kakao",null));
        var a=new Account(original,original.data().member().memberId(),UUID.fromString(decoder.decode(original.data().accessToken()).getClaimAsString("sid")));
        var other=emailAccount("other@example.com","other");
        user("12345","kakao@example.com",true);
        String proof=(String)finish(actions.reauthenticate(a.member(),a.session(),"WITHDRAWAL"),a).data().get("reauthProof");
        withdraw(a,UUID.randomUUID(),proof);retained(a);
        assertThat(loginMethods.get(other.member()).loginMethods()).containsExactly("EMAIL");
        assertThat(store.sessionActive(other.member(),other.session(),clock.instant())).isTrue();
        assertThat(count("credit_wallet")).isEqualTo(2);assertThat(count("credit_transaction")).isEqualTo(2);
        user("12345","kakao@example.com",true);
        assertThat(oauth.exchange(callback(begin()),null,null,"test").data().get("result")).isEqualTo("RECOVERY_REQUIRED");
        code(()->loginMethods.recordVerifiedKakao(other.member(),"12345"),"SOCIAL_ACCOUNT_ALREADY_LINKED");
        // The deadline never silently permits a duplicate signup.
        clock.advance(30*86400);
        assertThat(store.emailExists("kakao@example.com")).isTrue();
        assertThat(count("member")).isEqualTo(2);
    }
    @Test
    void rejectsWrongPurposeMemberSessionAndExactProofDeadlineWithoutMutation(){
        var a=emailAccount("first@example.com","first");
        var b=emailAccount("second@example.com","second");
        code(()->withdraw(a,UUID.randomUUID(),passwordProof(a,"CHANGE_EMAIL")),"INVALID_PROOF");
        code(()->withdraw(b,UUID.randomUUID(),passwordProof(a,"WITHDRAWAL")),"INVALID_PROOF");
        String proof=passwordProof(a,"WITHDRAWAL");
        var second=auth.login("first@example.com"," valid password ","test");
        UUID session=UUID.fromString(decoder.decode(second.accessToken()).getClaimAsString("sid"));
        code(()->withdrawals.withdraw(a.member(),session,UUID.randomUUID(),proof),"INVALID_PROOF");
        clock.advance(300);
        code(()->withdraw(a,UUID.randomUUID(),proof),"INVALID_PROOF");
        assertThat(count("withdrawal_operation")).isZero();assertThat(count("password_credential")).isEqualTo(2);
    }
    @Test
    void encryptionFailureAndFailureAfterScrubRollbackAllSourceDataAndProof(){
        var a=emailAccount("rollback@example.com","rollback");
        String proof=passwordProof(a,"WITHDRAWAL");UUID key=UUID.randomUUID();
        doThrow(new IllegalStateException("injected encryption failure")).when(archiveCrypto).encrypt(any(),any());
        assertThatThrownBy(()->withdraw(a,key,proof)).isInstanceOf(RuntimeException.class).hasMessageContaining("injected encryption failure");
        assertIntact(a,proof);
        doCallRealMethod().when(archiveCrypto).encrypt(any(),any());
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("injected after scrub");})
                .when(withdrawalIdentity).retain(any(),any(),any());
        assertThatThrownBy(()->withdraw(a,key,proof)).isInstanceOf(RuntimeException.class).hasMessageContaining("injected after scrub");
        assertIntact(a,proof);
        doCallRealMethod().when(withdrawalIdentity).retain(any(),any(),any());
        withdraw(a,key,proof);retained(a);
    }
    void assertIntact(Account a,String proof){
        assertThat(count("withdrawal_operation")).isZero();assertThat(count("withdrawal_archive")).isZero();
        assertThat(count("withdrawal_identity")).isZero();
        assertThat(signup.me(a.member()).email()).isEqualTo("rollback@example.com");
        assertThat(count("password_credential")).isEqualTo(1);assertThat(count("member_consent")).isEqualTo(3);
        assertThat(store.sessionActive(a.member(),a.session(),clock.instant())).isTrue();unconsumed(proof);
    }
    @Test
    void concurrentSameAndDifferentKeysProduceExactlyOneArchive() throws Exception {
        var a=emailAccount("concurrent@example.com","concurrent");
        String proof=passwordProof(a,"WITHDRAWAL");UUID key=UUID.randomUUID();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)){
            var futures=new ArrayList<Future<String>>();
            for(UUID requestKey:List.of(key,key,UUID.randomUUID())) futures.add(pool.submit(()->{
                start.await();try{withdraw(a,requestKey,proof);return "OK";}catch(ApiException ex){return ex.code();}
            }));
            start.countDown();var results=new ArrayList<String>();
            for(var future:futures)results.add(future.get(15,TimeUnit.SECONDS));
            assertThat(results).contains("OK").allMatch(v->v.equals("OK")||v.equals("INVALID_CREDENTIALS"));
        }
        retained(a);assertThat(count("withdrawal_operation")).isEqualTo(1);
    }
    @Test
    void archiveKeysArePerOperationAndRejectTamperingAndWrongContext(){
        UUID operation=UUID.randomUUID();byte[] source="synthetic fixture".getBytes(StandardCharsets.UTF_8);
        var first=archiveCrypto.encrypt(operation,source);var second=archiveCrypto.encrypt(operation,source);
        assertThat(first.wrappedKey()).isNotEqualTo(second.wrappedKey());
        assertThat(archiveCrypto.decrypt(operation,first)).isEqualTo(source);
        assertThatThrownBy(()->archiveCrypto.decrypt(UUID.randomUUID(),first)).isInstanceOf(IllegalStateException.class);
        first.ciphertext()[12]^=1;
        assertThatThrownBy(()->archiveCrypto.decrypt(operation,first)).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void archivePersistenceFailureDoesNotCommitConsumedProofOrAnyPersonalRemoval(){
        var a=emailAccount("rollback@example.com","rollback");
        String proof=passwordProof(a,"WITHDRAWAL");
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("injected archive persistence failure");})
                .when(withdrawalStore).save(any(),any(),anyLong(),any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->withdraw(a,UUID.randomUUID(),proof)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected archive persistence failure");
        assertIntact(a,proof);
    }
    @Test
    void inactiveMemberRejectsJwtRefreshAndNewSessionsEvenWithoutExplicitRevocation() throws Exception {
        var a=emailAccount("inactive@example.com","inactive");
        jdbc.update("UPDATE member SET status='WITHDRAWING' WHERE member_id=?",id(a.member()));
        assertThat(store.sessionActive(a.member(),a.session(),clock.instant())).isFalse();
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+a.signup().data().accessToken()))
                .andExpect(status().isUnauthorized());
        code(()->auth.refresh(a.signup().refreshToken()),"INVALID_CREDENTIALS");
        code(()->store.createSession(a.member(),UUID.randomUUID(),UUID.randomUUID(),secrets.hash(secrets.token()),clock.instant()),"INVALID_CREDENTIALS");
    }
    @Test
    void confirmationPurposeAndOriginMustBeValidBeforeWithdrawal() throws Exception {
        var a=emailAccount("confirm@example.com","confirm");
        String proof=passwordProof(a,"WITHDRAWAL"),bearer="Bearer "+a.signup().data().accessToken();
        mvc.perform(post("/api/v1/me/withdrawal").header("Authorization",bearer).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("reauthProof",proof,"confirmed",false))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/me/withdrawal").header("Authorization",bearer).header("Idempotency-Key",UUID.randomUUID())
                .header("Origin","https://untrusted.example").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("reauthProof",proof,"confirmed",true))))
                .andExpect(status().isForbidden());
        assertThat(count("withdrawal_operation")).isZero();unconsumed(proof);
    }
    @Test
    void retainedEmailCannotCreateAnotherAccountOrBecomeAnotherMembersEmail(){
        var a=emailAccount("reserved@example.com","reserved");
        var other=emailAccount("other@example.com","other");
        String proof=passwordProof(a,"WITHDRAWAL");withdraw(a,UUID.randomUUID(),proof);
        assertThat(store.emailExists("reserved@example.com")).isTrue();
        code(()->changes.requestEmail(other.member(),other.session(),"reserved@example.com",passwordProof(other,"CHANGE_EMAIL")),"EMAIL_ALREADY_EXISTS");
        var verified=new kr.nospo.identity.contract.SignupIdentity.Verified(UUID.randomUUID(),UUID.randomUUID(),"reserved@example.com");
        // Exercise the final persistence boundary too, not only preflight availability checks.
        code(()->store.createAccountWithoutPassword(other.member(),verified.email(),clock.instant()),"EMAIL_ALREADY_EXISTS");
        assertThat(count("member")).isEqualTo(2);assertThat(count("credit_transaction")).isEqualTo(2);
    }
    @Test
    void refreshRacingWithdrawalNeverLeavesAUsableSession() throws Exception {
        var a=emailAccount("race@example.com","race");
        String proof=passwordProof(a,"WITHDRAWAL");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var refresh=pool.submit(()->{start.await();try{return auth.refresh(a.signup().refreshToken()).refresh();}
                catch(ApiException ex){assertThat(ex.code()).isEqualTo("INVALID_CREDENTIALS");return null;}});
            var withdrawal=pool.submit(()->{start.await();withdraw(a,UUID.randomUUID(),proof);return null;});
            start.countDown();withdrawal.get(15,TimeUnit.SECONDS);String rotated=refresh.get(15,TimeUnit.SECONDS);
            if(rotated!=null)code(()->auth.refresh(rotated),"INVALID_CREDENTIALS");
        }
        retained(a);
    }
    @Test
    void multipleWithdrawnMembersCanHaveNullPersonalColumnsWithoutUniqueConflicts(){
        var a=emailAccount("first@example.com","first");var b=emailAccount("second@example.com","second");
        withdraw(a,UUID.randomUUID(),passwordProof(a,"WITHDRAWAL"));
        withdraw(b,UUID.randomUUID(),passwordProof(b,"WITHDRAWAL"));
        assertThat(count("withdrawal_operation")).isEqualTo(2);assertThat(count("withdrawal_archive")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE status='WITHDRAWN' AND nickname_key IS NOT NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE email IS NULL AND email_key IS NULL",Integer.class)).isEqualTo(2);
        assertThat(count("withdrawal_identity")).isEqualTo(2);assertThat(count("credit_transaction")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM credit_wallet",Integer.class)).isEqualTo(10);
    }
    @Test
    void resetVerificationRacingWithdrawalCannotRecreatePlaintextProofTargets() throws Exception {
        var a=emailAccount("verifyrace@example.com","verifyrace");
        var request=resets.request("verifyrace@example.com","test");delivery.processDue();
        String code=sent.get("verifyrace@example.com").substring(6),proof=passwordProof(a,"WITHDRAWAL");
        var verifierLocked=new CountDownLatch(1);var release=new CountDownLatch(1);var retaining=new CountDownLatch(1);
        doAnswer(i->{
            if("PASSWORD_RESET".equals(i.getArgument(4))){verifierLocked.countDown();
                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture release timeout");}
            return i.callRealMethod();
        }).when(verificationStore).createProof(any(),any(),any(),nullable(UUID.class),anyString(),any(),any(),any(),anyInt());
        doAnswer(i->{retaining.countDown();return i.callRealMethod();}).when(withdrawalIdentity).retain(any(),any(),any());
        try(var pool=Executors.newFixedThreadPool(2)){
            var verifying=pool.submit(()->resets.verify(request.verificationId(),code));
            assertThat(verifierLocked.await(10,TimeUnit.SECONDS)).isTrue();
            var withdrawal=pool.submit(()->{withdraw(a,UUID.randomUUID(),proof);return null;});
            try{
                assertThat(retaining.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->withdrawal.get(1,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            }finally{release.countDown();}
            verifying.get(10,TimeUnit.SECONDS);withdrawal.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_proof WHERE member_id=? AND"
                +" (target_email IS NOT NULL OR target_email_key IS NOT NULL OR invalidated_at IS NULL)",Integer.class,id(a.member()))).isZero();
        retained(a);
    }
    @Test
    void withdrawalAlsoScrubsParallelKakaoSignupProofsForTheSameProviderIdentity(){
        String abandoned=signupProof("12345","kakao@example.com",true);
        String unrelated=signupProof("67890","unrelated@example.com",true);
        var original=kakaoSignup.signup(UUID.randomUUID(),kakaoCommand(signupProof("12345","kakao@example.com",true),"kakao",null));
        var a=new Account(original,original.data().member().memberId(),UUID.fromString(decoder.decode(original.data().accessToken()).getClaimAsString("sid")));
        user("12345","kakao@example.com",true);
        String proof=(String)finish(actions.reauthenticate(a.member(),a.session(),"WITHDRAWAL"),a).data().get("reauthProof");
        withdraw(a,UUID.randomUUID(),proof);
        assertThat(jdbc.queryForObject("SELECT provider_subject FROM auth_proof WHERE proof_hash=?",String.class,secrets.hash(abandoned))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_proof WHERE member_id=? AND provider_subject IS NOT NULL",Integer.class,id(a.member()))).isZero();
        // Another unowned identity's flow and proof are preserved.
        assertThat(jdbc.queryForObject("SELECT provider_subject FROM auth_proof WHERE proof_hash=?",String.class,secrets.hash(unrelated))).isEqualTo("67890");
        assertThat(count("social_signup_context")).isEqualTo(1);assertThat(count("oauth_flow")).isEqualTo(1);
        retained(a);
    }
}
