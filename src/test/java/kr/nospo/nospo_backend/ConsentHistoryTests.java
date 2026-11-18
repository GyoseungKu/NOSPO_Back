package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import kr.nospo.member.contract.Members;
import kr.nospo.support.ApiException;
import kr.nospo.terms.application.ConsentStore;
import kr.nospo.terms.contract.MemberConsents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;

class ConsentHistoryTests extends RecoveryTests {
    @Autowired MemberConsents history;
    @Autowired ConsentStore consentStore;
    @Autowired Members members;
    @Autowired PlatformTransactionManager manager;
    @Autowired kr.nospo.terms.application.TermStore termStore;
    @Autowired kr.nospo.terms.contract.Terms terms;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    TransactionTemplate transaction() {
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;
    }
    void append(UUID member,UUID version,boolean agreed) {
        transaction().executeWithoutResult(s->{members.lockActive(member);consentStore.append(member,version,agreed,clock.instant());});
    }
    UUID optional() { return consents.stream().filter(c->!c.agreed()).findFirst().orElseThrow().termVersionId(); }
    String bearer(Account a) { return "Bearer "+a.signup().data().accessToken(); }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ConsentStore reconsentStoreSpy;

    UUID reconsentRevision(String type,String version,java.time.Instant effective,boolean requires,Boolean required,String publication) {
        byte[] term=jdbc.queryForObject("SELECT term_id FROM term WHERE type=?",byte[].class,type);
        boolean mandatory=required!=null?required:!type.equals("OPTIONAL");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO term_version(term_version_id,term_id,version,title,content,required,publication_status,effective_at,requires_reconsent,created_at)"
                +" VALUES (?,?,?,'fixture','fixture',?,?,?,?,?)",
                id(id),term,version,mandatory,publication,at(effective),requires,at(clock.instant()));
        return id;
    }
    UUID reconsentRevision(String type,String version,boolean requires) {
        clock.advance(1);
        return reconsentRevision(type,version,clock.instant(),requires,null,"PUBLISHED");
    }
    MemberConsents.Pending reconsentPending(Account a) { return history.pending(a.member(),a.session()); }
    java.util.List<MemberConsents.Event> reconsentSubmit(Account a,kr.nospo.terms.contract.Terms.Consent... choices) {
        return history.submit(a.member(),a.session(),java.util.List.of(choices));
    }
    org.springframework.test.web.servlet.ResultActions reconsentPost(Account a,List<kr.nospo.terms.contract.Terms.Consent> choices) throws Exception {
        return mvc.perform(post("/api/v1/me/consents").header("Authorization",bearer(a)).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("consents",choices))));
    }


    org.springframework.test.web.servlet.ResultActions updateNickname(String authorization) throws Exception {
        return mvc.perform(patch("/api/v1/me/nickname").header("Authorization",authorization)
                .contentType("application/json").content("{\"nickname\":\"new_reader\"}"));
    }

    @Test void accessGateUsesCurrentTermsWithTheSameTokenAcrossEffectiveTimeAndSubmission() throws Exception {
        var account=emailAccount("gateboundary@example.com","gateboundary");
        var effective=clock.instant().plusSeconds(1);
        UUID required=reconsentRevision("SERVICE","2",effective,true,null,"PUBLISHED");
        reconsentRevision("OPTIONAL","2",effective,true,null,"PUBLISHED");
        updateNickname(bearer(account)).andExpect(status().isOk());
        clock.advance(1);
        updateNickname(bearer(account)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TERMS_RECONSENT_REQUIRED"))
                .andExpect(jsonPath("$.meta.requestId").exists());
        clock.advance(1);
        updateNickname(bearer(account)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").header("Authorization",bearer(account))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/login-methods").header("Authorization",bearer(account))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/terms")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/terms/"+required)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(account))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/consents/pending").header("Authorization",bearer(account)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requiredAction").value(true));
        reconsentPost(account,List.of(new kr.nospo.terms.contract.Terms.Consent(required,true)))
                .andExpect(status().isCreated());
        updateNickname(bearer(account)).andExpect(status().isOk());
        assertThat(reconsentPending(account).items()).hasSize(1);
    }

    @Test void accessGateAllowsEmailLoginRefreshAndLogoutWhileBlockingNewTokens() throws Exception {
        var account=emailAccount("gatelogin@example.com","gatelogin");
        reconsentRevision("SERVICE","2",true);
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"gatelogin@example.com\",\"password\":\" valid password \"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String issued=mapper.readTree(login.getContentAsString()).at("/data/accessToken").asString();
        updateNickname("Bearer "+issued).andExpect(status().isForbidden());
        var refreshed=mvc.perform(post("/api/v1/auth/token/refresh").header("Origin","http://localhost:3000")
                .header("Cookie","refreshToken="+login.getCookie("refreshToken").getValue())
                .cookie(login.getCookie("refreshToken"))).andExpect(status().isOk()).andReturn().getResponse();
        String rotated=mapper.readTree(refreshed.getContentAsString()).at("/data/accessToken").asString();
        updateNickname("Bearer "+rotated).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+rotated))
                .andExpect(status().isNoContent());
        updateNickname("Bearer "+rotated).andExpect(status().isUnauthorized());
        updateNickname(bearer(account)).andExpect(status().isForbidden());
    }

    @Test void accessGatePreservesOriginAuthenticationAndWithdrawal() throws Exception {
        var account=emailAccount("gatewithdraw@example.com","gatewithdraw");
        reconsentRevision("SERVICE","2",true);
        mvc.perform(patch("/api/v1/me/nickname")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/me/nickname").header("Authorization",bearer(account))
                .header("Cookie","refreshToken=fixture")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ORIGIN_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/unmapped"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("TERMS_RECONSENT_REQUIRED"))));
        var now=java.time.Instant.now();
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("nospo")
                .subject(account.member().toString()).claim("sid",account.session().toString())
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(300)).build();
        var header=org.springframework.security.oauth2.jwt.JwsHeader.with(
                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String expired=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header,claims)).getTokenValue();
        updateNickname("Bearer "+expired).andExpect(status().isUnauthorized());
        var reauth=mvc.perform(post("/api/v1/me/reauth/password").header("Authorization",bearer(account))
                .contentType("application/json").content("{\"password\":\" valid password \",\"purpose\":\"WITHDRAWAL\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String proof=mapper.readTree(reauth.getContentAsString()).at("/data/reauthProof").asString();
        mvc.perform(post("/api/v1/me/withdrawal").header("Authorization",bearer(account))
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("reauthProof",proof,"confirmed",true))))
                .andExpect(status().isNoContent());
        updateNickname(bearer(account)).andExpect(status().isUnauthorized());
    }

    @Test void accessGateSelectsKakaoExchangeByStoredFlowType() throws Exception {
        var account=emailAccount("gatekakao@example.com","gatekakao");
        user("12345","external@example.com",true);
        var other=emailAccount("gateother@example.com","gateother");
        var link=actions.authorizeLink(account.member(),account.session(),passwordProof(account,"LINK_KAKAO"));
        String linkCode=exchangeFor(link);
        UUID required=reconsentRevision("SERVICE","2",true);
        mvc.perform(post("/api/v1/me/login-methods/kakao/authorization").header("Authorization",bearer(account)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TERMS_RECONSENT_REQUIRED"));
        mvc.perform(post("/api/v1/auth/kakao/exchange").header("Authorization",bearer(other))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("exchangeCode",linkCode))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PROOF"));
        mvc.perform(post("/api/v1/auth/kakao/exchange").header("Authorization",bearer(account))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("exchangeCode",linkCode))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TERMS_RECONSENT_REQUIRED"));
        reconsentPost(account,List.of(new kr.nospo.terms.contract.Terms.Consent(required,true))).andExpect(status().isCreated());
        var ready=oauth.exchange(linkCode,account.member(),account.session(),"gate-link");
        actions.link(account.member(),account.session(),(String)ready.data().get("linkProof"),UUID.randomUUID());
        reconsentRevision("SERVICE","3",true);
        var reauth=mvc.perform(post("/api/v1/me/reauth/kakao").header("Authorization",bearer(account))
                .contentType("application/json").content("{\"purpose\":\"WITHDRAWAL\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String url=mapper.readTree(reauth.getContentAsString()).at("/data/authorizationUrl").asString();
        var started=new kr.nospo.identity.application.KakaoOAuthService.Started(url,reauth.getCookie("nospoKakaoFlow").getValue());
        assertThat(finish(started,account).data()).containsEntry("result","REAUTH_SUCCESS");
        var login=mvc.perform(post("/api/v1/auth/kakao/exchange").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("exchangeCode",callback(begin())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("LOGIN_SUCCESS"))
                .andReturn().getResponse();
        String token=mapper.readTree(login.getContentAsString()).at("/data/auth/accessToken").asString();
        updateNickname("Bearer "+token)
                .andExpect(status().isForbidden());
    }

    @Test void accessGateFailsClosedOnInvalidCatalogAndQueryFailure() throws Exception {
        var account=emailAccount("gatefail@example.com","gatefail");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"))
                .when(reconsentStoreSpy).latestEffectiveChoices(account.member(),clock.instant());
        updateNickname(bearer(account)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        clock.advance(1);
        jdbc.update("UPDATE term_version SET publication_status='DRAFT' WHERE required=true");
        updateNickname(bearer(account)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("TERMS_NOT_CONFIGURED"));
        assertThat(jdbc.queryForObject("SELECT nickname FROM member WHERE member_id=?",String.class,id(account.member())))
                .isEqualTo("gatefail");
    }

    @Test void reconsentBaselineIsEmptyEvenForExplicitOptionalFalse() throws Exception {
        var a=emailAccount("rbase@example.com","rbase");
        jdbc.update("UPDATE term_version SET requires_reconsent=true");
        mvc.perform(get("/api/v1/me/consents/pending").header("Authorization",bearer(a)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.requiredAction").value(false));
        assertThat(consentStore.events(a.member())).hasSize(3);
    }

    @Test void reconsentRequiredDebtCarriesToLatestUnflaggedVersionAndExactRetryReusesEvent() throws Exception {
        var a=emailAccount("rcarry@example.com","rcarry");
        reconsentRevision("SERVICE","2",true);
        UUID latest=reconsentRevision("SERVICE","3",false);
        var pending=reconsentPending(a);
        assertThat(pending.requiredAction()).isTrue();
        assertThat(pending.items()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).containsExactly(latest);
        var body=List.of(new kr.nospo.terms.contract.Terms.Consent(latest,true));
        var response=reconsentPost(a,body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].termVersionId").value(latest.toString()))
                .andExpect(jsonPath("$.data.items[0].agreed").value(true))
                .andExpect(jsonPath("$.data.items[0].agreedAt").exists())
                .andExpect(jsonPath("$.meta.requestId").exists()).andReturn();
        String event=mapper.readTree(response.getResponse().getContentAsString()).at("/data/items/0/consentId").asString();
        reconsentPost(a,body).andExpect(status().isCreated()).andExpect(jsonPath("$.data.items[0].consentId").value(event));
        assertThat(consentStore.events(a.member())).hasSize(4);
        assertThat(reconsentPending(a).items()).isEmpty();
        assertThat(reconsentPending(a).requiredAction()).isFalse();
    }

    @Test void reconsentMandatoryOmissionRefusalAndMixedInvalidItemsNeverPartiallyPersist() throws Exception {
        var a=emailAccount("rrequired@example.com","rrequired");
        UUID service=reconsentRevision("SERVICE","2",true),privacy=reconsentRevision("PRIVACY","2",true);
        reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(service,true)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("REQUIRED_CONSENT_MISSING"));
        reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(service,true),new kr.nospo.terms.contract.Terms.Consent(privacy,false)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("REQUIRED_CONSENT_MISSING"));
        reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(service,true),new kr.nospo.terms.contract.Terms.Consent(privacy,true),
                        new kr.nospo.terms.contract.Terms.Consent(UUID.randomUUID(),true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_CHANGED"));
        assertThat(consentStore.events(a.member())).hasSize(3);
        reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(service,true),new kr.nospo.terms.contract.Terms.Consent(privacy,true)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.items.length()").value(2));
        assertThat(consentStore.events(a.member())).hasSize(5);
    }

    @Test void reconsentOptionalFalseCompletesPendingAndCannotBeChangedThroughThisApi() throws Exception {
        var a=emailAccount("roptional@example.com","roptional");
        UUID version=reconsentRevision("OPTIONAL","2",true);
        assertThat(reconsentPending(a).requiredAction()).isFalse();
        var choice=new kr.nospo.terms.contract.Terms.Consent(version,false);
        var event=reconsentSubmit(a,choice).get(0);
        assertThat(event.agreed()).isFalse();
        assertThat(reconsentPending(a).items()).isEmpty();
        assertThat(reconsentSubmit(a,choice)).containsExactly(event);
        reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(version,true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_CHANGED"));
        assertThat(consentStore.events(a.member())).hasSize(4);
        UUID next=reconsentRevision("OPTIONAL","3",true);
        assertThat(reconsentPending(a).items()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).containsExactly(next);
    }

    @Test void reconsentUnflaggedOptionalAndCompletedMandatoryDebtDoNotReopen() {
        var a=emailAccount("rfulfilled@example.com","rfulfilled");
        reconsentRevision("OPTIONAL","2",false);
        assertThat(reconsentPending(a).items()).isEmpty();
        UUID service=reconsentRevision("SERVICE","2",true);
        reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(service,true));
        UUID latest=reconsentRevision("SERVICE","3",false);
        assertThat(reconsentPending(a).items()).isEmpty();
        code(()->reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(service,true)),"TERMS_VERSION_CHANGED");
        code(()->reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(latest,true)),"TERMS_VERSION_CHANGED");
    }

    @Test void reconsentDraftFutureUnknownAndPastVersionsAreRejected() throws Exception {
        var a=emailAccount("rversions@example.com","rversions");
        UUID past=consents.get(0).termVersionId();
        UUID draft=reconsentRevision("SERVICE","draft",clock.instant().minusNanos(1000),true,null,"DRAFT");
        UUID future=reconsentRevision("SERVICE","future",clock.instant().plusSeconds(10),true,null,"PUBLISHED");
        UUID current=reconsentRevision("SERVICE","2",true);
        for(UUID version:List.of(past,draft,future,UUID.randomUUID()))
            reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(version,true)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_CHANGED"));
        assertThat(reconsentPending(a).items()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).containsExactly(current);
        assertThat(consentStore.events(a.member())).hasSize(3);
    }

    @Test void reconsentEffectiveBoundaryIsInclusiveAndDoesNotDuplicateOnRetry() {
        var a=emailAccount("rboundary@example.com","rboundary");
        var effective=clock.instant().plusSeconds(1);
        UUID version=reconsentRevision("SERVICE","2",effective,true,null,"PUBLISHED");
        clock.now.set(effective.minusNanos(1000));
        assertThat(reconsentPending(a).items()).isEmpty();
        code(()->reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true)),"TERMS_VERSION_CHANGED");
        clock.now.set(effective);
        assertThat(reconsentPending(a).items()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).containsExactly(version);
        var events=reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true));
        clock.now.set(effective.plusNanos(1000));
        assertThat(reconsentPending(a).items()).isEmpty();
        assertThat(reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true))).isEqualTo(events);
    }

    @Test void reconsentRequestValidationRequiresExplicitBooleanAndUniqueVersions() throws Exception {
        var a=emailAccount("rinput@example.com","rinput");
        UUID version=reconsentRevision("OPTIONAL","2",true);
        for(String body:List.of("{}", "{\"consents\":[]}", "{\"consents\":[null]}",
                "{\"consents\":[{\"termVersionId\":\""+version+"\"}]}",
                "{\"consents\":[{\"termVersionId\":\""+version+"\",\"agreed\":null}]}")) {
            mvc.perform(post("/api/v1/me/consents").header("Authorization",bearer(a)).contentType("application/json").content(body))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        var choice=new kr.nospo.terms.contract.Terms.Consent(version,false);
        reconsentPost(a,List.of(choice,choice)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        reconsentPost(a,Collections.nCopies(101,choice)).andExpect(status().isUnprocessableEntity());
        assertThat(consentStore.events(a.member())).hasSize(3);
    }

    @Test void reconsentAuthenticationAndOriginGuardsAreUnchanged() throws Exception {
        var a=emailAccount("rauth@example.com","rauth");
        UUID version=reconsentRevision("OPTIONAL","2",true);
        var body=mapper.writeValueAsString(Map.of("consents",List.of(new kr.nospo.terms.contract.Terms.Consent(version,true))));
        mvc.perform(get("/api/v1/me/consents/pending")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/consents").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/consents").header("Authorization",bearer(a)).header("Origin","https://evil.example")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/consents").header("Authorization",bearer(a)).header("Cookie","refreshToken=fixture")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        auth.logout(a.session(),null);
        mvc.perform(get("/api/v1/me/consents/pending").header("Authorization",bearer(a))).andExpect(status().isUnauthorized());
        reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(version,true))).andExpect(status().isUnauthorized());
        assertThat(consentStore.events(a.member())).hasSize(3);
    }

    @Test void reconsentExpiredJwtIsRejectedByBothEndpoints() throws Exception {
        var a=emailAccount("rexpired@example.com","rexpired");
        var now=java.time.Instant.now();
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("nospo")
                .subject(a.member().toString()).claim("sid",a.session().toString())
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(300)).build();
        var header=org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String expired=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header,claims)).getTokenValue();
        mvc.perform(get("/api/v1/me/consents/pending").header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/consents").header("Authorization","Bearer "+expired).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("consents",List.of(new kr.nospo.terms.contract.Terms.Consent(optional(),false))))))
                .andExpect(status().isUnauthorized());
    }

    @Test void reconsentMemberAndSessionCannotBeTransferredToAnotherAccount() throws Exception {
        var a=emailAccount("rowner@example.com","rowner");var b=emailAccount("rother@example.com","rother");
        UUID version=reconsentRevision("SERVICE","2",true);
        code(()->history.submit(b.member(),a.session(),List.of(new kr.nospo.terms.contract.Terms.Consent(version,true))),"INVALID_CREDENTIALS");
        reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true));
        mvc.perform(get("/api/v1/me/consents/pending").header("Authorization",bearer(a)).param("memberId",b.member().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        assertThat(reconsentPending(b).requiredAction()).isTrue();
        assertThat(consentStore.events(b.member())).hasSize(3);
    }

    @Test void reconsentLastInsertFailureRollsBackAllEventsAndCanBeRetried() {
        var a=emailAccount("rrollback@example.com","rrollback");
        UUID first=reconsentRevision("SERVICE","2",true),last=reconsentRevision("PRIVACY","2",true);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("fixture failure")).when(reconsentStoreSpy)
                .append(org.mockito.ArgumentMatchers.eq(a.member()),org.mockito.ArgumentMatchers.eq(last),
                        org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.any());
        try {
            assertThatThrownBy(()->reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(first,true),
                    new kr.nospo.terms.contract.Terms.Consent(last,true))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            org.mockito.Mockito.verify(reconsentStoreSpy).append(org.mockito.ArgumentMatchers.eq(a.member()),
                    org.mockito.ArgumentMatchers.eq(first),org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.any());
            assertThat(consentStore.events(a.member())).hasSize(3);
            assertThat(consentStore.ceiling(a.member())).isEqualTo(3);
            assertThat(reconsentPending(a).items()).hasSize(2);
        } finally {
            org.mockito.Mockito.doCallRealMethod().when(reconsentStoreSpy).append(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.any());
        }
        reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(first,true),new kr.nospo.terms.contract.Terms.Consent(last,true));
        assertThat(consentStore.events(a.member())).hasSize(5);
    }

    @Test void reconsentRecoveryKeepsNewFalseChoiceAndDoesNotReviveOldMandatoryDebt() {
        var a=emailAccount("rrecover@example.com","rrecover");
        clock.advance(10);append(a.member(),optional(),true);clock.advance(-5);
        var old=consentStore.events(a.member()).stream().map(ConsentStore.Ordered::event).toList();
        withdraw(a);
        reconsentRevision("SERVICE","2",clock.instant().minusSeconds(1),true,null,"PUBLISHED");
        reconsentRevision("SERVICE","3",clock.instant(),false,null,"PUBLISHED");
        jdbc.update("UPDATE term_version SET requires_reconsent=true WHERE term_version_id=?",id(optional()));
        var current=terms.current().stream().map(v->new kr.nospo.terms.contract.Terms.Consent(v.termVersionId(),v.required())).toList();
        var result=recovery.recover(new kr.nospo.lifecycle.application.RecoveryService.Command(recoveryProof("rrecover@example.com"),"replacement password",current));
        UUID session=UUID.fromString(decoder.decode(result.data().accessToken()).getClaimAsString("sid"));
        assertThat(history.pending(a.member(),session).items()).isEmpty();
        var after=consentStore.events(a.member());
        assertThat(after).hasSize(7);
        assertThat(after.stream().map(ConsentStore.Ordered::event).toList()).containsAll(old);
        var restoredChoice=history.submit(a.member(),session,List.of(new kr.nospo.terms.contract.Terms.Consent(optional(),false))).get(0);
        assertThat(restoredChoice.agreed()).isFalse();
        assertThat(after.stream().filter(e->e.event().termVersionId().equals(optional())).findFirst().orElseThrow().event()).isEqualTo(restoredChoice);
        assertThat(consentStore.events(a.member())).hasSize(7);
        assertThat(count("credit_transaction")).isEqualTo(1);
        assertThat(result.data().member().memberId()).isEqualTo(a.member());
    }

    @Test void reconsentUnresolvedRequirednessTransitionIsDeferredWithoutWriting() throws Exception {
        var a=emailAccount("rtransition@example.com","rtransition");
        UUID version=reconsentRevision("OPTIONAL","2",clock.instant(),true,true,"PUBLISHED");
        mvc.perform(get("/api/v1/me/consents/pending").header("Authorization",bearer(a)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_CHANGED"));
        reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(version,true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_CHANGED"));
        assertThat(consentStore.events(a.member())).hasSize(3);
    }

    @Test void reconsentMissingInitialRequiredChoiceTargetsLatestWithoutRequestingOldVersions() {
        var a=emailAccount("rmissing@example.com","rmissing");
        jdbc.update("DELETE FROM member_consent WHERE member_id=? AND term_version_id=?",id(a.member()),id(consents.get(0).termVersionId()));
        UUID latest=reconsentRevision("SERVICE","2",false);
        assertThat(reconsentPending(a).requiredAction()).isTrue();
        assertThat(reconsentPending(a).items()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).containsExactly(latest);
        reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(latest,true));
        assertThat(reconsentPending(a).items()).isEmpty();
    }

    @Test void reconsentOptionalMayRemainPendingWithoutRequiredActionAfterRequiredSubmit() {
        var a=emailAccount("rpartial@example.com","rpartial");
        UUID service=reconsentRevision("SERVICE","2",true),optional=reconsentRevision("OPTIONAL","2",true);
        var accepted=reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(service,true));
        assertThat(reconsentPending(a).requiredAction()).isFalse();
        assertThat(reconsentPending(a).items()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).containsExactly(optional);
        assertThat(reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(service,true))).isEqualTo(accepted);
        reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(optional,false));
        assertThat(reconsentPending(a).items()).isEmpty();
    }

    @Test void reconsentUndefinedKindScopeDoesNotSilentlyCompleteOrCreateAnObligation() throws Exception {
        var a=emailAccount("rkindscope@example.com","rkindscope");
        byte[] term=jdbc.queryForObject("SELECT term_id FROM term WHERE type='OPTIONAL'",byte[].class);
        for(String type:List.of("OPTIONAL","UNSCOPED")) {
            jdbc.update("UPDATE term SET type=? WHERE term_id=?",type,term);
            jdbc.update("UPDATE term_version SET required=? WHERE term_id=?",type.equals("OPTIONAL"),term);
            mvc.perform(get("/api/v1/me/consents/pending").header("Authorization",bearer(a)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_CHANGED"));
            reconsentPost(a,List.of(new kr.nospo.terms.contract.Terms.Consent(optional(),false)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TERMS_VERSION_CHANGED"));
            assertThat(consentStore.events(a.member())).hasSize(3);
        }
    }

    @Test void historyHttpContractIsAuthenticatedPrivateAndDoesNotAcceptAnotherMemberId() throws Exception {
        var first=emailAccount("history@example.com","history");
        var other=emailAccount("other@example.com","other");
        mvc.perform(get("/api/v1/me/consents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(first)).param("memberId",other.member().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].consentId").exists())
                .andExpect(jsonPath("$.data.items[0].termVersionId").exists())
                .andExpect(jsonPath("$.data.items[0].agreedAt").exists())
                .andExpect(jsonPath("$.data.hasNext").value(false)).andExpect(jsonPath("$.data.nextCursor").isEmpty());
        var firstIds=history.history(first.member(),null,100).items().stream().map(MemberConsents.Event::consentId).toList();
        assertThat(history.history(other.member(),null,100).items()).noneMatch(e->firstIds.contains(e.consentId()));
    }

    @Test void cursorPaginationHandlesSameTimestampWithoutDuplicatesAndExcludesNewEvents() {
        var a=emailAccount("pages@example.com","pages");
        var before=history.history(a.member(),null,100).items();
        var first=history.history(a.member(),null,1);
        append(a.member(),optional(),true);
        List<MemberConsents.Event> all=new ArrayList<>(first.items());
        String cursor=first.nextCursor();
        while(cursor!=null) {
            var page=history.history(a.member(),cursor,1);all.addAll(page.items());cursor=page.nextCursor();
        }
        assertThat(all).containsExactlyElementsOf(before);
        assertThat(all.stream().map(MemberConsents.Event::consentId).distinct().count()).isEqualTo(3);
        assertThat(history.history(a.member(),null,100).items()).hasSize(4);
    }

    @Test void otherMembersAndTamperedCursorsAreRejectedByHttp() throws Exception {
        var a=emailAccount("cursor@example.com","cursor");
        var b=emailAccount("second@example.com","second");
        var cursor=history.history(a.member(),null,1).nextCursor();
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(b)).param("cursor",cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        for(String bad:List.of("", "broken",cursor+"="))
            mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(a)).param("cursor",bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
    }

    @Test void limitsUseDefaultTwentyAndEnforceOneThroughOneHundred() throws Exception {
        var a=emailAccount("limits@example.com","limits");
        for(int i=0;i<22;i++) append(a.member(),optional(),i%2==0);
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        for(String limit:List.of("0","101","-1"))
            mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(a)).param("limit",limit))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(a)).param("limit","bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(a)).param("limit","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(25));
    }

    @Test void internalEventOrderSurvivesClockRollbackAndDoesNotChangeHistoricalChoices() {
        var a=emailAccount("order@example.com","order");
        clock.advance(1);append(a.member(),optional(),true);
        clock.advance(-2);append(a.member(),optional(),false);
        var events=consentStore.events(a.member());
        assertThat(events.get(0).event().agreed()).isFalse();
        assertThat(events.get(0).order()).isEqualTo(5);
        assertThat(events.get(1).event().agreed()).isTrue();
        assertThat(events.get(1).order()).isEqualTo(4);
        assertThat(events.get(0).event().agreedAt()).isBefore(events.get(1).event().agreedAt());
    }

    @Test void expiredAccessAndLoggedOutSessionCannotReadHistory() throws Exception {
        var a=emailAccount("session@example.com","session");
        auth.logout(a.session(),null);
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(a))).andExpect(status().isUnauthorized());
        var b=emailAccount("expired@example.com","expired");
        var now=java.time.Instant.now();
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("nospo")
                .subject(b.member().toString()).claim("sid",b.session().toString())
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(300)).build();
        var header=org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String expired=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header,claims)).getTokenValue();
        mvc.perform(get("/api/v1/me/consents").header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
    }

    @Test void withdrawalRemovesActiveHistoryAndRecoveryRestoresAllEventsPlusNewConsents() throws Exception {
        var a=emailAccount("restorehistory@example.com","restorehistory");
        append(a.member(),optional(),true);append(a.member(),optional(),false);
        var before=consentStore.events(a.member()).stream().map(ConsentStore.Ordered::event).toList();
        withdraw(a);
        code(()->history.history(a.member(),null,20),"INVALID_CREDENTIALS");
        assertThat(consentStore.events(a.member())).isEmpty();
        mvc.perform(get("/api/v1/me/consents").header("Authorization",bearer(a))).andExpect(status().isUnauthorized());
        var restored=recover(recoveryProof("restorehistory@example.com"));
        var after=consentStore.events(a.member());
        assertThat(after).hasSize(8);
        assertThat(after.stream().map(ConsentStore.Ordered::event).toList()).containsAll(before);
        assertThat(after.stream().map(ConsentStore.Ordered::order).distinct().count()).isEqualTo(8);
        mvc.perform(get("/api/v1/me/consents").header("Authorization","Bearer "+restored.data().accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(8));
        assertThat(count("credit_transaction")).isEqualTo(1);
    }

    @Test void historyWaitsForWithdrawalCommitAndNeverRecreatesInactiveHistory() throws Exception {
        var a=emailAccount("racehistory@example.com","racehistory");
        String proof=passwordProof(a,"WITHDRAWAL");
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),reading=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> withdrawal=pool.submit(()->transaction().executeWithoutResult(s->{
                members.lockActive(a.member());locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test barrier"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                withdrawals.withdraw(a.member(),a.session(),UUID.randomUUID(),proof);
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<String> read=pool.submit(()->{
                reading.countDown();
                try { history.history(a.member(),null,20);return "ACTIVE"; }
                catch(ApiException e) { return e.code(); }
            });
            assertThat(reading.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->read.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();withdrawal.get(20,TimeUnit.SECONDS);
            assertThat(read.get(20,TimeUnit.SECONDS)).isEqualTo("INVALID_CREDENTIALS");
            assertThat(consentStore.events(a.member())).isEmpty();
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void signupConsentRechecksEffectiveVersionAfterWaitingForTermLocks() throws Exception {
        var a=emailAccount("versionwait@example.com","versionwait");
        UUID previous=consents.get(0).termVersionId(),next=UUID.randomUUID();
        byte[] term=jdbc.queryForObject("SELECT term_id FROM term_version WHERE term_version_id=?",byte[].class,id(previous));
        jdbc.update("INSERT INTO term_version(term_version_id,term_id,version,title,content,required,publication_status,effective_at,requires_reconsent,created_at)"
                +" VALUES (?,?,'2.0','fixture','fixture',true,'PUBLISHED',?,true,?)",id(next),term,at(clock.instant().plusSeconds(1)),at(clock.instant()));
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),writing=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> owner=pool.submit(()->transaction().executeWithoutResult(s->{
                termStore.lock();locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test barrier"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var startedAt=clock.instant();
            Future<String> write=pool.submit(()->{
                try {
                    transaction().executeWithoutResult(s->{members.lockActive(a.member());writing.countDown();terms.recordSignup(a.member(),consents,startedAt);});
                    return "ACCEPTED";
                } catch(ApiException e) { return e.code(); }
            });
            assertThat(writing.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->write.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            clock.advance(2);release.countDown();owner.get(20,TimeUnit.SECONDS);
            assertThat(write.get(20,TimeUnit.SECONDS)).isEqualTo("TERMS_VERSION_CHANGED");
            assertThat(consentStore.events(a.member())).hasSize(3);
            assertThat(terms.current()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).contains(next).doesNotContain(previous);
        } finally { release.countDown();pool.shutdownNow(); }
    }
}
