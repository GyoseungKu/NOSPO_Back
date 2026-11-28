package kr.nospo.ai.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import kr.nospo.ai.application.AiGraphStore;
import kr.nospo.ai.application.AiQuestionStore.Cursor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import static kr.nospo.ai.application.AiBookContext.*;
import static kr.nospo.support.SqlIds.*;

@Repository
class JdbcAiGraphStore implements AiGraphStore {
    private static final RowMapper<Graph> GRAPH = (row, index) -> {
        var completed = row.getTimestamp("completed_at");
        return new Graph(uuid(row.getBytes("job_id")), uuid(row.getBytes("graph_id")),
                uuid(row.getBytes("member_id")), uuid(row.getBytes("book_id")),
                uuid(row.getBytes("regenerate_from")), row.getObject("requested_value", Integer.class),
                new Position(Unit.valueOf(row.getString("position_unit")), row.getInt("position_value")),
                row.getInt("wiki_version"), row.getLong("withdrawal_generation"), row.getString("status"),
                row.getString("payload"), row.getString("error_code"), row.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant(), row.getTimestamp("deadline").toInstant());
    };
    private final JdbcTemplate db;

    JdbcAiGraphStore(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Graph> find(UUID value, boolean job) {
        return db.query("SELECT * FROM ai_graph WHERE " + (job ? "job_id" : "graph_id") + "=?",
                GRAPH, id(value)).stream().findFirst();
    }

    public Optional<Graph> request(UUID member, UUID key) {
        return db.query("SELECT * FROM ai_graph WHERE member_id=? AND request_key=?",
                GRAPH, id(member), id(key)).stream().findFirst();
    }

    public void clearKey(UUID graph) {
        db.update("UPDATE ai_graph SET request_key=NULL WHERE graph_id=?", id(graph));
    }

    public void insert(Graph graph, UUID key) {
        db.update("""
                INSERT INTO ai_graph (job_id,graph_id,member_id,book_id,request_key,regenerate_from,requested_value,
                    position_unit,position_value,wiki_version,withdrawal_generation,status,payload,created_at,deadline)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id(graph.job()), id(graph.id()), id(graph.member()), id(graph.book()), id(key), id(graph.source()),
                graph.requestedValue(), graph.position().unit().name(), graph.position().value(), graph.version(),
                graph.generation(), graph.status(), graph.payload(), at(graph.created()), at(graph.deadline()));
    }

    public List<UUID> queued() {
        return db.query("SELECT job_id FROM ai_graph WHERE status='QUEUED' ORDER BY deadline,job_id LIMIT 20",
                (row, index) -> uuid(row.getBytes(1)));
    }

    public void finish(UUID graph, Instant now, String payload, String error) {
        db.update("UPDATE ai_graph SET status=?,completed_at=?,payload=?,error_code=? WHERE graph_id=?",
                error == null ? "SUCCEEDED" : "FAILED", at(now), payload, error, id(graph));
    }

    public List<Graph> list(UUID member, UUID book, Cursor cursor) {
        String sql = "SELECT * FROM ai_graph WHERE member_id=? AND status='SUCCEEDED'";
        var args = new ArrayList<Object>();
        args.add(id(member));
        if (book != null) {
            sql += " AND book_id=?";
            args.add(id(book));
        }
        if (cursor != null) {
            sql += " AND (created_at<? OR (created_at=? AND graph_id<?))";
            args.add(at(cursor.time()));
            args.add(at(cursor.time()));
            args.add(id(cursor.id()));
        }
        return db.query(sql + " ORDER BY created_at DESC,graph_id DESC LIMIT 50", GRAPH, args.toArray());
    }
}
