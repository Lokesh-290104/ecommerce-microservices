package dev.lokesh.shop.order;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import dev.lokesh.shop.order.domain.InventoryAction;
import dev.lokesh.shop.order.domain.OrderLine;
import dev.lokesh.shop.order.service.CheckoutService;
import dev.lokesh.shop.order.service.OrderStateService;
import dev.lokesh.shop.order.service.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every row of the design's checkout flow table, with WireMock playing the other services. */
@Testcontainers(disabledWithoutDocker = true)
class CheckoutIT extends WireMockSupport {

    static final String BODY = "{\"items\":[{\"productId\":5,\"quantity\":2},{\"productId\":3,\"quantity\":1}],"
            + "\"paymentToken\":\"tok_visa\"}";
    static final String PROBLEM = "application/problem+json";
    /** Unique per test run so tests never share users (and their idempotency keys / orders). */
    private static final AtomicLong USER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CheckoutService checkout;

    @Autowired
    OrderStateService state;

    long userId;

    @BeforeEach
    void aHealthyWorld() {
        userId = USER_IDS.incrementAndGet();
        DOWNSTREAM.stubFor(WireMock.get(urlMatching("/api/users/\\d+")).willReturn(okJson("{}")));
        DOWNSTREAM.stubFor(WireMock.post("/api/inventory/reservations").willReturn(okJson(
                "{\"orderId\":1,\"status\":\"RESERVED\",\"items\":[{\"productId\":3,\"quantity\":1,\"unitPrice\":5.00},"
                        + "{\"productId\":5,\"quantity\":2,\"unitPrice\":12.50}]}")));
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/api/inventory/reservations/\\d+/commit")).willReturn(okJson("{}")));
        DOWNSTREAM.stubFor(WireMock.delete(urlMatching("/api/inventory/reservations/\\d+")).willReturn(okJson("{}")));
        payments(okJson("{\"id\":1,\"orderId\":1,\"amount\":30.00,\"status\":\"APPROVED\"}"));
    }

    @Test
    void happyPathPaysCommitsStockAndReturns201() throws Exception {
        String json = place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(30.00)) // 2 x 12.50 + 1 x 5.00
                .andExpect(jsonPath("$.items[0].productId").value(3)) // sorted by product id
                .andExpect(jsonPath("$.history[*].to").value(org.hamcrest.Matchers.contains("CREATED", "PAYMENT_PENDING", "PAID")))
                .andExpect(jsonPath("$.paymentToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = id(json);

        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/api/payments")).withRequestBody(equalToJson(
                "{\"orderId\":" + id + ",\"amount\":30.00,\"paymentToken\":\"tok_visa\"}")));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/inventory/reservations/" + id + "/commit")));
        assertThat(order(id).inventoryAction()).isEqualTo(InventoryAction.DONE);
    }

    @Test
    void theIdempotencyKeyIsRequired() throws Exception {
        mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, user())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        DOWNSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void anUnknownUserIs422AndNothingIsWritten() throws Exception {
        DOWNSTREAM.stubFor(WireMock.get(urlMatching("/api/users/\\d+")).willReturn(aResponse().withStatus(404)));
        place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertThat(ordersOfUser()).isZero();
    }

    @Test
    void userServiceDownIs503AndNothingIsWritten() throws Exception {
        DOWNSTREAM.stubFor(WireMock.get(urlMatching("/api/users/\\d+")).willReturn(serverError()));
        place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        assertThat(ordersOfUser()).isZero();
    }

    @Test
    void outOfStockCancelsWith409AndNeverCharges() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post("/api/inventory/reservations").willReturn(problem(409, "INSUFFICIENT_STOCK")));
        place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("new Idempotency-Key")));
        DOWNSTREAM.verify(0, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void anUnknownProductCancelsWith422() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post("/api/inventory/reservations").willReturn(problem(422, "UNKNOWN_PRODUCT")));
        place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.cancelReason").value("INVALID_ITEMS"));
    }

    @Test
    void inventoryDownCancelsWith503AndReleasesJustInCase() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post("/api/inventory/reservations").willReturn(serverError()));
        long id = id(place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.cancelReason").value("INVENTORY_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString());

        // The reserve may have happened with only the answer lost, so a release is sent (idempotent).
        DOWNSTREAM.verify(1, deleteRequestedFor(urlEqualTo("/api/inventory/reservations/" + id)));
        assertThat(order(id).inventoryAction()).isEqualTo(InventoryAction.DONE);
    }

    @Test
    void aDeclineIs402AndReturnsTheStock() throws Exception {
        payments(okJson("{\"id\":1,\"orderId\":1,\"amount\":30.00,\"status\":\"DECLINED\"}"));
        long id = id(place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andReturn().getResponse().getContentAsString());

        DOWNSTREAM.verify(1, deleteRequestedFor(urlEqualTo("/api/inventory/reservations/" + id)));
        assertThat(order(id).inventoryAction()).isEqualTo(InventoryAction.DONE);
    }

    @Test
    void paymentsDownLeavesTheOrderPendingWith202AndStockHeld() throws Exception {
        payments(serverError());
        long id = id(place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("update on its own")))
                .andReturn().getResponse().getContentAsString());

        DOWNSTREAM.verify(3, postRequestedFor(urlEqualTo("/api/payments"))); // 1 + 2 retries
        DOWNSTREAM.verify(0, deleteRequestedFor(urlPathMatching("/api/inventory/reservations/.*")));
        assertThat(order(id).inventoryAction()).isEqualTo(InventoryAction.NONE); // stock stays reserved
    }

    @Test
    void aChargeThatTimesOutIsLeftPendingNotFailed() throws Exception {
        // Ambiguous (design D16): payments may have charged; only the reconciler's lookup can tell.
        payments(okJson("{\"id\":1,\"orderId\":1,\"amount\":30.00,\"status\":\"APPROVED\"}").withFixedDelay(2_500));
        place(UUID.randomUUID().toString(), BODY)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
    }

    @Test
    void paidButCommitUnreachableStaysCommitPendingForTheReconciler() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/api/inventory/reservations/\\d+/commit")).willReturn(serverError()));
        long id = id(place(UUID.randomUUID().toString(), BODY).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(order(id).inventoryAction()).isEqualTo(InventoryAction.COMMIT_PENDING);
    }

    @Test
    void aCommitRejectedWith4xxIsMarkedFailedNotRetriedForever() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/api/inventory/reservations/\\d+/commit"))
                .willReturn(problem(409, "RESERVATION_NOT_ACTIVE")));
        long id = id(place(UUID.randomUUID().toString(), BODY).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(order(id).inventoryAction()).isEqualTo(InventoryAction.FAILED);
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheOrderWithoutCallingAnything() throws Exception {
        String key = UUID.randomUUID().toString();
        long id = id(place(key, BODY).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        int callsSoFar = DOWNSTREAM.getAllServeEvents().size();

        place(key, BODY).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));

        assertThat(DOWNSTREAM.getAllServeEvents()).hasSize(callsSoFar); // no second reserve or charge
        assertThat(ordersOfUser()).isEqualTo(1);
    }

    @Test
    void theSameKeyForADifferentOrderIsRefused() throws Exception {
        String key = UUID.randomUUID().toString();
        place(key, BODY).andExpect(status().isCreated());
        place(key, BODY.replace("\"quantity\":2", "\"quantity\":3"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void aDeclinedOrdersReplayKeeps402() throws Exception {
        payments(okJson("{\"id\":1,\"orderId\":1,\"amount\":30.00,\"status\":\"DECLINED\"}"));
        String key = UUID.randomUUID().toString();
        place(key, BODY).andExpect(status().isPaymentRequired());
        place(key, BODY).andExpect(status().isPaymentRequired());
    }

    @Test
    void fiveConcurrentSubmitsOfOneCheckoutCreateOneOrderAndOneCharge() throws Exception {
        String key = UUID.randomUUID().toString();
        List<CheckoutService.Item> items = List.of(new CheckoutService.Item(5, 2), new CheckoutService.Item(3, 1));
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CheckoutService.Result>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return checkout.checkout(userId, key, items, "tok_visa");
            }));
        }
        start.countDown();
        List<Long> ids = new ArrayList<>();
        for (Future<CheckoutService.Result> f : results) {
            ids.add(f.get(60, TimeUnit.SECONDS).order().id());
        }
        pool.shutdown();

        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(ordersOfUser()).isEqualTo(1);
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/inventory/reservations")));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void aReplayWhileTheFirstRequestIsStillRunningGets202() throws Exception {
        // As if the first request died mid-flight: an order exists but no answer was recorded.
        String key = UUID.randomUUID().toString();
        state.create(userId, key, CheckoutService.requestHash(
                List.of(new CheckoutService.Item(5, 2), new CheckoutService.Item(3, 1)), "tok_visa"), "tok_visa",
                List.of(new OrderLine(3L, 1), new OrderLine(5L, 2)));

        place(key, BODY).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CREATED"));
        DOWNSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void usersSeeOnlyTheirOwnOrdersNewestFirst() throws Exception {
        long first = id(place(UUID.randomUUID().toString(), BODY).andReturn().getResponse().getContentAsString());
        long second = id(place(UUID.randomUUID().toString(), BODY).andReturn().getResponse().getContentAsString());

        mvc.perform(get("/api/orders/{id}", first).header(HttpHeaders.AUTHORIZATION, user()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));
        mvc.perform(get("/api/orders/{id}", first).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(userId + 10_000))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/orders/{id}", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, user()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/orders/{id}", first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/{id}", first).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.internal())))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first));
    }

    @Test
    void invalidCheckoutsAreRejectedBeforeAnyCall() throws Exception {
        place(UUID.randomUUID().toString(), "{\"items\":[],\"paymentToken\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        place(UUID.randomUUID().toString(), "{\"items\":[{\"productId\":5,\"quantity\":1},{\"productId\":5,\"quantity\":2}],"
                + "\"paymentToken\":\"tok\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ITEMS"));
        DOWNSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- helpers ---

    private ResultActions place(String key, String body) throws Exception {
        return mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, user()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String user() {
        return TestTokens.bearer(TestTokens.user(userId));
    }

    private void payments(ResponseDefinitionBuilder response) {
        DOWNSTREAM.stubFor(WireMock.post("/api/payments").willReturn(response));
    }

    private static ResponseDefinitionBuilder problem(int status, String code) {
        return aResponse().withStatus(status).withHeader("Content-Type", PROBLEM)
                .withBody("{\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }

    private OrderView order(long id) {
        return state.find(id).orElseThrow();
    }

    private int ordersOfUser() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, userId);
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
