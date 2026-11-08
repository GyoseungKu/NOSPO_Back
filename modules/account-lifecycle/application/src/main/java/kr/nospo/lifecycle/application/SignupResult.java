package kr.nospo.lifecycle.application;

import java.time.Instant;
import java.util.*;

public record SignupResult(String accessToken, String tokenType, int expiresIn, Member member) {
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
