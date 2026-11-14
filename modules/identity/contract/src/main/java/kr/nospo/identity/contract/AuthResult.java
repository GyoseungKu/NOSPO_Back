package kr.nospo.identity.contract;

import java.time.Instant;
import java.util.*;

public record AuthResult(String accessToken, String tokenType, int expiresIn, Member member) {
    public record Member(
            UUID memberId,
            String nickname,
            String email,
            String phoneNumber,
            boolean phoneVerified,
            String profileImageUrl,
            List<String> loginMethods,
            Instant createdAt) {}
}
