package dev.lokesh.shop.payment;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.lokesh.shop.payment.security.Tokens;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Real, signed tokens for tests, made with the test key from src/test/resources, so tests
 * go through the actual signature and claim checks rather than a mocked authentication.
 */
final class TestTokens {

    static final String TEST_SECRET = "test-only-jwt-secret-0123456789-abcdef";

    private TestTokens() {
    }

    static String user(long userId) {
        return sign(TEST_SECRET, Tokens.USER_ISSUER, Long.toString(userId), Tokens.USER_SCOPE, Duration.ofMinutes(30));
    }

    /** What order-service will send in step 7. */
    static String internal() {
        return sign(TEST_SECRET, Tokens.SERVICE_ISSUER, "order-service", Tokens.INTERNAL_SCOPE, Duration.ofSeconds(60));
    }

    static String expiredUser(long userId) {
        return sign(TEST_SECRET, Tokens.USER_ISSUER, Long.toString(userId), Tokens.USER_SCOPE, Duration.ofMinutes(-5));
    }

    static String signedWith(String secret, long userId) {
        return sign(secret, Tokens.USER_ISSUER, Long.toString(userId), Tokens.USER_SCOPE, Duration.ofMinutes(30));
    }

    static String fromIssuer(String issuer, long userId) {
        return sign(TEST_SECRET, issuer, Long.toString(userId), Tokens.USER_SCOPE, Duration.ofMinutes(30));
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String sign(String secret, String issuer, String subject, String scope, Duration ttl) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        // Backdate issuedAt for expired tokens so exp stays after iat.
        Instant now = Instant.now();
        Instant issued = ttl.isNegative() ? now.plus(ttl).minusSeconds(60) : now;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).subject(subject).issuedAt(issued).expiresAt(now.plus(ttl)).claim("scope", scope)
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
