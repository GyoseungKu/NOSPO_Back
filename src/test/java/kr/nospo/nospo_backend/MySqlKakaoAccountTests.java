package kr.nospo.nospo_backend;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kr.nospo.support.DatabaseGuard;
import kr.nospo.support.ApiException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlKakaoAccountTests extends KakaoAccountTests {
    @Autowired DatabaseGuard guard;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void concurrentDistinctRateScopesDoNotLockEachOthersMissingCounters() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                String scope = "distinct-rate:" + i;
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    guard.rateLimit(scope, 5, Duration.ofMinutes(15), clock.instant());
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("auth_rate_limit")).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT request_count FROM auth_rate_limit", Integer.class))
                .containsOnly(1);
    }

    @Test
    void concurrentSameRateScopeKeepsExactLimitAndResetsOnlyAfterExpiry() throws Exception {
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<String>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    guard.rateLimit("shared-rate", 5, Duration.ofMinutes(15), clock.instant());
                    return "OK";
                } catch (ApiException ex) { return ex.code(); }
            }));
            start.countDown();
            for (var future : futures) outcomes.add(future.get(10, TimeUnit.SECONDS));
        }
        assertThat(Collections.frequency(outcomes, "OK")).isEqualTo(5);
        assertThat(Collections.frequency(outcomes, "RATE_LIMITED")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT request_count FROM auth_rate_limit", Integer.class)).isEqualTo(5);
        clock.now.set(clock.instant().plus(Duration.ofMinutes(15)));
        guard.rateLimit("shared-rate", 5, Duration.ofMinutes(15), clock.instant());
        assertThat(jdbc.queryForObject("SELECT request_count FROM auth_rate_limit", Integer.class)).isEqualTo(1);
    }

    @Test
    void ownerAssignmentDoesNotWaitForUnrelatedSocialProof() throws Exception {
        var owner = emailAccount("owner@example.com", "owner");
        String social = signupProof("98765", "unrelated@example.com", true);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> tx.execute(s -> {
                jdbc.queryForList("SELECT proof_id FROM auth_proof WHERE proof_hash=? FOR UPDATE", secrets.hash(social));
                locked.countDown();
                try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var assignment = pool.submit(() -> tx.execute(s -> {
                    guard.lock("signup:email:owner@example.com", clock.instant());
                    store.assignOwner("owner@example.com", owner.member());
                    return null;
                }));
                assignment.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT member_id FROM auth_proof WHERE proof_hash=?",
                byte[].class, secrets.hash(social))).isNull();
    }

    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
}
