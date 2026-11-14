package kr.nospo.identity.application;

public interface KakaoClient {
    record User(String subject, String email, boolean emailVerified, String nickname) {}

    String authorizationUrl(String state, String redirectUri);

    User identify(String code, String redirectUri);
}
