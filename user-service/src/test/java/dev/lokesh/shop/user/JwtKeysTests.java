package dev.lokesh.shop.user;

import dev.lokesh.shop.user.security.JwtKeys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtKeysTests {

    @Test
    void aMissingOrShortSecretStopsTheServiceFromStarting() {
        assertThrows(IllegalStateException.class, () -> new JwtKeys(""));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JwtKeys("x".repeat(31)));
        assertThat(e.getMessage()).contains("JWT_SECRET").doesNotContain("xxxx"); // never echo the secret
    }

    @Test
    void a32ByteSecretIsAccepted() {
        assertThat(new JwtKeys("x".repeat(32)).key().getAlgorithm()).isEqualTo("HmacSHA256");
    }
}
