package kr.nospo.member.application;

import kr.nospo.member.contract.Members;
import kr.nospo.member.domain.MemberInput;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class MemberService implements Members {
    private final MemberStore store;

    public MemberService(MemberStore store) {
        this.store = store;
    }

    public Profile create(String nickname, String phone, Instant now) {
        String normalized = MemberInput.nickname(nickname);
        return store.create(
                normalized, MemberInput.nicknameKey(normalized), MemberInput.phone(phone), now);
    }

    public boolean nicknameAvailable(String nickname) {
        return store.nicknameAvailable(MemberInput.nicknameKey(nickname));
    }

    public Profile get(UUID member) {
        return store.get(member);
    }

    public boolean active(UUID member) {
        return store.active(member);
    }
}
