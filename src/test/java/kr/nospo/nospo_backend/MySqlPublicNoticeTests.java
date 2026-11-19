package kr.nospo.nospo_backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MySqlPublicNoticeTests extends PublicNoticeTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void publicOrderIndexIsAvailableToMySql() {
        var plan = db.queryForMap("""
                EXPLAIN SELECT notice_id, title, summary, pinned, published_at, updated_at
                FROM notice WHERE deleted_at IS NULL AND published = TRUE AND published_at <= ?
                ORDER BY pinned DESC, published_at DESC, notice_id DESC LIMIT ?
                """, utc(NOW), 21);
        assertThat((String) plan.get("possible_keys")).contains("ix_notice_public_order");
        var oldest = db.queryForMap("""
                EXPLAIN SELECT notice_id, title, summary, pinned, published_at, updated_at
                FROM notice WHERE deleted_at IS NULL AND published = TRUE AND published_at <= ?
                ORDER BY pinned DESC, published_at ASC, notice_id ASC LIMIT ?
                """, utc(NOW), 21);
        assertThat((String) oldest.get("possible_keys")).contains("ix_notice_public_order");
        System.out.printf("Notice EXPLAIN latest key=%s extra=%s; oldest key=%s extra=%s%n",
                plan.get("key"), plan.get("Extra"), oldest.get("key"), oldest.get("Extra"));
    }
}
