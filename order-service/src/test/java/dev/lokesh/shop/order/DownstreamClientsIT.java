package dev.lokesh.shop.order;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.lokesh.shop.order.client.DownstreamRejectedException;
import dev.lokesh.shop.order.client.DownstreamUnavailableException;
import dev.lokesh.shop.order.client.InventoryClient;
import dev.lokesh.shop.order.client.PaymentClient;
import dev.lokesh.shop.order.client.UserClient;
import dev.lokesh.shop.order.security.JwtKeys;
import dev.lokesh.shop.order.security.Tokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The downstream clients against WireMock: timeouts, retries, breaker, 4xx vs unavailable, tokens. */
@Testcontainers(disabledWithoutDocker = true)
class DownstreamClientsIT extends WireMockSupport {

    static final String PAYMENT_JSON = "{\"id\":1,\"orderId\":9,\"amount\":20.00,\"status\":\"%s\"}";
    static final String PROBLEM = "application/problem+json";

    @Autowired
    UserClient users;

    @Autowired
    InventoryClient inventory;

    @Autowired
    PaymentClient payments;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void callsCarryAFreshInternalServiceTokenNeverTheUsersToken() {
        DOWNSTREAM.stubFor(get("/api/users/7").willReturn(okJson("{\"id\":7}")));

        assertThat(users.isActiveUser(7)).isTrue();

        String header = DOWNSTREAM.findAll(getRequestedFor(urlEqualTo("/api/users/7"))).getFirst().getHeader("Authorization");
        Jwt jwt = Tokens.decoder(new JwtKeys(TestTokens.TEST_SECRET)).decode(header.substring("Bearer ".length()));
        assertThat(Tokens.isInternal(jwt)).isTrue();
        assertThat(jwt.getSubject()).isEqualTo("order-service");
    }

    @Test
    void aMissingOrDeletedUserIsAnAnswerNotAnOutage() {
        DOWNSTREAM.stubFor(get("/api/users/8").willReturn(aResponse().withStatus(404)));
        assertThat(users.isActiveUser(8)).isFalse();
        DOWNSTREAM.verify(1, getRequestedFor(urlEqualTo("/api/users/8"))); // 4xx is never retried
    }

    @Test
    void reserveReturnsPricesAndA409KeepsItsProblemCode() {
        DOWNSTREAM.stubFor(post("/api/inventory/reservations").withRequestBody(equalTo(
                        "{\"orderId\":1,\"items\":[{\"productId\":5,\"quantity\":2}]}"))
                .willReturn(okJson("{\"orderId\":1,\"status\":\"RESERVED\",\"items\":[{\"productId\":5,\"quantity\":2,\"unitPrice\":9.50}]}")));
        assertThat(inventory.reserve(1, List.of(new InventoryClient.Item(5, 2)))).containsEntry(5L, new BigDecimal("9.50"));

        DOWNSTREAM.stubFor(post("/api/inventory/reservations").withRequestBody(equalTo(
                        "{\"orderId\":2,\"items\":[{\"productId\":5,\"quantity\":99}]}"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", PROBLEM)
                        .withBody("{\"status\":409,\"detail\":\"Not enough stock\",\"code\":\"INSUFFICIENT_STOCK\"}")));
        DownstreamRejectedException e = assertThrows(DownstreamRejectedException.class,
                () -> inventory.reserve(2, List.of(new InventoryClient.Item(5, 99))));
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void chargeRetriesTwiceThroughAnOutageBlip() {
        DOWNSTREAM.stubFor(post("/api/payments").inScenario("blip").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("second"));
        DOWNSTREAM.stubFor(post("/api/payments").inScenario("blip").whenScenarioStateIs("second")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("third"));
        DOWNSTREAM.stubFor(post("/api/payments").inScenario("blip").whenScenarioStateIs("third")
                .willReturn(okJson(PAYMENT_JSON.formatted("APPROVED"))));

        assertThat(payments.charge(9, new BigDecimal("20.00"), "tok_visa")).isEqualTo(PaymentClient.Outcome.APPROVED);
        DOWNSTREAM.verify(3, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void aChargeThatTimesOutEveryTimeIsUnavailableAfterThreeAttempts() {
        // Like payments with PAYMENT_RESPONSE_DELAY_MS: the answer comes after our 2 s read timeout.
        DOWNSTREAM.stubFor(post("/api/payments").willReturn(okJson(PAYMENT_JSON.formatted("APPROVED")).withFixedDelay(2_500)));

        DownstreamUnavailableException e = assertThrows(DownstreamUnavailableException.class,
                () -> payments.charge(9, new BigDecimal("20.00"), "tok_visa"));
        assertThat(e.service()).isEqualTo("payment-service");
        DOWNSTREAM.verify(3, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void aPaymentRejectionIsNotRetried() {
        DOWNSTREAM.stubFor(post("/api/payments").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", PROBLEM).withBody("{\"status\":409,\"code\":\"PAYMENT_MISMATCH\"}")));

        assertThat(assertThrows(DownstreamRejectedException.class,
                () -> payments.charge(9, new BigDecimal("20.00"), "tok_visa")).code()).isEqualTo("PAYMENT_MISMATCH");
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void lookingUpACharge() {
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/payments")).withQueryParam("orderId", equalTo("9"))
                .willReturn(okJson(PAYMENT_JSON.formatted("DECLINED"))));
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/payments")).withQueryParam("orderId", equalTo("10"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(payments.find(9)).contains(PaymentClient.Outcome.DECLINED);
        assertThat(payments.find(10)).isEmpty(); // payments never received that charge
    }

    @Test
    void theBreakerOpensAfterRepeatedFailuresAndStopsCallingTheService() {
        DOWNSTREAM.stubFor(delete(urlPathEqualTo("/api/inventory/reservations/1")).willReturn(serverError()));
        for (int i = 0; i < 5; i++) {
            assertThrows(DownstreamUnavailableException.class, () -> inventory.release(1));
        }

        DownstreamUnavailableException e = assertThrows(DownstreamUnavailableException.class, () -> inventory.release(1));
        assertThat(e.getCause()).isInstanceOf(CallNotPermittedException.class);
        DOWNSTREAM.verify(5, anyRequestedFor(anyUrl())); // the 6th call never left order-service
    }

    @Test
    void noDownstreamCallMayRunInsideADatabaseTransaction() {
        DOWNSTREAM.stubFor(get("/api/users/7").willReturn(okJson("{\"id\":7}")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> transactions.executeWithoutResult(tx -> users.isActiveUser(7)));
        assertThat(e.getMessage()).contains("inside a database transaction");
        DOWNSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }
}
