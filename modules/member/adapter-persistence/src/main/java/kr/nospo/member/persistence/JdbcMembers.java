package kr.nospo.member.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.member.application.MemberStore;
import kr.nospo.member.contract.Members.Profile;
import kr.nospo.support.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcMembers implements MemberStore {
    private final JdbcTemplate jdbc;

    public JdbcMembers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Profile create(String nickname, String key, String phone, Instant now) {
        UUID member = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " member(member_id,nickname,nickname_key,phone_number,status,role,created_at,updated_at)"
                        + " VALUES (?,?,?,?,'ACTIVE','USER',?,?)",
                    id(member),
                    nickname,
                    key,
                    phone,
                    at(now),
                    at(now));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "NICKNAME_ALREADY_EXISTS");
        }
        return new Profile(member, nickname, phone, now, null);
    }

    public boolean nicknameAvailable(String key) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM member WHERE nickname_key=?", Integer.class, key)
                == 0;
    }

    public Profile get(UUID member) {
        return jdbc
                .query(
                        "SELECT * FROM member WHERE member_id=? AND status='ACTIVE'",
                        (rs, n) ->
                                new Profile(
                                        uuid(rs.getBytes("member_id")),
                                        rs.getString("nickname"),
                                        rs.getString("phone_number"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getString("profile_image_key")),
                        id(member))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS"));
    }

    public boolean active(UUID member) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM member WHERE member_id=? AND status='ACTIVE'",
                        Integer.class,
                        id(member))
                == 1;
    }

    public void lockActive(UUID member) {
        var states = jdbc.query("SELECT status FROM member WHERE member_id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), id(member));
        if (states.size() != 1 || !"ACTIVE".equals(states.get(0)))
            throw new ApiException(401, "INVALID_CREDENTIALS");
    }
}
