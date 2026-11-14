package kr.nospo.nospo_backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import kr.nospo.support.ApiErrors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper mapper,
            @Value("${nospo.auth.allowed-origins}") List<String> allowedOrigins)
            throws Exception {
        if (allowedOrigins.isEmpty() || allowedOrigins.contains("*"))
            throw new IllegalArgumentException("Explicit allowed origins required");
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return http.cors(c -> c.configurationSource(source))
                .csrf(
                        c ->
                                c.ignoringRequestMatchers(
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
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/v1/terms",
                                                "/api/v1/auth/kakao/authorize",
                                                "/api/v1/auth/kakao/callback",
                                                "/api/v1/terms/*",
                                                "/api/v1/auth/availability/*")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/v1/auth/signup",
                                                "/api/v1/auth/kakao/exchange",
                                                "/api/v1/auth/signup/kakao",
                                                "/api/v1/auth/signup/kakao/context",
                                                "/api/v1/auth/signup/email-verifications",
                                                "/api/v1/auth/signup/email-verifications/confirm",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/token/refresh",
                                                "/api/v1/auth/logout",
                                                "/api/v1/auth/password-resets",
                                                "/api/v1/auth/password-resets/verify",
                                                "/api/v1/auth/password-resets/complete")
                                        .permitAll()
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
                                                        error(s, mapper, 403, "ACCESS_DENIED")))
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
                .build();
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
