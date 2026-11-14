package kr.nospo.identity.application;

import kr.nospo.support.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
public class KakaoOAuthSettings {
    private final String redirectUri;
    private final String frontendCallback;
    private final List<String> returnPaths;

    public KakaoOAuthSettings(
            @Value("${nospo.auth.kakao.redirect-uri}") String redirectUri,
            @Value("${nospo.auth.kakao.frontend-callback}") String frontendCallback,
            @Value("${nospo.auth.kakao.return-paths}") List<String> returnPaths,
            @Value("${nospo.auth.secure-cookie:true}") boolean secureCookie) {
        validateUri(redirectUri, secureCookie);
        validateUri(frontendCallback, secureCookie);
        if (!URI.create(redirectUri).getPath().equals("/api/v1/auth/kakao/callback"))
            throw new IllegalArgumentException("Invalid Kakao callback path");
        if (returnPaths.isEmpty() || returnPaths.stream().anyMatch(p -> !safePath(p)))
            throw new IllegalArgumentException("Explicit relative Kakao return paths required");
        this.redirectUri = redirectUri;
        this.frontendCallback = frontendCallback;
        this.returnPaths = List.copyOf(returnPaths);
    }

    private static void validateUri(String value, boolean secure) {
        URI uri = URI.create(value);
        boolean local = List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !uri.normalize().equals(uri)
                || !("https".equals(uri.getScheme())
                    || (!secure && local && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException("Kakao redirects require HTTPS (local HTTP only with insecure cookies)");
    }

    private static boolean safePath(String path) {
        return path.matches("/[a-zA-Z0-9/_-]*") && !path.startsWith("//");
    }

    public String returnPath(String path) {
        String result = path == null ? returnPaths.getFirst() : path;
        if (!safePath(result) || !returnPaths.contains(result))
            throw new ApiException(422, "VALIDATION_ERROR");
        return result;
    }

    public String redirectUri() { return redirectUri; }
    public String frontendCallback() { return frontendCallback; }
}
