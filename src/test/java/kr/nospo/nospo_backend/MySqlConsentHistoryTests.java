package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;
import kr.nospo.lifecycle.application.PurgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import java.util.UUID;

/** Required Docker; all databases and external identity adapters are isolated fixtures. */
@Testcontainers
class MySqlConsentHistoryTests extends ConsentHistoryTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.7");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);
        p.add("spring.datasource.password",MYSQL::getPassword);p.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
    }
    @Autowired PurgeService purger;

    @Test void reconsentConcurrentIdenticalSubmissionsReturnOneStoredEvent() throws Exception {
        var a=emailAccount("rconcurrent@example.com","rconcurrent");
        UUID version=reconsentRevision("SERVICE","2",true);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<java.util.List<kr.nospo.terms.contract.MemberConsents.Event>> call=()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier");
                return reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true));
            };
            var first=pool.submit(call);var second=pool.submit(call);start.countDown();
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(consentStore.events(a.member())).hasSize(4);
            assertThat(reconsentPending(a).items()).isEmpty();
        } finally { start.countDown();pool.shutdownNow(); }
    }

    @Test void reconsentConcurrentOppositeChoicesDoNotOverwriteTheWinningDecision() throws Exception {
        var a=emailAccount("rdifferent@example.com","rdifferent");
        UUID version=reconsentRevision("OPTIONAL","2",true);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<Boolean,java.util.concurrent.Callable<String>> call=agreed->()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier");
                try { reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,agreed));return "ACCEPTED:"+agreed; }
                catch(kr.nospo.support.ApiException e) { return e.code(); }
            };
            var first=pool.submit(call.apply(true));var second=pool.submit(call.apply(false));start.countDown();
            var results=java.util.List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(v->v.startsWith("ACCEPTED:")).count()).isEqualTo(1);
            assertThat(results).contains("TERMS_VERSION_CHANGED");
            var choice=consentStore.events(a.member()).get(0).event();
            assertThat(results).contains("ACCEPTED:"+choice.agreed());
            assertThat(consentStore.events(a.member())).hasSize(4);
        } finally { start.countDown();pool.shutdownNow(); }
    }

    @Test void reconsentWaitingSubmitCannotRecreateHistoryAfterWithdrawal() throws Exception {
        var a=emailAccount("rwithdrawrace@example.com","rwithdrawrace");
        UUID version=reconsentRevision("SERVICE","2",true);
        String proof=passwordProof(a,"WITHDRAWAL");
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var writing=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var withdrawal=pool.submit(()->transaction().executeWithoutResult(s->{
                members.lockActive(a.member());locked.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                withdrawals.withdraw(a.member(),a.session(),UUID.randomUUID(),proof);
            }));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var write=pool.submit(()->{
                writing.countDown();
                try { reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true));return "ACCEPTED"; }
                catch(kr.nospo.support.ApiException e) { return e.code(); }
            });
            assertThat(writing.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->write.get(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();withdrawal.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(write.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("INVALID_CREDENTIALS");
            assertThat(consentStore.events(a.member())).isEmpty();
            code(()->reconsentPending(a),"INVALID_CREDENTIALS");
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void reconsentTermLockWaitUsesNewClockAndRejectsSupersededVersion() throws Exception {
        var a=emailAccount("rtermwait@example.com","rtermwait");
        UUID previous=reconsentRevision("SERVICE","2",true);
        UUID latest=reconsentRevision("SERVICE","3",clock.instant().plusSeconds(1),false,null,"PUBLISHED");
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var writing=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var owner=pool.submit(()->transaction().executeWithoutResult(s->{
                termStore.lock();locked.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var write=pool.submit(()->{
                writing.countDown();
                try { reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(previous,true));return "ACCEPTED"; }
                catch(kr.nospo.support.ApiException e) { return e.code(); }
            });
            assertThat(writing.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->write.get(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            clock.advance(2);release.countDown();owner.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(write.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("TERMS_VERSION_CHANGED");
            assertThat(reconsentPending(a).items()).extracting(kr.nospo.terms.contract.Terms.Version::termVersionId).containsExactly(latest);
            assertThat(consentStore.events(a.member())).hasSize(3);
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void reconsentRevokedSessionIsRecheckedAfterWaitingForMemberLock() throws Exception {
        var a=emailAccount("rrevokequeue@example.com","rrevokequeue");
        UUID version=reconsentRevision("SERVICE","2",true);
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var writing=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var owner=pool.submit(()->transaction().executeWithoutResult(s->{
                members.lockActive(a.member());locked.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var write=pool.submit(()->{
                writing.countDown();
                try { reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true));return "ACCEPTED"; }
                catch(kr.nospo.support.ApiException e) { return e.code(); }
            });
            assertThat(writing.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->write.get(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            auth.logout(a.session(),null);release.countDown();owner.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(write.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("INVALID_CREDENTIALS");
            assertThat(consentStore.events(a.member())).hasSize(3);
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void reconsentOldSessionCannotSubmitWhileRecoveryReactivatesTheSameMember() throws Exception {
        var a=emailAccount("rrecoverrace@example.com","rrecoverrace");
        withdraw(a);
        reconsentRevision("SERVICE","2",true);
        var current=terms.current().stream().map(v->new kr.nospo.terms.contract.Terms.Consent(v.termVersionId(),v.required())).toList();
        String proof=recoveryProof("rrecoverrace@example.com");
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var restore=pool.submit(()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier");
                return recovery.recover(new kr.nospo.lifecycle.application.RecoveryService.Command(proof,"replacement password",current));
            });
            var write=pool.submit(()->{
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier");
                try { history.submit(a.member(),a.session(),current);return "ACCEPTED"; }
                catch(kr.nospo.support.ApiException e) { return e.code(); }
            });
            start.countDown();
            var restored=restore.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(write.get(30,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("INVALID_CREDENTIALS");
            UUID session=UUID.fromString(decoder.decode(restored.data().accessToken()).getClaimAsString("sid"));
            assertThat(history.pending(a.member(),session).items()).isEmpty();
            assertThat(consentStore.events(a.member())).hasSize(6);
            assertThat(count("credit_transaction")).isEqualTo(1);
        } finally { start.countDown();pool.shutdownNow(); }
    }

    @Test void reconsentWinningSubmissionIsArchivedBeforeWaitingWithdrawalAndRestoredAsHistory() throws Exception {
        var a=emailAccount("rarchiveorder@example.com","rarchiveorder");
        UUID version=reconsentRevision("SERVICE","2",true);
        String proof=passwordProof(a,"WITHDRAWAL");
        var stored=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var withdrawing=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var submit=pool.submit(()->transaction().execute(s->{
                members.lockActive(a.member());
                var event=reconsentSubmit(a,new kr.nospo.terms.contract.Terms.Consent(version,true)).get(0);
                stored.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                return event;
            }));
            assertThat(stored.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var withdrawal=pool.submit(()->{
                withdrawing.countDown();withdrawals.withdraw(a.member(),a.session(),UUID.randomUUID(),proof);
            });
            assertThat(withdrawing.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->withdrawal.get(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();var accepted=submit.get(20,java.util.concurrent.TimeUnit.SECONDS);withdrawal.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(consentStore.events(a.member())).isEmpty();
            var current=terms.current().stream().map(v->new kr.nospo.terms.contract.Terms.Consent(v.termVersionId(),v.required())).toList();
            recovery.recover(new kr.nospo.lifecycle.application.RecoveryService.Command(recoveryProof("rarchiveorder@example.com"),"replacement password",current));
            assertThat(consentStore.events(a.member()).stream().map(kr.nospo.terms.application.ConsentStore.Ordered::event).toList()).contains(accepted).hasSize(7);
            assertThat(count("credit_transaction")).isEqualTo(1);
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void finalPurgeRemovesOrderedHistoryAndPreservesPublicTermVersions() {
        var a=emailAccount("purgehistory@example.com","purgehistory");
        append(a.member(),optional(),true);
        withdraw(a);clock.advance(30*86400);
        assertThat(purger.processBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_consent WHERE member_id=?",Integer.class,id(a.member()))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE member_id=?",Integer.class,id(a.member()))).isZero();
        assertThat(count("term_version")).isEqualTo(3);
        code(()->history.history(a.member(),null,20),"INVALID_CREDENTIALS");
    }
}
