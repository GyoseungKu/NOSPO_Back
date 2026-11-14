package kr.nospo.member.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.member.application.ProfileStore;
import kr.nospo.support.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class JdbcProfileStore implements ProfileStore {
    private final JdbcTemplate db;

    public JdbcProfileStore(JdbcTemplate db) {
        this.db = db;
    }

    public void lockActive(UUID member, UUID session, Instant now) {
        if (db.query(
                        "SELECT member_id FROM member WHERE member_id=? AND status='ACTIVE' FOR"
                            + " UPDATE",
                        (r, n) -> r.getBytes(1),
                        id(member))
                .isEmpty()) throw new ApiException(401, "INVALID_CREDENTIALS");
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM auth_session WHERE member_id=? AND session_id=? AND"
                            + " revoked_at IS NULL AND expires_at>?",
                        Integer.class,
                        id(member),
                        id(session),
                        at(now))
                != 1) throw new ApiException(401, "INVALID_CREDENTIALS");
    }

    public void nickname(UUID member, String name, String key, Instant now) {
        try {
            db.update(
                    "UPDATE member SET nickname=?,nickname_key=?,updated_at=? WHERE member_id=?",
                    name,
                    key,
                    at(now),
                    id(member));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "NICKNAME_ALREADY_EXISTS");
        }
    }

    public void phone(UUID member, String phone, Instant now) {
        db.update(
                "UPDATE member SET phone_number=?,updated_at=? WHERE member_id=?",
                phone,
                at(now),
                id(member));
    }

    public void image(UUID member, byte[] png, Instant now) {
        db.update("DELETE FROM member_profile_image WHERE member_id=?", id(member));
        if (png != null)
            db.update(
                    "INSERT INTO member_profile_image(member_id,png_data,updated_at) VALUES"
                        + " (?,?,?)",
                    id(member),
                    png,
                    at(now));
        db.update(
                "UPDATE member SET profile_image_key=?,updated_at=? WHERE member_id=?",
                png == null ? null : "/api/v1/me/profile-image",
                at(now),
                id(member));
    }

    public Optional<byte[]> image(UUID member) {
        return db
                .query(
                        "SELECT png_data FROM member_profile_image WHERE member_id=?",
                        (r, n) -> r.getBytes(1),
                        id(member))
                .stream()
                .findFirst();
    }
}
