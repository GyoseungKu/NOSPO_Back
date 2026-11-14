package kr.nospo.identity.persistence;

import static kr.nospo.support.SqlIds.*;

import kr.nospo.identity.application.LoginMethodStore;
import kr.nospo.support.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class JdbcLoginMethodStore implements LoginMethodStore {
    private final JdbcTemplate db;

    public JdbcLoginMethodStore(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Methods> methods(UUID member) {
        return db.query(
                "SELECT EXISTS (SELECT 1 FROM password_credential p WHERE p.member_id=a.member_id),"
                        + " EXISTS (SELECT 1 FROM social_identity s WHERE s.member_id=a.member_id"
                        + " AND s.provider='KAKAO') FROM account a JOIN member m ON"
                        + " m.member_id=a.member_id WHERE a.member_id=? AND m.status='ACTIVE'",
                (r, n) -> new Methods(r.getBoolean(1), r.getBoolean(2)), id(member))
                .stream().findFirst();
    }

    public Optional<UUID> kakaoMember(String subject) {
        return db.query(
                "SELECT member_id FROM social_identity WHERE provider='KAKAO' AND provider_subject=?",
                (r, n) -> uuid(r.getBytes(1)), subject)
                .stream().findFirst();
    }

    public void lockActiveMember(UUID member) {
        if (db.query(
                "SELECT m.member_id FROM member m JOIN account a ON a.member_id=m.member_id"
                        + " WHERE m.member_id=? AND m.status='ACTIVE' FOR UPDATE",
                (r, n) -> r.getBytes(1), id(member)).isEmpty())
            throw new ApiException(401, "INVALID_CREDENTIALS");
    }

    public void insertKakao(UUID member, String subject, Instant now) {
        try {
            db.update("INSERT INTO social_identity"
                            + " (social_identity_id,member_id,provider,provider_subject,linked_at)"
                            + " VALUES (?,?,'KAKAO',?,?)",
                    id(UUID.randomUUID()), id(member), subject, at(now));
        } catch (DuplicateKeyException ex) {
            // Both competing owners and a second Kakao identity for one member are conflicts.
            throw new ApiException(409, "SOCIAL_ACCOUNT_ALREADY_LINKED");
        }
    }
}
