package kr.nospo.member.contract;

import java.time.Instant;
import java.util.UUID;

public interface Members {
    record Profile(
            UUID memberId,
            String nickname,
            String phoneNumber,
            Instant createdAt,
            String profileImageUrl) {}

    Profile create(String nickname, String phone, Instant now);

    boolean nicknameAvailable(String key);

    Profile get(UUID memberId);

    boolean active(UUID memberId);
}
