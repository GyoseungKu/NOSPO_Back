package kr.nospo.nospo_backend.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import kr.nospo.identity.contract.SignupIdentity;
import kr.nospo.support.Secrets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AuthConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${nospo.auth.jwt-key}") String key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(Secrets.key(key, "HmacSHA256")));
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${nospo.auth.jwt-key}") String key, SignupIdentity identity, Clock clock) {
        var decoder =
                NimbusJwtDecoder.withSecretKey(Secrets.key(key, "HmacSHA256"))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        OAuth2TokenValidator<Jwt> session =
                jwt -> {
                    try {
                        if (identity.sessionActive(
                                UUID.fromString(jwt.getSubject()),
                                UUID.fromString(jwt.getClaimAsString("sid")),
                                clock.instant())) return OAuth2TokenValidatorResult.success();
                    } catch (IllegalArgumentException ignored) {
                    }
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer("nospo"), session));
        return decoder;
    }
}
