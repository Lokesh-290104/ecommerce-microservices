package dev.lokesh.shop.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payments are internal: only order-service's service token may reach them (design D23). */
@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
class PaymentSecurityTests {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(get("/api/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void userTokensGet403EvenForTheirOwnOrders() throws Exception {
        mvc.perform(get("/api/payments").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void serviceTokensPassTheSecurityLayer() throws Exception {
        int status = mvc.perform(get("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.internal())))
                .andReturn().getResponse().getStatus();
        // No payment endpoints exist until step 6; the point is that security let it through.
        assertThat(status).isNotIn(401, 403);
    }
}
