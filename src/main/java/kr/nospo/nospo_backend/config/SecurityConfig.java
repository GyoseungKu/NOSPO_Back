package kr.nospo.nospo_backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import kr.nospo.identity.application.KakaoLinkAccess;
import kr.nospo.member.contract.MemberAccess;
import kr.nospo.notice.web.AdminNoticeImageController;
import kr.nospo.support.ApiErrors;
import kr.nospo.support.ApiException;
import kr.nospo.terms.contract.MemberConsents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    private static final PathPatternRequestMatcher.Builder PATHS =
            PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher API = PATHS.matcher("/api/**");
    private static final RequestMatcher NOTICE_IMAGE_UPLOAD =
            PATHS.matcher(HttpMethod.POST, AdminNoticeImageController.PATH);
    private static final RequestMatcher[] ADMIN_NOTICES = {
            NOTICE_IMAGE_UPLOAD,
            PATHS.matcher("/api/v1/admin/notices"),
            PATHS.matcher("/api/v1/admin/notices/{noticeId}")
    };
    private static final RequestMatcher ADMIN_AI =
            PATHS.matcher("/api/v1/admin/members/{memberId}/ai/**");
    private static final RequestMatcher[] PUBLIC_REQUESTS = {
            PATHS.matcher(HttpMethod.GET, "/api/v1/ai/credit-policy"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/notices"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/notices/{noticeId}"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/terms"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/auth/kakao/authorize"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/auth/kakao/callback"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/terms/*"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/auth/availability/*"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/recovery/complete"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/recovery/email-verifications"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/recovery/email-verifications/confirm"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/signup"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/kakao/exchange"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/signup/kakao"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/signup/kakao/context"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/signup/email-verifications"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/signup/email-verifications/confirm"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/login"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/token/refresh"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/logout"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/password-resets"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/password-resets/verify"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/password-resets/complete")
    };
    private static final RequestMatcher[] RECONSENT_EXEMPT = {
            PATHS.matcher(HttpMethod.GET, "/api/v1/me"),
            PATHS.matcher(HttpMethod.HEAD, "/api/v1/me"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/me/login-methods"),
            PATHS.matcher(HttpMethod.HEAD, "/api/v1/me/login-methods"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/me/profile-image"),
            PATHS.matcher(HttpMethod.HEAD, "/api/v1/me/profile-image"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/me/consents"),
            PATHS.matcher(HttpMethod.GET, "/api/v1/me/consents/pending"),
            PATHS.matcher(HttpMethod.HEAD, "/api/v1/me/consents"),
            PATHS.matcher(HttpMethod.HEAD, "/api/v1/me/consents/pending"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/me/consents"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/me/reauth/password"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/me/reauth/kakao"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/me/withdrawal")
    };

    @Bean
    KakaoLinkAccess kakaoLinkAccess(MemberConsents consents) {
        return (member, session) -> {
            if (consents.requiresReconsent(member, session))
                throw new ApiException(403, "TERMS_RECONSENT_REQUIRED");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper mapper,
            KakaoLinkAccess consentAccess,
            MemberAccess members,
            @Value("${nospo.auth.allowed-origins}") List<String> allowedOrigins)
            throws Exception {
        if (allowedOrigins.isEmpty() || allowedOrigins.contains("*"))
            throw new IllegalArgumentException("Explicit allowed origins required");
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key", "Last-Event-ID"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        var noticeCors = new CorsConfiguration(cors);
        noticeCors.addAllowedHeader("X-CSRF-TOKEN");
        noticeCors.addExposedHeader("X-CSRF-TOKEN");
        source.registerCorsConfiguration("/api/v1/admin/notices", noticeCors);
        source.registerCorsConfiguration(AdminNoticeImageController.PATH, noticeCors);
        source.registerCorsConfiguration("/api/v1/admin/notices/{noticeId}", noticeCors);
        source.registerCorsConfiguration("/api/**", cors);
        return http.cors(c -> c.configurationSource(source))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
                        String origin = request.getHeader("Origin");
                        if (NOTICE_IMAGE_UPLOAD.matches(request) && origin != null
                                && !allowedOrigins.contains(origin)) {
                            error(response, mapper, 403, "ORIGIN_NOT_ALLOWED");
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, CorsFilter.class)
                .csrf(
                        c ->
                                c.ignoringRequestMatchers(NOTICE_IMAGE_UPLOAD)
                                        .ignoringRequestMatchers(
                                        "/api/v1/ai/questions",
                                        "/api/v1/admin/members/{memberId}/ai/credit-adjustments",
                                        "/api/v1/auth/recovery/complete",
                                        "/api/v1/auth/recovery/email-verifications",
                                        "/api/v1/auth/recovery/email-verifications/confirm",
                                        "/api/v1/auth/kakao/exchange",
                                        "/api/v1/auth/signup/kakao",
                                        "/api/v1/auth/signup/kakao/context",
                                        "/api/v1/me/**",
                                        "/api/v1/auth/signup",
                                        "/api/v1/auth/signup/email-verifications",
                                        "/api/v1/auth/signup/email-verifications/confirm",
                                        "/api/v1/auth/login",
                                        "/api/v1/auth/token/refresh",
                                        "/api/v1/auth/logout",
                                        "/api/v1/auth/password-resets",
                                        "/api/v1/auth/password-resets/verify",
                                        "/api/v1/auth/password-resets/complete"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/v3/api-docs.yaml")
                                        .permitAll()
                                        .requestMatchers(PUBLIC_REQUESTS).permitAll()
                                        .requestMatchers(request -> matches(ADMIN_NOTICES, request) || ADMIN_AI.matches(request))
                                        .access((authentication, context) -> new AuthorizationDecision(
                                                authentication.get() instanceof JwtAuthenticationToken jwt
                                                        && jwt.isAuthenticated() && activeAdmin(members, jwt)))
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        o ->
                                o.jwt(Customizer.withDefaults())
                                        .authenticationEntryPoint(
                                                (r, s, e) ->
                                                        error(
                                                                s,
                                                                mapper,
                                                                401,
                                                                "INVALID_CREDENTIALS")))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (r, s, x) ->
                                                        error(
                                                                s,
                                                                mapper,
                                                                401,
                                                                "INVALID_CREDENTIALS"))
                                        .accessDeniedHandler(
                                                (r, s, x) ->
                                                        error(s, mapper, 403,
                                                                (matches(ADMIN_NOTICES, r) || ADMIN_AI.matches(r))
                                                                        ? "FORBIDDEN" : "ACCESS_DENIED")))
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            protected void doFilterInternal(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
                                    throws ServletException, IOException {
                                if (request.getRequestURI().startsWith("/api/")) {
                                    response.setHeader("Cache-Control", "no-store");
                                    if (!Set.of("GET", "HEAD", "OPTIONS")
                                            .contains(request.getMethod())) {
                                        String origin = request.getHeader("Origin");
                                        if ((origin != null && !allowedOrigins.contains(origin))
                                                || (origin == null
                                                        && (request.getHeader("Cookie") != null
                                                                || "cross-site"
                                                                        .equals(
                                                                                request.getHeader(
                                                                                        "Sec-Fetch-Site"))))) {
                                            error(response, mapper, 403, "ORIGIN_NOT_ALLOWED");
                                            return;
                                        }
                                    }
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new OncePerRequestFilter() {
                            @Override
                            protected void doFilterInternal(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
                                    throws ServletException, IOException {
                                var authentication = SecurityContextHolder.getContext().getAuthentication();
                                if (authentication instanceof JwtAuthenticationToken jwt
                                        && API.matches(request)
                                        && !matches(PUBLIC_REQUESTS, request)
                                        && !matches(RECONSENT_EXEMPT, request)) {
                                    try {
                                        consentAccess.check(
                                                UUID.fromString(jwt.getToken().getSubject()),
                                                UUID.fromString(jwt.getToken().getClaimAsString("sid")));
                                    } catch (ApiException failure) {
                                        error(response, mapper, failure.status(), failure.code());
                                        return;
                                    } catch (RuntimeException failure) {
                                        org.slf4j.LoggerFactory.getLogger(SecurityConfig.class)
                                                .error("Reconsent check failed type={}", failure.getClass().getSimpleName());
                                        error(response, mapper, 500, "INTERNAL_ERROR");
                                        return;
                                    }
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        AuthorizationFilter.class)
                .addFilterAfter(noticeAdminCsrf(mapper), AuthorizationFilter.class)
                .build();
    }

    private static OncePerRequestFilter noticeAdminCsrf(ObjectMapper mapper) {
        // 리소스 서버의 Bearer CSRF 예외와 별도로 관리자 공지 경로를 검사한다.
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setSessionAttributeName("NOSPO_NOTICE_ADMIN_CSRF");
        var csrf = new CsrfFilter(repository);
        csrf.setRequestHandler(new CsrfTokenRequestHandler() {
            private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();

            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response,
                    java.util.function.Supplier<CsrfToken> token) {
                delegate.handle(request, response, token);
            }

            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                // Reading a multipart form parameter can parse the upload before CSRF validation.
                if (NOTICE_IMAGE_UPLOAD.matches(request) && request.getHeader(token.getHeaderName()) == null)
                    return null;
                return delegate.resolveCsrfTokenValue(request, token);
            }
        });
        csrf.setBeanName("noticeAdminCsrf");
        csrf.setAccessDeniedHandler((request, response, failure) -> error(response, mapper, 403, "FORBIDDEN"));
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !matches(ADMIN_NOTICES, request);
            }

            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                csrf.doFilter(request, response, chain);
            }
        };
    }

    private static boolean activeAdmin(MemberAccess members, JwtAuthenticationToken jwt) {
        return members.state(UUID.fromString(jwt.getToken().getSubject()))
                .filter(MemberAccess.State::administrator).isPresent();
    }

    private static boolean matches(RequestMatcher[] matchers, HttpServletRequest request) {
        return Arrays.stream(matchers).anyMatch(matcher -> matcher.matches(request));
    }

    private static void error(
            HttpServletResponse response, ObjectMapper mapper, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(ApiErrors.body(code)));
    }
}
