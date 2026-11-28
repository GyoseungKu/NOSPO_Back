package kr.nospo.ai.persistence;

import kr.nospo.ai.application.AiQuestionStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static kr.nospo.ai.application.AiBookContext.*;
import static kr.nospo.support.SqlIds.*;

@Repository
public class JdbcAiQuestionStore implements AiQuestionStore {
    private static final String QUESTIONS = """
            SELECT q.*, c.book_id, c.position_unit FROM ai_question q
            JOIN ai_conversation c ON c.conversation_id=q.conversation_id
            """;
    private final JdbcTemplate db;

    public JdbcAiQuestionStore(JdbcTemplate db) { this.db = db; }

    public Optional<Question> question(UUID value, boolean job) {
        return db.query(QUESTIONS + (job ? " WHERE q.job_id=?" : " WHERE q.question_id=?"),
                JdbcAiQuestionStore::question, id(value)).stream().findFirst();
    }

    public Optional<Question> request(UUID member, UUID key) {
        return db.query(QUESTIONS + " WHERE q.member_id=? AND q.request_key=?",
                JdbcAiQuestionStore::question, id(member), id(key)).stream().findFirst();
    }

    public void expireRequest(UUID question) {
        db.update("UPDATE ai_question SET request_key=NULL WHERE question_id=?", id(question));
    }

    public Optional<Conversation> conversation(UUID member, UUID conversation) {
        return db.query("SELECT * FROM ai_conversation WHERE conversation_id=? AND member_id=?",
                JdbcAiQuestionStore::conversation, id(conversation), id(member)).stream().findFirst();
    }

    public List<Conversation> conversations(UUID member, UUID book, Cursor cursor, int limit) {
        String sql = "SELECT * FROM ai_conversation WHERE member_id=?";
        var params = new java.util.ArrayList<Object>();
        params.add(id(member));
        if (book != null) {
            sql += " AND book_id=?";
            params.add(id(book));
        }
        if (cursor != null) {
            sql += " AND (created_at<? OR (created_at=? AND conversation_id<?))";
            params.add(at(cursor.time()));
            params.add(at(cursor.time()));
            params.add(id(cursor.id()));
        }
        params.add(limit);
        return db.query(sql + " ORDER BY created_at DESC,conversation_id DESC LIMIT ?",
                JdbcAiQuestionStore::conversation, params.toArray());
    }

    public boolean pending(UUID conversation) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ai_question WHERE conversation_id=? AND status IN ('QUEUED','RUNNING'))
                """, Boolean.class, id(conversation)));
    }

    public void createConversation(UUID member, UUID conversation, UUID book, Position position, String title, Instant now) {
        db.update("""
                INSERT INTO ai_conversation
                  (conversation_id,member_id,book_id,position_unit,position_value,title,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id(conversation), id(member), id(book), position.unit().name(), position.value(), title, at(now), at(now));
    }

    public void touchConversation(UUID conversation, Position position, Instant now) {
        db.update("""
                UPDATE ai_conversation SET position_value=CASE WHEN position_value<? THEN ? ELSE position_value END,
                  updated_at=? WHERE conversation_id=?
                """, position.value(), position.value(), at(now), id(conversation));
    }

    public void insert(Question q, UUID key) {
        db.update("""
                INSERT INTO ai_question (question_id,job_id,conversation_id,member_id,request_key,
                    withdrawal_generation,requested_conversation,requested_unit,requested_value,position_value,
                    wiki_version,wiki_context,question,status,created_at,deadline)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?)
                """, id(q.id()), id(q.job()), id(q.conversation()), id(q.member()), id(key), q.generation(),
                id(q.requestedConversation()), q.requestedUnit(), q.requestedValue(), q.position().value(),
                q.version(), q.context(), q.text(), at(q.created()), at(q.deadline()));
    }

    public List<UUID> expired(Instant now) {
        return db.query("""
                SELECT question_id FROM ai_question WHERE status IN ('QUEUED','RUNNING') AND deadline<=?
                ORDER BY deadline LIMIT 50
                """, (r, n) -> uuid(r.getBytes(1)), at(now));
    }

    public List<UUID> queued(Instant now) {
        return db.query("""
                SELECT question_id FROM ai_question WHERE status='QUEUED' AND deadline>?
                ORDER BY deadline LIMIT 8
                """, (r, n) -> uuid(r.getBytes(1)), at(now));
    }

    public boolean start(UUID question, Instant now) {
        return db.update("""
                UPDATE ai_question SET status='RUNNING', started_at=?
                WHERE question_id=? AND status='QUEUED' AND deadline>?
                """, at(now), id(question), at(now)) == 1;
    }

    public void finish(Question q, Instant now, String answer, String citations, String error) {
        db.update("""
                UPDATE ai_question SET status=?,completed_at=?,answer=?,citations=?,error_code=?,wiki_context=NULL
                WHERE question_id=?
                """, error == null ? "SUCCEEDED" : "FAILED", at(now), answer, citations, error, id(q.id()));
    }

    public void delta(UUID question, int sequence, String content) {
        db.update("INSERT INTO ai_question_delta(question_id,sequence_number,content) VALUES (?,?,?)",
                id(question), sequence, content);
    }

    public List<Delta> deltas(UUID question, int after) {
        return db.query("""
                SELECT sequence_number,content FROM ai_question_delta
                WHERE question_id=? AND sequence_number>? ORDER BY sequence_number LIMIT 100
                """, (r, n) -> new Delta(r.getInt(1), r.getString(2)), id(question), after);
    }

    public int lastDelta(UUID question) {
        return db.queryForObject("""
                SELECT COALESCE(MAX(sequence_number),1) FROM ai_question_delta WHERE question_id=?
                """, Integer.class, id(question));
    }

    public void pruneDeltas(Instant cutoff) {
        db.update("""
                DELETE FROM ai_question_delta WHERE question_id IN
                    (SELECT question_id FROM ai_question WHERE completed_at<=?)
                """, at(cutoff));
    }

    public void discardDeltas(UUID question) {
        db.update("DELETE FROM ai_question_delta WHERE question_id=?", id(question));
    }

    public List<Question> previous(Question question) {
        return db.query(QUESTIONS + """
                 WHERE q.conversation_id=? AND q.status='SUCCEEDED' AND q.position_value<=? AND q.wiki_version=?
                 ORDER BY q.created_at DESC,q.question_id DESC LIMIT 10
                """, JdbcAiQuestionStore::question, id(question.conversation()), question.position().value(), question.version());
    }

    public List<Message> messages(UUID conversation, Unit unit, Cursor cursor, int limit) {
        String sql = """
                SELECT * FROM (
                    SELECT question_id AS message_id,question_id,'USER' AS role,question AS body,position_value,
                           created_at AS sent_at,NULL AS citations FROM ai_question WHERE conversation_id=?
                    UNION ALL
                    SELECT job_id,question_id,'ASSISTANT',answer,position_value,completed_at,citations
                    FROM ai_question WHERE conversation_id=? AND status='SUCCEEDED'
                ) messages
                """;
        var params = new java.util.ArrayList<Object>(List.of(id(conversation), id(conversation)));
        if (cursor != null) {
            sql += " WHERE sent_at>? OR (sent_at=? AND message_id>?)";
            params.add(at(cursor.time()));
            params.add(at(cursor.time()));
            params.add(id(cursor.id()));
        }
        params.add(limit);
        return db.query(sql + " ORDER BY sent_at,message_id LIMIT ?", (r, n) -> new Message(
                uuid(r.getBytes("message_id")), uuid(r.getBytes("question_id")), r.getString("role"),
                r.getString("body"), new Position(unit, r.getInt("position_value")),
                r.getTimestamp("sent_at").toInstant(), r.getString("citations")), params.toArray());
    }

    private static Conversation conversation(ResultSet r, int n) throws SQLException {
        return new Conversation(uuid(r.getBytes("conversation_id")), uuid(r.getBytes("book_id")), position(r),
                r.getString("title"), r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant());
    }

    private static Question question(ResultSet r, int n) throws SQLException {
        var completed = r.getTimestamp("completed_at");
        return new Question(uuid(r.getBytes("question_id")), uuid(r.getBytes("job_id")), uuid(r.getBytes("conversation_id")),
                uuid(r.getBytes("member_id")), uuid(r.getBytes("book_id")), position(r), r.getLong("withdrawal_generation"),
                r.getInt("wiki_version"), r.getString("wiki_context"), r.getString("question"), r.getString("answer"),
                r.getString("citations"), r.getString("status"), r.getString("error_code"), r.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant(), r.getTimestamp("deadline").toInstant(),
                uuid(r.getBytes("requested_conversation")), r.getString("requested_unit"),
                r.getObject("requested_value", Integer.class));
    }

    private static Position position(ResultSet r) throws SQLException {
        return new Position(Unit.valueOf(r.getString("position_unit")), r.getInt("position_value"));
    }
}
