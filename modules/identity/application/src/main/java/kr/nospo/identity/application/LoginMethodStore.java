package kr.nospo.identity.application;

import java.time.Instant;
import java.util.*;

public interface LoginMethodStore {
    record Methods(boolean email, boolean kakao) {}

    Optional<Methods> methods(UUID member);

    Optional<UUID> kakaoMember(String subject);

    void lockActiveMember(UUID member);

    void insertKakao(UUID member, String subject, Instant now);
}
