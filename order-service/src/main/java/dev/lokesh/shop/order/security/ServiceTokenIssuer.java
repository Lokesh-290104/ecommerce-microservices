package dev.lokesh.shop.order.security;

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

/**
 * Mints the short-lived tokens order-service sends to product-service and payment-service
 * (design D23): scope "internal", 60 s. A user's token is never forwarded downstream, so a
 * user can't call the internal endpoints directly, and a leaked service token expires fast.
 */
@Component
public class ServiceTokenIssuer {

    public static final Duration SERVICE_TOKEN_TTL = Duration.ofSeconds(60);
    static final String SUBJECT = "order-service";

    private final JwtEncoder encoder;
    private final Clock clock;

    public ServiceTokenIssuer(JwtKeys keys, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(keys.key()));
        this.clock = clock;
    }

    public String issue() {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(Tokens.SERVICE_ISSUER)
                .subject(SUBJECT)
                .issuedAt(now)
                .expiresAt(now.plus(SERVICE_TOKEN_TTL))
                .claim("scope", Tokens.INTERNAL_SCOPE)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
