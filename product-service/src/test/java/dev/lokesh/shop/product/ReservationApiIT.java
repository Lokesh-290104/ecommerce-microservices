package dev.lokesh.shop.product;

import com.jayway.jsonpath.JsonPath;
import dev.lokesh.shop.product.api.ReservationDtos.ReserveItem;
import dev.lokesh.shop.product.error.ApiException;
import dev.lokesh.shop.product.error.ErrorCode;
import dev.lokesh.shop.product.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inventory reservations end to end against real MySQL, including real concurrent races. */
@Testcontainers(disabledWithoutDocker = true)
class ReservationApiIT extends MySqlTestSupport {

    private static final String USER = TestTokens.bearer(TestTokens.user(1));
    private static final String INTERNAL = TestTokens.bearer(TestTokens.internal());
    /** Order ids are unique per test run so tests never share a reservation. */
    private static final AtomicLong ORDER_IDS = new AtomicLong(ThreadLocalRandom.current().nextLong(1, 1L << 40));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReservationService reservations;

    @Test
    void reserveHoldsStockAndReturnsUnitPrices() throws Exception {
        long product = product("19.99", 10);
        long order = ORDER_IDS.incrementAndGet();

        reserve(order, Map.of(product, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items[0].productId").value(product))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unitPrice").value(19.99));

        assertThat(stock(product)).containsEntry("on_hand", 10).containsEntry("reserved", 3);
        mvc.perform(get("/api/products/{id}", product)).andExpect(jsonPath("$.available").value(7));
    }

    @Test
    void reserveIsAllOrNothing() throws Exception {
        long plenty = product("5.00", 5);
        long scarce = product("5.00", 1);
        long order = ORDER_IDS.incrementAndGet();

        reserve(order, Map.of(plenty, 2, scarce, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(stock(plenty)).containsEntry("reserved", 0); // the first line was rolled back too
        assertThat(count("SELECT COUNT(*) FROM reservations WHERE order_id = ?", order)).isZero();
        // Nothing was kept, so the same order id can be used for a request that fits.
        reserve(order, Map.of(plenty, 2)).andExpect(status().isOk());
    }

    @Test
    void unknownProductIs422AndHoldsNothing() throws Exception {
        long product = product("5.00", 5);
        reserve(ORDER_IDS.incrementAndGet(), Map.of(product, 1, Long.MAX_VALUE, 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PRODUCT"));
        assertThat(stock(product)).containsEntry("reserved", 0);
    }

    @Test
    void retriesReplayTheOriginalResultButDifferentItemsAreRefused() throws Exception {
        long product = product("10.00", 10);
        long order = ORDER_IDS.incrementAndGet();
        reserve(order, Map.of(product, 2)).andExpect(status().isOk());

        // The price changes between the attempt and its retry: the retry keeps the original price.
        mvc.perform(put("/api/products/{id}", product).header(HttpHeaders.AUTHORIZATION, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":1,\"name\":\"Repriced\",\"price\":99.00,\"version\":0}"))
                .andExpect(status().isOk());
        reserve(order, Map.of(product, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(10.00));
        assertThat(stock(product)).containsEntry("reserved", 2); // counted once

        reserve(order, Map.of(product, 3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_MISMATCH"));
    }

    @Test
    void commitShipsTheStockAndIsIdempotent() throws Exception {
        long product = product("10.00", 10);
        long order = ORDER_IDS.incrementAndGet();
        reserve(order, Map.of(product, 4)).andExpect(status().isOk());

        commit(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMMITTED"));
        commit(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMMITTED"));

        assertThat(stock(product)).containsEntry("on_hand", 6).containsEntry("reserved", 0);
        release(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_COMMITTED"));
        reserve(order, Map.of(product, 4)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_COMMITTED"));
    }

    @Test
    void releaseGivesStockBackAndIsFinal() throws Exception {
        long product = product("10.00", 10);
        long order = ORDER_IDS.incrementAndGet();
        reserve(order, Map.of(product, 4)).andExpect(status().isOk());

        release(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        release(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));

        assertThat(stock(product)).containsEntry("on_hand", 10).containsEntry("reserved", 0);
        commit(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));
        reserve(order, Map.of(product, 4)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));
    }

    @Test
    void aReleaseBeforeTheReserveLeavesATombstoneThatBlocksTheLateReserve() throws Exception {
        long product = product("10.00", 3);
        long order = ORDER_IDS.incrementAndGet();

        release(order).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.items.length()").value(0));
        reserve(order, Map.of(product, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));

        assertThat(stock(product)).containsEntry("reserved", 0);
    }

    @Test
    void commitWithoutAReservationIs409() throws Exception {
        commit(ORDER_IDS.incrementAndGet()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));
    }

    @Test
    void userTokensCannotTouchInventory() throws Exception {
        mvc.perform(post("/api/inventory/reservations").header(HttpHeaders.AUTHORIZATION, USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":1,\"items\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void twentyBuyersRaceForTheLastFiveUnits() throws Exception {
        long product = product("10.00", 5);

        List<Object> results = race(20, i -> () -> reservations.reserve(ORDER_IDS.incrementAndGet(),
                List.of(new ReserveItem(product, 1))));

        assertThat(results.stream().filter(r -> !(r instanceof Throwable))).hasSize(5);
        assertThat(results.stream().filter(r -> r instanceof ApiException e
                && e.code() == ErrorCode.INSUFFICIENT_STOCK)).hasSize(15);
        assertThat(stock(product)).containsEntry("on_hand", 5).containsEntry("reserved", 5);
    }

    @Test
    void concurrentRetriesOfOneOrderReserveOnce() throws Exception {
        long product = product("10.00", 100);
        long order = ORDER_IDS.incrementAndGet();

        List<Object> results = race(10, i -> () -> reservations.reserve(order, List.of(new ReserveItem(product, 7))));

        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(stock(product)).containsEntry("reserved", 7);
    }

    @Test
    void ordersLockingTheSameProductsInOppositeOrderDoNotDeadlock() throws Exception {
        long a = product("1.00", 1_000);
        long b = product("1.00", 1_000);

        // Each request lists the products in a different order; rows are still locked by id.
        List<Object> results = race(20, i -> () -> reservations.reserve(ORDER_IDS.incrementAndGet(),
                i % 2 == 0 ? List.of(new ReserveItem(a, 1), new ReserveItem(b, 1))
                        : List.of(new ReserveItem(b, 1), new ReserveItem(a, 1))));

        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(stock(a)).containsEntry("reserved", 20);
        assertThat(stock(b)).containsEntry("reserved", 20);
    }

    @Test
    void racingReserveAndReleaseOfTheSameOrderAlwaysEndReleasedWithNoStockHeld() throws Exception {
        long product = product("1.00", 1_000);
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            orders.add(ORDER_IDS.incrementAndGet());
        }

        // For each order, a reserve and a release (the reconciler timing it out) arrive together.
        race(20, i -> () -> i % 2 == 0
                ? reservations.reserve(orders.get(i / 2), List.of(new ReserveItem(product, 3)))
                : reservations.release(orders.get(i / 2)));

        for (long order : orders) {
            assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE order_id = ?", String.class, order))
                    .isEqualTo("RELEASED");
        }
        assertThat(stock(product)).containsEntry("reserved", 0);
    }

    // --- helpers ---

    private long product(String price, int stock) throws Exception {
        String json = mvc.perform(post("/api/products").header(HttpHeaders.AUTHORIZATION, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":1,\"name\":\"Stocked\",\"price\":" + price
                                + ",\"initialStock\":" + stock + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions reserve(long order, Map<Long, Integer> items) throws Exception {
        StringBuilder lines = new StringBuilder();
        items.forEach((product, quantity) -> lines.append(lines.isEmpty() ? "" : ",")
                .append("{\"productId\":").append(product).append(",\"quantity\":").append(quantity).append('}'));
        return mvc.perform(post("/api/inventory/reservations").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":" + order + ",\"items\":[" + lines + "]}"));
    }

    private org.springframework.test.web.servlet.ResultActions commit(long order) throws Exception {
        return mvc.perform(post("/api/inventory/reservations/{id}/commit", order).header(HttpHeaders.AUTHORIZATION, INTERNAL));
    }

    private org.springframework.test.web.servlet.ResultActions release(long order) throws Exception {
        return mvc.perform(delete("/api/inventory/reservations/{id}", order).header(HttpHeaders.AUTHORIZATION, INTERNAL));
    }

    private Map<String, Object> stock(long product) {
        return jdbc.queryForMap("SELECT on_hand, reserved FROM inventory WHERE product_id = ?", product);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** Starts all tasks at the same instant; each result is the return value or the thrown exception. */
    private static List<Object> race(int threads, java.util.function.IntFunction<Callable<Object>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Object> work = task.apply(i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return work.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
