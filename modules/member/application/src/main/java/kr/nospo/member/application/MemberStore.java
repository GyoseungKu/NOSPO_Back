package kr.nospo.member.application;

import kr.nospo.member.contract.Members.Profile;

import java.time.Instant;
import java.util.UUID;

public interface MemberStore {
    Profile create(String nickname, String nicknameKey, String phone, Instant now);

    boolean nicknameAvailable(String key);

    Profile get(UUID memberId);

    boolean active(UUID memberId);
}
