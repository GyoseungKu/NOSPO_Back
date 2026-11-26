package kr.nospo.book.persistence;

import static kr.nospo.support.SqlIds.id;
import static kr.nospo.support.SqlIds.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.book.application.ShelfStore;
import kr.nospo.book.contract.BookType;
import kr.nospo.book.contract.PositionContext;
import kr.nospo.book.contract.ShelfSort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcShelfStore implements ShelfStore {
    private static final String OWNED = """
            SELECT s.book_id,s.added_at,p.current_value,p.highest_value,p.last_progress_at,p.revision,b.title,
                   e.volume_label,e.chapter_label,e.episode_label
            FROM book_shelf s
            JOIN member m ON m.member_id=s.member_id AND m.status='ACTIVE'
            JOIN book_progress p ON p.member_id=s.member_id AND p.book_id=s.book_id
            JOIN book_edition b ON b.book_id=s.book_id
            LEFT JOIN book_episode e ON b.book_type='WEB_NOVEL' AND e.book_id=s.book_id AND e.sequence_no=p.current_value
            WHERE s.member_id=:owner
            """;

    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate named;

    public JdbcShelfStore(JdbcTemplate db) {
        this.db = db;
        named = new NamedParameterJdbcTemplate(db);
    }

    @Override
    public Optional<Progress> progress(UUID owner, UUID bookId) {
        return db.query("""
                SELECT current_value,highest_value,last_progress_at,revision FROM book_progress
                WHERE member_id=? AND book_id=?
                """, (row, n) -> readProgress(row), id(owner), id(bookId)).stream().findFirst();
    }

    @Override
    public void insertProgress(UUID owner, UUID bookId, Progress progress) {
        db.update("""
                INSERT INTO book_progress(member_id,book_id,current_value,highest_value,last_progress_at,revision)
                VALUES (?,?,?,?,?,?)
                """, id(owner), id(bookId), progress.current(), progress.highest(), utc(progress.lastProgressAt()),
                progress.revision());
    }

    @Override
    public void updateProgress(UUID owner, UUID bookId, Progress progress) {
        db.update("""
                UPDATE book_progress SET current_value=?,highest_value=?,last_progress_at=?,revision=?
                WHERE member_id=? AND book_id=?
                """, progress.current(), progress.highest(), utc(progress.lastProgressAt()), progress.revision(),
                id(owner), id(bookId));
    }

    @Override
    public void attach(UUID owner, UUID bookId, Instant addedAt) {
        db.update("INSERT INTO book_shelf(member_id,book_id,added_at) VALUES (?,?,?)", id(owner), id(bookId), utc(addedAt));
    }

    @Override
    public boolean detach(UUID owner, UUID bookId) {
        return db.update("DELETE FROM book_shelf WHERE member_id=? AND book_id=?", id(owner), id(bookId)) == 1;
    }

    @Override
    public Optional<Entry> find(UUID owner, UUID bookId) {
        return named.query(OWNED + " AND s.book_id=:book", new MapSqlParameterSource("owner", id(owner))
                .addValue("book", id(bookId)), this::entry).stream().findFirst();
    }

    @Override
    public List<Entry> list(UUID owner, BookType type, ShelfSort sort, Boundary after, int fetch) {
        String key = switch (sort) {
            case TITLE -> "CAST(b.title AS BINARY(2048))";
            case RECENT_ADDED -> "s.added_at";
            case RECENT_ACTIVITY -> "COALESCE(p.last_progress_at,s.added_at)";
        };
        boolean byTitle = sort == ShelfSort.TITLE;
        String direction = byTitle ? " ASC" : " DESC";
        String comparison = byTitle ? ">" : "<";
        String placeholder = byTitle ? "CAST(:key AS BINARY(2048))" : ":key";
        var args = new MapSqlParameterSource("owner", id(owner)).addValue("fetch", fetch);
        String sql = OWNED;
        if (type != null) {
            sql += " AND b.book_type=:type";
            args.addValue("type", type.name());
        }
        if (after != null) {
            sql += " AND (" + key + comparison + placeholder + " OR (" + key + "=" + placeholder
                    + " AND s.book_id" + comparison + ":book))";
            args.addValue("key", byTitle ? after.sortKey() : utc(Instant.parse(after.sortKey())))
                    .addValue("book", id(after.bookId()));
        }
        return named.query(sql + " ORDER BY " + key + direction + ",s.book_id" + direction + " LIMIT :fetch",
                args, this::entry);
    }

    @Override
    public void record(UUID owner, UUID bookId, int from, int to, int addedUnits, Instant recordedAt) {
        db.update("""
                INSERT INTO book_reading_record(record_id,member_id,book_id,from_value,to_value,added_units,recorded_at)
                VALUES (?,?,?,?,?,?,?)
                """, id(UUID.randomUUID()), id(owner), id(bookId), from, to, addedUnits, utc(recordedAt));
    }

    private Entry entry(ResultSet row, int ignored) throws SQLException {
        return new Entry(uuid(row.getBytes("book_id")), readProgress(row),
                new PositionContext(row.getString("volume_label"), row.getString("chapter_label"), row.getString("episode_label")),
                instant(row, "added_at"), row.getString("title"));
    }

    private static Progress readProgress(ResultSet row) throws SQLException {
        return new Progress(row.getInt("current_value"), row.getInt("highest_value"),
                instant(row, "last_progress_at"), row.getLong("revision"));
    }

    private static LocalDateTime utc(Instant time) {
        return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        var value = row.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
