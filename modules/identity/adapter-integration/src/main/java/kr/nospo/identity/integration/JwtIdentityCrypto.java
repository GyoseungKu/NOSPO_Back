package kr.nospo.identity.integration;

import kr.nospo.identity.application.IdentityCrypto;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class JwtIdentityCrypto implements IdentityCrypto {
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final JwtEncoder encoder;

    public JwtIdentityCrypto(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    public String hashPassword(String password) {
        return passwords.encode(password);
    }

    public String accessToken(UUID member, UUID session, Instant issuedAt) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer("nospo")
                        .subject(member.toString())
                        .issuedAt(issuedAt)
                        .expiresAt(issuedAt.plusSeconds(900))
                        .claim("sid", session.toString())
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
