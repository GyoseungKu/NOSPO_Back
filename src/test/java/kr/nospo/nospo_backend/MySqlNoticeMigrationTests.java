package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class MySqlNoticeMigrationTests {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7");

    @Test
    void versionTenPreservesV9RowsAndMatchesErdConstraints() {
        var source = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("8").load().migrate();
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='notice'
                """, Integer.class)).isZero();
        var versionNine = Flyway.configure().dataSource(source).target("9").load();
        assertThat(versionNine.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(versionNine.info().current().getVersion().getVersion()).isEqualTo("9");
        UUID existing = UUID.randomUUID();
        db.update("""
                INSERT INTO notice(notice_id,title,summary,content,created_at,updated_at)
                VALUES (?, 'existing', '', 'body', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id(existing));
        var flyway = Flyway.configure().dataSource(source).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(db.queryForMap("SELECT deleted_at FROM notice WHERE notice_id=?", id(existing)).get("deleted_at")).isNull();
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN ('member','account','member_consent')
                """, Integer.class)).isEqualTo(3);

        var erd = new ObjectMapper().readTree(Path.of("docs/NOSPO_Notice.erd.json").toFile());
        var collections = erd.path("collections");
        var columns = collections.path("tableColumnEntities");
        var table = collections.path("tableEntities").path("t_notice");
        assertThat(table.path("name").asString()).isEqualTo("notice");
        var names = db.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='notice' ORDER BY ordinal_position
                """, String.class);
        assertThat(names).hasSize(table.path("columnIds").size());
        for (int i = 0; i < names.size(); i++) {
            var expected = columns.path(table.path("columnIds").path(i).asString());
            var actual = db.queryForMap("""
                    SELECT column_type, is_nullable, column_default, column_key
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='notice' AND column_name=?
                    """, names.get(i));
            assertThat(names.get(i)).isEqualTo(expected.path("name").asString());
            String type = expected.path("dataType").asString().toLowerCase(java.util.Locale.ROOT);
            assertThat(actual.get("column_type")).isEqualTo(type.equals("boolean") ? "tinyint(1)" : type);
            int flags = expected.path("options").asInt();
            assertThat(actual.get("is_nullable")).isEqualTo((flags & 8) == 8 ? "NO" : "YES");
            assertThat(actual.get("column_key").equals("PRI")).isEqualTo((flags & 2) == 2);
            assertThat(actual.get("column_default")).isEqualTo(
                    expected.path("default").asString().equals("FALSE") ? "0" : null);
        }
        var index = collections.path("indexEntities").path("ix_notice_public_order");
        var indexColumns = collections.path("indexColumnEntities");
        var expectedOrder = new java.util.ArrayList<String>();
        for (var columnId : index.path("indexColumnIds")) {
            var col = indexColumns.path(columnId.asString());
            expectedOrder.add(columns.path(col.path("columnId").asString()).path("name").asString()
                    + ":" + (col.path("orderType").asInt() == 2 ? "D" : "A"));
        }
        assertThat(index.path("unique").asBoolean()).isFalse();
        assertThat(db.queryForList("""
                SELECT CONCAT(column_name, ':', collation) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='notice'
                  AND index_name=? AND non_unique=1 ORDER BY seq_in_index
                """, String.class, index.path("name").asString())).containsExactlyElementsOf(expectedOrder);
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema=DATABASE() AND table_name='notice' AND constraint_type='FOREIGN KEY'
                """, Integer.class)).isZero();

        var notice = UUID.randomUUID();
        var now = Instant.parse("2026-11-19T01:02:03.123456Z");
        String insert = """
                INSERT INTO notice(notice_id,title,summary,content,created_at,updated_at)
                VALUES (?,?,?,?,?,?)
                """;
        db.update(insert, id(notice), "title", "", "a".repeat(50000), at(now), at(now));
        db.queryForObject("SELECT * FROM notice WHERE notice_id=?", (row, n) -> {
            assertThat(uuid(row.getBytes("notice_id"))).isEqualTo(notice);
            assertThat(row.getBoolean("published")).isFalse();
            assertThat(row.getBoolean("pinned")).isFalse();
            assertThat(row.getTimestamp("published_at")).isNull();
            assertThat(row.getTimestamp("created_at").toInstant()).isEqualTo(now);
            return true;
        }, id(notice));
        assertThatThrownBy(() -> db.update(insert, id(notice), "title", "", "duplicate", at(now), at(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(insert, id(UUID.randomUUID()), "title", null, "text", at(now), at(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertCheck("ck_notice_title_length",
                () -> db.update(insert, id(UUID.randomUUID()), "", "", "text", at(now), at(now)));
        assertCheck("ck_notice_content_length",
                () -> db.update("UPDATE notice SET content='' WHERE notice_id=?", id(notice)));
        assertCheck("ck_notice_content_length",
                () -> db.update("UPDATE notice SET content=? WHERE notice_id=?", "a".repeat(50001), id(notice)));
        assertCheck("ck_notice_published_at",
                () -> db.update("UPDATE notice SET published=TRUE WHERE notice_id=?", id(notice)));
        assertCheck("ck_notice_pinned",
                () -> db.update("UPDATE notice SET pinned=2 WHERE notice_id=?", id(notice)));
        assertCheck("ck_notice_published",
                () -> db.update("UPDATE notice SET published=2 WHERE notice_id=?", id(notice)));
        db.update("UPDATE notice SET published=TRUE, published_at=? WHERE notice_id=?", at(now), id(notice));
        db.update("UPDATE notice SET published=FALSE WHERE notice_id=?", id(notice));
        Instant publishedAt = db.queryForObject("SELECT published_at FROM notice WHERE notice_id=?",
                (row, n) -> row.getTimestamp(1).toInstant(), id(notice));
        assertThat(publishedAt).isEqualTo(now);
    }

    private static void assertCheck(String constraint, org.assertj.core.api.ThrowableAssert.ThrowingCallable write) {
        assertThatThrownBy(write).isInstanceOfSatisfying(UncategorizedSQLException.class, failure -> {
            SQLException sql = failure.getSQLException();
            assertThat(sql.getErrorCode()).isEqualTo(3819);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }
}
