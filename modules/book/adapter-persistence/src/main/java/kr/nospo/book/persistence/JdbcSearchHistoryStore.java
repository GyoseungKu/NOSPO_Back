package kr.nospo.book.persistence;

import static kr.nospo.support.SqlIds.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.nospo.book.application.SearchHistoryStore;
import kr.nospo.book.contract.SearchHistory;
import kr.nospo.book.contract.SearchType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSearchHistoryStore implements SearchHistoryStore {
    private static final String OWNED = """
            SELECT h.history_id,h.query_text,h.search_type,h.searched_at FROM book_search_history h
            JOIN member m ON m.member_id=h.member_id AND m.status='ACTIVE' WHERE h.member_id=?
            """;
    private static final String NEWEST = " ORDER BY h.searched_at DESC,h.history_id DESC LIMIT ?";
    private final JdbcTemplate db;

    public JdbcSearchHistoryStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public boolean lockActiveMember(UUID memberId) {
        return !db.query("SELECT member_id FROM member WHERE member_id=? AND status='ACTIVE' FOR UPDATE",
                (row, index) -> row.getBytes(1), id(memberId)).isEmpty();
    }

    @Override
    public void record(UUID memberId, String query, byte[] queryKey, SearchType type, Instant now) {
        // The member lock serializes this update and the retention check.
        var previous = list(memberId, null, null, 1);
        if (!previous.isEmpty() && !now.isAfter(previous.getFirst().searchedAt())) {
            now = previous.getFirst().searchedAt().plusNanos(1000);
        }
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        db.update("""
                INSERT INTO book_search_history(history_id,member_id,query_text,query_key,search_type,searched_at)
                VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE query_text=?,searched_at=?
                """, id(UUID.randomUUID()), id(memberId), query, queryKey, type.name(), timestamp, query, timestamp);
        var retained = list(memberId, null, null, 101);
        if (retained.size() > 100) delete(memberId, retained.getLast().historyId());
    }

    @Override
    public List<SearchHistory> list(UUID memberId, Instant before, UUID beforeId, int fetch) {
        if (before == null) return db.query(OWNED + NEWEST, this::history, id(memberId), fetch);
        var timestamp = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
        return db.query(OWNED + " AND (h.searched_at<? OR (h.searched_at=? AND h.history_id<?))" + NEWEST,
                this::history, id(memberId), timestamp, timestamp, id(beforeId), fetch);
    }

    @Override
    public boolean delete(UUID memberId, UUID historyId) {
        return db.update("DELETE FROM book_search_history WHERE member_id=? AND history_id=?",
                id(memberId), id(historyId)) == 1;
    }

    @Override
    public void deleteAll(UUID memberId) {
        db.update("DELETE FROM book_search_history WHERE member_id=?", id(memberId));
    }

    private SearchHistory history(ResultSet row, int index) throws SQLException {
        return new SearchHistory(uuid(row.getBytes(1)), row.getString(2), SearchType.valueOf(row.getString(3)),
                row.getObject(4, LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
