package kr.nospo.nospo_backend;

import static kr.nospo.support.SqlIds.id;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class MySqlBookMigrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7");

    @Test
    void upgradesVersionElevenAndMatchesBookErd() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(Flyway.configure().dataSource(source).target("11").load().migrate().migrationsExecuted)
                .isEqualTo(11);
        var db = new JdbcTemplate(source);
        UUID member = UUID.randomUUID();
        UUID notice = UUID.randomUUID();
        db.update("""
                INSERT INTO member(member_id,nickname,nickname_key,phone_number,created_at,updated_at)
                VALUES (?,'fixture','fixture','+821000000000',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id(member));
        db.update("""
                INSERT INTO notice(notice_id,title,summary,content,created_at,updated_at)
                VALUES (?,'previous','','body',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id(notice));

        var flyway = Flyway.configure().dataSource(source).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(db.queryForObject("SELECT nickname FROM member WHERE member_id=?", String.class, id(member)))
                .isEqualTo("fixture");
        assertThat(db.queryForObject("SELECT title FROM notice WHERE notice_id=?", String.class, id(notice)))
                .isEqualTo("previous");

        JsonNode erd = new ObjectMapper().readTree(Path.of("erd/NOSPO_Book.erd.json").toFile());
        var collections = erd.path("collections");
        var tables = collections.path("tableEntities");
        var columns = collections.path("tableColumnEntities");
        var bookTableNames = new ArrayList<String>();
        for (var table : tables) {
            String name = table.path("name").asString();
            if (name.equals("member")) continue; // Identity's member PK is a reference in the diagram.
            bookTableNames.add(name);
            var actual = db.queryForList("""
                    SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY,COLUMN_DEFAULT
                    FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?
                    ORDER BY ordinal_position
                    """, name);
            assertThat(actual).hasSize(table.path("columnIds").size());
            for (int i = 0; i < actual.size(); i++) {
                var expected = columns.path(table.path("columnIds").get(i).asString());
                var row = actual.get(i);
                int options = expected.path("options").asInt();
                assertThat(row.get("COLUMN_NAME")).isEqualTo(expected.path("name").asString());
                assertThat(row.get("COLUMN_TYPE")).isEqualTo(expected.path("dataType").asString().toLowerCase(Locale.ROOT));
                assertThat(row.get("IS_NULLABLE")).isEqualTo((options & 8) != 0 ? "NO" : "YES");
                assertThat("PRI".equals(row.get("COLUMN_KEY"))).isEqualTo((options & 2) != 0);
                assertThat(row.get("COLUMN_DEFAULT")).isNull();
            }
        }
        assertThat(db.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'book%'
                """, String.class)).containsExactlyInAnyOrderElementsOf(bookTableNames);

        for (var index : collections.path("indexEntities")) {
            var expected = new ArrayList<String>();
            for (var indexColumnId : index.path("indexColumnIds")) {
                var indexColumn = collections.path("indexColumnEntities").path(indexColumnId.asString());
                expected.add(columns.path(indexColumn.path("columnId").asString()).path("name").asString());
            }
            String table = tables.path(index.path("tableId").asString()).path("name").asString();
            assertThat(db.queryForList("""
                    SELECT column_name FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name=? AND index_name=? AND non_unique=?
                    ORDER BY seq_in_index
                    """, String.class, table, index.path("name").asString(), index.path("unique").asBoolean() ? 0 : 1))
                    .containsExactlyElementsOf(expected);
        }

        var foreignKeys = collections.path("relationshipEntities");
        var expectedNames = new ArrayList<String>();
        for (var foreignKey : foreignKeys) {
            String name = foreignKey.path("id").asString();
            expectedNames.add(name);
            var row = db.queryForMap("""
                    SELECT k.TABLE_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.DELETE_RULE
                    FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r
                    ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name
                    WHERE k.table_schema=DATABASE() AND k.constraint_name=?
                    """, name);
            assertThat(row.get("TABLE_NAME")).isEqualTo(tables.path(foreignKey.path("end").path("tableId").asString())
                    .path("name").asString());
            assertThat(row.get("REFERENCED_TABLE_NAME")).isEqualTo(tables.path(foreignKey.path("start").path("tableId").asString())
                    .path("name").asString());
            assertThat(row.get("COLUMN_NAME")).isEqualTo(columns.path(foreignKey.path("end").path("columnIds")
                    .get(0).asString()).path("name").asString());
            assertThat(row.get("REFERENCED_COLUMN_NAME")).isEqualTo(columns.path(foreignKey.path("start").path("columnIds")
                    .get(0).asString()).path("name").asString());
            assertThat((String) row.get("DELETE_RULE")).isIn(foreignKey.path("onDelete").asInt() == 4
                    ? List.of("CASCADE") : List.of("RESTRICT", "NO ACTION"));
        }
        assertThat(db.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema=DATABASE()
                AND table_name LIKE 'book%' AND constraint_type='FOREIGN KEY'
                """, String.class)).containsExactlyInAnyOrderElementsOf(expectedNames);
        assertThat(db.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema=DATABASE()
                AND table_name LIKE 'book%' AND constraint_type='CHECK'
                """, String.class)).containsExactlyInAnyOrder("ck_book_edition_type", "ck_book_edition_total",
                    "ck_book_episode_sequence", "ck_book_search_history_type");
    }
}
