package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import kr.nospo.identity.application.RecoveryIdentityService;
import kr.nospo.lifecycle.application.*;
import kr.nospo.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.*;

/** Isolated test mail and Kakao adapters; no real accounts or SMTP are accessed. */
class RecoveryTests extends KakaoAccountTests {
    @Autowired WithdrawalService withdrawals;
    @Autowired RecoveryService recovery;
    @Autowired RecoveryIdentityService recoveryEvidence;
    void withdraw(Account a) {withdrawals.withdraw(a.member(),a.session(),UUID.randomUUID(),passwordProof(a,"WITHDRAWAL"));}
    String recoveryProof(String email) {
        recoveryEvidence.request(email,"test"); delivery.processDue();
        assertThat(sent).containsKey(email);
        return recoveryEvidence.confirm(sent.get(email).substring(9)).recoveryProof();
    }
    SignupService.Completed recover(String proof) {return recovery.recover(new RecoveryService.Command(proof,"replacement password",List.copyOf(consents)));}
    @Test
    void emailRecoveryKeepsIdentityProfileWalletAndMethodsButOnlyNewSessionsWork() throws Exception {
        var a=emailAccount("recover@example.com","recover");
        loginMethods.recordVerifiedKakao(a.member(),"12345");
        jdbc.update("INSERT INTO member_profile_image(member_id,png_data,updated_at) VALUES (?,?,?)",id(a.member()),new byte[]{1,2,3},at(clock.instant()));
        jdbc.update("UPDATE member SET profile_image_key='/api/v1/me/profile-image' WHERE member_id=?",id(a.member()));
        var created=a.signup().data().member().createdAt();
        withdraw(a);
        String proof=recoveryProof("recover@example.com");
        var result=mvc.perform(post("/api/v1/auth/recovery/complete").contentType("application/json")
                .content(mapper.writeValueAsString(new RecoveryService.Command(proof,"replacement password",consents))))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("refreshToken",true)).andExpect(cookie().secure("refreshToken",true))
                .andExpect(jsonPath("$.data.member.memberId").value(a.member().toString())).andReturn();
        var body=mapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(body.path("member").path("nickname").asString()).isEqualTo("recover");
        assertThat(signup.me(a.member()).createdAt()).isEqualTo(created);
        assertThat(signup.me(a.member()).profileImageUrl()).isEqualTo("/api/v1/me/profile-image");
        assertThat(loginMethods.get(a.member()).loginMethods()).containsExactly("EMAIL","KAKAO");
        assertThat(count("member")).isEqualTo(1);assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE member_id=?",Integer.class,id(a.member()))).isEqualTo(5);
        assertThat(count("withdrawal_archive")).isZero();assertThat(count("withdrawal_identity")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM withdrawal_operation",String.class)).isEqualTo("RESTORED");
        assertThat(count("member_consent")).isEqualTo(6);
        assertThat(count("member_profile_image")).isEqualTo(1);
        code(()->auth.refresh(a.signup().refreshToken()),"INVALID_CREDENTIALS");
        assertThat(auth.refresh(result.getResponse().getCookie("refreshToken").getValue())).isNotNull();
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+a.signup().data().accessToken())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+body.path("accessToken").asString())).andExpect(status().isOk());
        code(()->auth.login("recover@example.com"," valid password ","test"),"INVALID_CREDENTIALS");
        assertThat(auth.login("recover@example.com","replacement password","test")).isNotNull();
        code(()->recover(proof),"ACCOUNT_NOT_RECOVERABLE");
    }
    @Test
    void exactThirtyDaysRejectsEvenUnexpiredProofAndBeforeDeadlineSucceeds() {
        var a=emailAccount("boundary@example.com","boundary");withdraw(a);
        clock.advance(30*86400-1);
        String proof=recoveryProof("boundary@example.com");
        assertThat(signup.nicknameAvailable("boundary")).isFalse();
        clock.advance(1);
        code(()->recover(proof),"ACCOUNT_NOT_RECOVERABLE");
        assertThat(signup.nicknameAvailable("boundary")).isFalse();
        clock.now.set(clock.instant().plusNanos(1000));
        code(()->recover(proof),"ACCOUNT_NOT_RECOVERABLE");
        assertThat(signup.nicknameAvailable("boundary")).isFalse();
        assertThat(count("withdrawal_archive")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM member",String.class)).isEqualTo("WITHDRAWN");
    }
    @Test
    void oneMicrosecondBeforeDeadlineSucceeds() {
        var a=emailAccount("early@example.com","early");withdraw(a);
        clock.advance(30*86400-1);
        String proof=recoveryProof("early@example.com");
        clock.now.set(clock.instant().plusNanos(999999000));
        assertThat(recover(proof).data().member().memberId()).isEqualTo(a.member());
    }
    @Test
    void expiredEvidenceWrongPurposeAndMissingPasswordCannotRestore() {
        var a=emailAccount("expiry@example.com","expiry");withdraw(a);
        String proof=recoveryProof("expiry@example.com");
        code(()->recovery.recover(new RecoveryService.Command(proof,null,consents)),"VALIDATION_ERROR");
        code(()->recover(secrets.token()),"INVALID_PROOF");
        clock.advance(600);
        code(()->recover(proof),"INVALID_PROOF");
        assertThat(count("withdrawal_archive")).isEqualTo(1);
    }
    @Test
    void termsFailureRollsBackConsumptionAndRestoration() {
        var a=emailAccount("terms@example.com","terms");withdraw(a);
        String proof=recoveryProof("terms@example.com");
        code(()->recovery.recover(new RecoveryService.Command(proof,"replacement password",List.of())),"REQUIRED_CONSENT_MISSING");
        assertThat(jdbc.queryForObject("SELECT status FROM member",String.class)).isEqualTo("WITHDRAWN");
        assertThat(count("password_credential")).isZero();assertThat(count("withdrawal_archive")).isEqualTo(1);
        assertThat(recover(proof).data().member().memberId()).isEqualTo(a.member());
    }
    @Test
    void kakaoOAuthRecoveryPreservesKakaoOnlyAndNeverGrantsAgain() {
        var original=kakaoSignup.signup(UUID.randomUUID(),kakaoCommand(signupProof("12345","social@example.com",true),"social",null));
        var a=new Account(original,original.data().member().memberId(),UUID.fromString(decoder.decode(original.data().accessToken()).getClaimAsString("sid")));
        user("12345","social@example.com",true);
        String reauth=(String)finish(actions.reauthenticate(a.member(),a.session(),"WITHDRAWAL"),a).data().get("reauthProof");
        withdrawals.withdraw(a.member(),a.session(),UUID.randomUUID(),reauth);
        user("12345","changed@example.com",true); // Provider subject, never provider email, owns recovery.
        var exchanged=oauth.exchange(callback(begin()),null,null,"test");
        assertThat(exchanged.data().get("result")).isEqualTo("RECOVERY_REQUIRED");assertThat(exchanged.refresh()).isNull();
        String proof=(String)exchanged.data().get("recoveryProof");
        var restored=recovery.recover(new RecoveryService.Command(proof,null,consents));
        assertThat(restored.data().member().memberId()).isEqualTo(a.member());
        assertThat(restored.data().member().email()).isEqualTo("social@example.com");
        assertThat(restored.data().member().loginMethods()).containsExactly("KAKAO");
        assertThat(count("password_credential")).isZero();assertThat(count("credit_transaction")).isEqualTo(1);
        user("12345","social@example.com",true);
        assertThat(oauth.exchange(callback(begin()),null,null,"test").data().get("result")).isEqualTo("LOGIN_SUCCESS");
    }
    @Test
    void concurrentDifferentProofsOpenOnlyOneSessionAndPreviousGenerationCannotRestoreAgain() throws Exception {
        var a=emailAccount("race@example.com","race");loginMethods.recordVerifiedKakao(a.member(),"12345");withdraw(a);
        String emailProof=recoveryProof("race@example.com");
        user("12345","race@example.com",true);
        String kakaoProof=(String)oauth.exchange(callback(begin()),null,null,"test").data().get("recoveryProof");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var email=pool.submit(()->{start.await();try{return recover(emailProof);}catch(ApiException ex){return ex.code();}});
            var kakao=pool.submit(()->{start.await();try{return recovery.recover(new RecoveryService.Command(kakaoProof,null,consents));}catch(ApiException ex){return ex.code();}});
            start.countDown();var results=List.of(email.get(30,TimeUnit.SECONDS),kakao.get(30,TimeUnit.SECONDS));
            assertThat(results.stream().filter(SignupService.Completed.class::isInstance).count()).isEqualTo(1);
            assertThat(results).contains("ACCOUNT_NOT_RECOVERABLE");
            var restored=(SignupService.Completed)results.stream().filter(SignupService.Completed.class::isInstance).findFirst().orElseThrow();
            var active=new Account(restored,a.member(),UUID.fromString(decoder.decode(restored.data().accessToken()).getClaimAsString("sid")));
            user("12345","race@example.com",true);
            String again=(String)finish(actions.reauthenticate(active.member(),active.session(),"WITHDRAWAL"),active).data().get("reauthProof");
            withdrawals.withdraw(active.member(),active.session(),UUID.randomUUID(),again);
            code(()->recover(emailProof),"ACCOUNT_NOT_RECOVERABLE");
            assertThat(jdbc.queryForObject("SELECT withdrawal_generation FROM member",Long.class)).isEqualTo(2);
            clock.advance(60);
            assertThat(recover(recoveryProof("race@example.com")).data().member().memberId()).isEqualTo(a.member());
            assertThat(count("credit_transaction")).isEqualTo(1);
        }
    }
    @Test
    void activeUnknownAndExpiredAccountsNeverSendRecoveryMail() {
        var a=emailAccount("private@example.com","private");sent.clear();
        recoveryEvidence.request("private@example.com","test");recoveryEvidence.request("unknown@example.com","test");delivery.processDue();assertThat(sent).isEmpty();
        withdraw(a);clock.advance(30*86400);
        recoveryEvidence.request("private@example.com","test");delivery.processDue();assertThat(sent).isEmpty();
    }
    @Test
    void changedRequiredTermsMustBeAcceptedAndEvidenceRemainsUsableAfterFailure() {
        var a=emailAccount("latest@example.com","latest");withdraw(a);
        String proof=recoveryProof("latest@example.com");
        UUID term=jdbc.queryForObject("SELECT term_id FROM term WHERE type='PRIVACY'",(r,n)->uuid(r.getBytes(1)));
        UUID latest=UUID.randomUUID();clock.advance(1);
        jdbc.update("INSERT INTO term_version(term_version_id,term_id,version,title,content,required,publication_status,effective_at,requires_reconsent,created_at)"
                +" VALUES (?,?,'2.0','Privacy','TEST FIXTURE',true,'PUBLISHED',?,true,?)",id(latest),id(term),at(clock.instant()),at(clock.instant()));
        code(()->recover(proof),"TERMS_VERSION_CHANGED");
        var current=new ArrayList<kr.nospo.terms.contract.Terms.Consent>();
        for(var consent:consents) {
            UUID owner=jdbc.queryForObject("SELECT term_id FROM term_version WHERE term_version_id=?",(r,n)->uuid(r.getBytes(1)),id(consent.termVersionId()));
            current.add(owner.equals(term)?new kr.nospo.terms.contract.Terms.Consent(latest,true):consent);
        }
        assertThat(recovery.recover(new RecoveryService.Command(proof,"replacement password",current)).data().member().memberId()).isEqualTo(a.member());
    }
    @Test
    void emailChallengeReplayResendAndProofPurposeAreIsolated() {
        var a=emailAccount("challenge@example.com","challenge");withdraw(a);sent.clear();
        recoveryEvidence.request("challenge@example.com","test");delivery.processDue();String first=sent.get("challenge@example.com").substring(9);
        String proof=recoveryEvidence.confirm(first).recoveryProof();
        code(()->recoveryEvidence.confirm(first),"CHALLENGE_ALREADY_VERIFIED");
        code(()->signup.signup(UUID.randomUUID(),command(proof,"intruder")),"INVALID_PROOF");
        clock.advance(60);
        recoveryEvidence.request("challenge@example.com","test");
        code(()->recover(proof),"INVALID_PROOF");
        delivery.processDue();String second=sent.get("challenge@example.com").substring(9);
        code(()->recoveryEvidence.confirm(first),"VERIFICATION_EXPIRED");
        assertThat(recover(recoveryEvidence.confirm(second).recoveryProof()).data().member().memberId()).isEqualTo(a.member());
    }
    @Test
    void reservedNicknameBlocksSignupAndChangeBeforeAndAfterRecovery() throws Exception {
        var owner=emailAccount("reserved@example.com","Reserved");
        var other=emailAccount("editor@example.com","editor");
        String signupProof=proof("newreader@example.com");
        var command=command(signupProof," reserved ");
        code(()->signup.signup(UUID.randomUUID(),command),"NICKNAME_ALREADY_EXISTS");
        withdraw(owner);
        mvc.perform(get("/api/v1/auth/availability/nickname").param("nickname"," RESERVED "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false));
        code(()->signup.signup(UUID.randomUUID(),command),"NICKNAME_ALREADY_EXISTS");
        code(()->profiles.nickname(other.member(),other.session(),"RESERVED"),"NICKNAME_ALREADY_EXISTS");
        var restored=recover(recoveryProof("reserved@example.com")).data().member();
        assertThat(restored.memberId()).isEqualTo(owner.member());
        assertThat(restored.nickname()).isEqualTo("Reserved");
        code(()->signup.signup(UUID.randomUUID(),command),"NICKNAME_ALREADY_EXISTS");
        code(()->profiles.nickname(other.member(),other.session(),"reserved"),"NICKNAME_ALREADY_EXISTS");
    }

    @Test
    void legacyNicknameConflictRollsBackAndOwnerCanRetryAfterConflictIsResolved() {
        var a=emailAccount("owner@example.com","owner");withdraw(a);
        // Simulate a withdrawal made before nickname keys were retained.
        jdbc.update("UPDATE member SET nickname_key=NULL WHERE member_id=?",id(a.member()));
        var b=emailAccount("other@example.com","owner");
        String proof=recoveryProof("owner@example.com");
        code(()->recover(proof),"RECOVERY_PROFILE_CONFLICT");
        assertThat(signup.me(b.member()).email()).isEqualTo("other@example.com");
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE member_id=?",String.class,id(a.member()))).isEqualTo("WITHDRAWN");
        jdbc.update("UPDATE member SET nickname='other',nickname_key='other' WHERE member_id=?",id(b.member()));
        assertThat(recover(proof).data().member().memberId()).isEqualTo(a.member());
        assertThat(count("credit_transaction")).isEqualTo(2);
    }
}
