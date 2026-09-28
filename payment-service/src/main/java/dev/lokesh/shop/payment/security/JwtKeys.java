package dev.lokesh.shop.payment.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * The HS256 key shared by every service (design D23). All four services validate tokens
 * with it; user-service also signs with it. Startup fails without a strong enough secret,
 * so a misconfigured service never runs with a guessable key (fail closed).
 */
@Component
public class JwtKeys {

    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;

    public JwtKeys(@Value("${shop.jwt.secret:}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("shop.jwt.secret (env JWT_SECRET) must be at least "
                    + MIN_SECRET_BYTES + " bytes; refusing to start with a missing or weak signing key.");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public SecretKey key() {
        return key;
    }
}
