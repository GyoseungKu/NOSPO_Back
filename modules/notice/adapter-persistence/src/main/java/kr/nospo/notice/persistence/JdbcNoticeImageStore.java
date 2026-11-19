package kr.nospo.notice.persistence;

import static kr.nospo.support.SqlIds.at;
import static kr.nospo.support.SqlIds.id;

import java.time.Instant;
import java.util.UUID;
import kr.nospo.notice.application.NoticeImageStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNoticeImageStore implements NoticeImageStore {
    private final JdbcTemplate db;

    public JdbcNoticeImageStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void insert(UUID imageId, byte[] png, Instant createdAt) {
        db.update("INSERT INTO notice_image(image_id,png_data,created_at) VALUES (?,?,?)",
                id(imageId), png, at(createdAt));
    }
}
