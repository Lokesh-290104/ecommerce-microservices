package dev.lokesh.shop.order;

import dev.lokesh.shop.order.security.JwtKeys;
import dev.lokesh.shop.order.security.ServiceTokenIssuer;
import dev.lokesh.shop.order.security.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** No Spring context: the issuer and the decoder are plain objects over the same key. */
class ServiceTokenIssuerTests {

    private final JwtKeys keys = new JwtKeys(TestTokens.TEST_SECRET);

    @Test
    void serviceTokensAreShortLivedInternalTokensFromOrderService() {
        Jwt jwt = Tokens.decoder(keys).decode(new ServiceTokenIssuer(keys, Clock.systemUTC()).issue());

        assertThat(Tokens.isInternal(jwt)).isTrue();
        assertThat(jwt.getSubject()).isEqualTo("order-service");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(Tokens.SERVICE_ISSUER);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void userTokensAreNotInternal() {
        Jwt jwt = Tokens.decoder(keys).decode(TestTokens.user(7));
        assertThat(Tokens.isInternal(jwt)).isFalse();
    }
}
