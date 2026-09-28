package dev.lokesh.shop.user.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Signs the 30-minute user tokens returned by login (sub = user id, scope = user). */
@Component
public class TokenIssuer {

    public static final Duration USER_TOKEN_TTL = Duration.ofMinutes(30);

    private final JwtEncoder encoder;
    private final Clock clock;

    public TokenIssuer(JwtKeys keys, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(keys.key()));
        this.clock = clock;
    }

    public String issueUserToken(long userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(Tokens.USER_ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plus(USER_TOKEN_TTL))
                .claim("scope", Tokens.USER_SCOPE)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
