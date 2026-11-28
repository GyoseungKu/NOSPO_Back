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
        URI callback = validateUri("KAKAO_REDIRECT_URI", redirectUri, secureCookie);
        validateUri("KAKAO_FRONTEND_CALLBACK", frontendCallback, secureCookie);
        if (!"/api/v1/auth/kakao/callback".equals(callback.getPath()))
            throw new IllegalArgumentException("KAKAO_REDIRECT_URI must end with /api/v1/auth/kakao/callback");
        if (returnPaths.isEmpty() || returnPaths.stream().anyMatch(p -> !safePath(p)))
            throw new IllegalArgumentException("KAKAO_RETURN_PATHS must list allowed relative paths");
        this.redirectUri = redirectUri;
        this.frontendCallback = frontendCallback;
        this.returnPaths = List.copyOf(returnPaths);
    }

    private static URI validateUri(String setting, String value, boolean secure) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(setting + " must be configured");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(setting + " must be a valid callback URL");
        }
        String host = uri.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !uri.normalize().equals(uri)
                || !("https".equals(uri.getScheme())
                    || (!secure && local && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException(setting + " requires an absolute HTTPS URL (loopback HTTP only with insecure cookies)");
        return uri;
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
