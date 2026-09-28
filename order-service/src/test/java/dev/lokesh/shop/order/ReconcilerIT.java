package dev.lokesh.shop.order;

import com.github.tomakehurst.wiremock.client.WireMock;
import dev.lokesh.shop.order.domain.CancelReason;
import dev.lokesh.shop.order.domain.InventoryAction;
import dev.lokesh.shop.order.domain.OrderLine;
import dev.lokesh.shop.order.domain.OrderStatus;
import dev.lokesh.shop.order.service.CheckoutService;
import dev.lokesh.shop.order.service.OrderStateService;
import dev.lokesh.shop.order.service.OrderView;
import dev.lokesh.shop.order.service.Reconciler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Every row of the design's reconciler table, driven tick by tick with a moved clock. */
@Testcontainers(disabledWithoutDocker = true)
class ReconcilerIT extends WireMockSupport {

    private static final AtomicLong USER_IDS = new AtomicLong(2_000_000_000L + System.nanoTime() % 1_000_000_000L);
    private static final String PAYMENT = "{\"id\":1,\"orderId\":1,\"amount\":25.00,\"status\":\"%s\"}";

    @Autowired
    CheckoutService checkout;

    @Autowired
    OrderStateService state;

    @Autowired
    Reconciler reconciler;

    @BeforeEach
    void worldWithPaymentsDown() {
        DOWNSTREAM.stubFor(WireMock.get(urlMatching("/api/users/\\d+")).willReturn(okJson("{}")));
        DOWNSTREAM.stubFor(WireMock.post("/api/inventory/reservations").willReturn(okJson(
                "{\"orderId\":1,\"status\":\"RESERVED\",\"items\":[{\"productId\":5,\"quantity\":2,\"unitPrice\":12.50}]}")));
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/api/inventory/reservations/\\d+/commit")).willReturn(okJson("{}")));
        DOWNSTREAM.stubFor(WireMock.delete(urlMatching("/api/inventory/reservations/\\d+")).willReturn(okJson("{}")));
        DOWNSTREAM.stubFor(WireMock.post("/api/payments").willReturn(serverError()));
        // Leftover pending orders from other tests get a low-priority answer, so their failures
        // can't open the payment breaker and hide this test's order (the breaker works as designed).
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/api/payments")).atPriority(10)
                .willReturn(okJson(PAYMENT.formatted("DECLINED"))));
    }

    @Test
    void paymentsBackAndTheChargeHadGoneThroughMeansPaidWithoutChargingAgain() {
        long id = pendingOrder();
        DOWNSTREAM.resetRequests();
        paymentLookup(id, okJson(PAYMENT.formatted("APPROVED")));

        tick();

        OrderView order = state.find(id).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.inventoryAction()).isEqualTo(InventoryAction.DONE);
        DOWNSTREAM.verify(0, postRequestedFor(urlEqualTo("/api/payments"))); // no second charge
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/inventory/reservations/" + id + "/commit")));
    }

    @Test
    void paymentsNeverReceivedTheChargeSoItIsSentAgain() {
        long id = pendingOrder();
        paymentLookup(id, aResponse().withStatus(404));
        DOWNSTREAM.stubFor(WireMock.post("/api/payments").willReturn(okJson(PAYMENT.formatted("APPROVED"))));

        tick();

        assertThat(state.find(id).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void aChargeFoundDeclinedFailsTheOrderAndReturnsTheStock() {
        long id = pendingOrder();
        paymentLookup(id, okJson(PAYMENT.formatted("DECLINED")));

        tick();

        OrderView order = state.find(id).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.inventoryAction()).isEqualTo(InventoryAction.DONE);
        DOWNSTREAM.verify(1, deleteRequestedFor(urlEqualTo("/api/inventory/reservations/" + id)));
    }

    @Test
    void paymentsStillDownLeavesTheOrderPendingWithStockHeld() {
        long id = pendingOrder();
        paymentLookup(id, serverError());

        tick();

        assertThat(state.find(id).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void aFreshPendingOrderIsLeftAloneWhileItsCheckoutMayStillBeCharging() {
        long id = pendingOrder();
        paymentLookup(id, okJson(PAYMENT.formatted("APPROVED")));

        reconciler.runOnce(Instant.now()); // within the 10 s grace period

        assertThat(state.find(id).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void anOrderStuckInCreatedIsCancelledAndItsStockReleased() {
        OrderView stuck = state.create(USER_IDS.incrementAndGet(), UUID.randomUUID().toString(), "a".repeat(64),
                "tok_visa", List.of(new OrderLine(5L, 1)));

        tick();

        OrderView order = state.find(stuck.id()).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.cancelReason()).isEqualTo(CancelReason.TIMED_OUT);
        assertThat(order.inventoryAction()).isEqualTo(InventoryAction.DONE);
    }

    @Test
    void leftoverStockWorkIsRetriedUntilDoneAnd4xxStopsIt() {
        long retried = pendingOrder();
        long rejected = pendingOrder();
        state.markPaid(retried, "test");
        state.markPaid(rejected, "test");
        DOWNSTREAM.stubFor(WireMock.post(urlEqualTo("/api/inventory/reservations/" + rejected + "/commit"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"code\":\"RESERVATION_NOT_ACTIVE\"}")));

        tick();

        assertThat(state.find(retried).orElseThrow().inventoryAction()).isEqualTo(InventoryAction.DONE);
        assertThat(state.find(rejected).orElseThrow().inventoryAction()).isEqualTo(InventoryAction.FAILED);
    }

    private long pendingOrder() {
        CheckoutService.Result result = checkout.checkout(USER_IDS.incrementAndGet(), UUID.randomUUID().toString(),
                List.of(new CheckoutService.Item(5, 2)), "tok_visa");
        assertThat(result.httpStatus()).isEqualTo(202);
        return result.order().id();
    }

    private void paymentLookup(long orderId, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder answer) {
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/api/payments"))
                .withQueryParam("orderId", equalTo(String.valueOf(orderId))).willReturn(answer));
    }

    /** One reconciler pass, a minute in the future (past both the 10 s and 60 s thresholds). */
    private void tick() {
        breakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
        reconciler.runOnce(Instant.now().plusSeconds(120));
    }
}
