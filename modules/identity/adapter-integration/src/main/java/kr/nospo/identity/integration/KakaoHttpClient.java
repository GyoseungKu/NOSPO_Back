package kr.nospo.identity.integration;

import kr.nospo.identity.application.KakaoClient;
import kr.nospo.support.ApiException;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class KakaoHttpClient implements KakaoClient {
    private final String clientId, clientSecret;
    private final URI tokenEndpoint, userEndpoint;
    private final ObjectMapper mapper;

    @Autowired
    public KakaoHttpClient(@Value("${nospo.auth.kakao.client-id:}") String clientId,
            @Value("${nospo.auth.kakao.client-secret:}") String clientSecret, ObjectMapper mapper) {
        this(clientId, clientSecret, mapper, URI.create("https://kauth.kakao.com/oauth/token"),
                URI.create("https://kapi.kakao.com/v2/user/me"));
    }

    // Package-private endpoint injection is for local HTTP contract tests only.
    KakaoHttpClient(String clientId, String clientSecret, ObjectMapper mapper, URI token, URI user) {
        this.clientId = clientId; this.clientSecret = clientSecret; this.mapper = mapper;
        tokenEndpoint = token; userEndpoint = user;
    }

    private void configured() {
        if (clientId.isBlank() || clientSecret.isBlank())
            throw new ApiException(503, "KAKAO_NOT_CONFIGURED");
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public String authorizationUrl(String state, String redirectUri) {
        // Request missing email consent through Kakao's normal authorization flow.
        return authorizationBase(state, redirectUri) + "&scope=account_email";
    }

    private String authorizationBase(String state, String redirectUri) {
        configured();
        return "https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri) + "&state=" + enc(state);
    }

    public String reauthenticationUrl(String state, String redirectUri) {
        // Reauth/link identify the provider subject; they do not require email consent.
        return authorizationBase(state, redirectUri) + "&prompt=login";
    }

    public User identify(String code, String redirectUri) {
        configured();
        try {
            String form = "grant_type=authorization_code&client_id=" + enc(clientId)
                    + "&client_secret=" + enc(clientSecret) + "&redirect_uri=" + enc(redirectUri)
                    + "&code=" + enc(code);
            var token = request(tokenEndpoint, form, null).path("access_token");
            if (!token.isString() || token.asString().isBlank() || token.asString().length() > 8192)
                throw new IllegalArgumentException();
            var user = request(userEndpoint, null, token.asString());
            var id = user.path("id");
            if (!id.isIntegralNumber() || !id.asText().matches("[1-9][0-9]{0,127}"))
                throw new IllegalArgumentException();
            var account = user.path("kakao_account");
            boolean verified = account.path("is_email_valid").isBoolean()
                    && account.path("is_email_valid").asBoolean()
                    && account.path("is_email_verified").isBoolean()
                    && account.path("is_email_verified").asBoolean();
            String email = account.path("email").isString() ? account.path("email").asString() : null;
            var nick = account.path("profile").path("nickname");
            return new User(id.asText(), email, verified, nick.isString() ? nick.asString() : null);
        } catch (Exception ignored) {
            // Do not attach causes: HTTP errors may contain secrets, codes and provider tokens.
            throw new ApiException(400, "KAKAO_AUTH_FAILED");
        }
    }

    private JsonNode request(URI endpoint, String form, String bearer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (form != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                try (var out = connection.getOutputStream()) { out.write(form.getBytes(StandardCharsets.UTF_8)); }
            } else {
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (connection.getResponseCode() != 200) throw new IllegalArgumentException();
            try (var in = connection.getInputStream()) {
                byte[] body = in.readNBytes(65537);
                if (body.length > 65536) throw new IllegalArgumentException();
                return mapper.readTree(body);
            }
        } finally { connection.disconnect(); }
    }
}
