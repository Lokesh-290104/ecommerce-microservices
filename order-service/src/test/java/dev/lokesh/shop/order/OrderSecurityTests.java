package dev.lokesh.shop.order;

import dev.lokesh.shop.order.security.ServiceTokenIssuer;
import dev.lokesh.shop.order.security.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
class OrderSecurityTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ServiceTokenIssuer serviceTokens;

    @Autowired
    JwtDecoder decoder;

    @Test
    void orderEndpointsNeedAToken() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.expiredUser(1))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokensAreShortLivedInternalTokensFromOrderService() {
        Jwt jwt = decoder.decode(serviceTokens.issue());

        assertThat(Tokens.isInternal(jwt)).isTrue();
        assertThat(jwt.getSubject()).isEqualTo("order-service");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(Tokens.SERVICE_ISSUER);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(60));
    }
}
