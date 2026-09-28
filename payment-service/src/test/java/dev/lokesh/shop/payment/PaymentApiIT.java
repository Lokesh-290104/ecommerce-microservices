package dev.lokesh.shop.payment;

import com.jayway.jsonpath.JsonPath;
import dev.lokesh.shop.payment.domain.Payment;
import dev.lokesh.shop.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payments end to end against real MySQL: gateway outcome, idempotency, and a real race. */
@Testcontainers(disabledWithoutDocker = true)
class PaymentApiIT extends MySqlTestSupport {

    private static final String INTERNAL = TestTokens.bearer(TestTokens.internal());
    private static final AtomicLong ORDER_IDS = new AtomicLong(ThreadLocalRandom.current().nextLong(1, 1L << 40));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentService payments;

    @Test
    void anApprovedChargeCanBeReadByIdAndByOrder() throws Exception {
        long order = ORDER_IDS.incrementAndGet();
        String json = charge(order, "49.90", "tok_visa")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(order))
                .andExpect(jsonPath("$.amount").value(49.90))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(json, "$.id")).longValue();

        mvc.perform(get("/api/payments/{id}", id).header(HttpHeaders.AUTHORIZATION, INTERNAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order));
        mvc.perform(get("/api/payments").param("orderId", String.valueOf(order)).header(HttpHeaders.AUTHORIZATION, INTERNAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void theDeclineTokenIsDeclinedAndStillRecordedOnce() throws Exception {
        long order = ORDER_IDS.incrementAndGet();
        charge(order, "10.00", "tok_decline").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        // A retry, even with a different token, can't turn a decline into an approval.
        charge(order, "10.00", "tok_visa").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(rows(order)).isEqualTo(1);
    }

    @Test
    void retriesReplayThePaymentButADifferentAmountIsRefused() throws Exception {
        long order = ORDER_IDS.incrementAndGet();
        String first = charge(order, "25.00", "tok_visa").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = charge(order, "25.00", "tok_visa").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first); // byte-for-byte, timestamp included
        charge(order, "30.00", "tok_visa").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertThat(rows(order)).isEqualTo(1);
    }

    @Test
    void unknownPaymentsAre404() throws Exception {
        mvc.perform(get("/api/payments/{id}", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, INTERNAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mvc.perform(get("/api/payments").param("orderId", String.valueOf(ORDER_IDS.incrementAndGet()))
                        .header(HttpHeaders.AUTHORIZATION, INTERNAL))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenConcurrentChargesForOneOrderCreateExactlyOnePayment() throws Exception {
        long order = ORDER_IDS.incrementAndGet();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentService.Result>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return payments.charge(order, new BigDecimal("15.00"), "tok_visa");
            }));
        }
        start.countDown();

        List<Long> ids = new ArrayList<>();
        int created = 0;
        for (Future<PaymentService.Result> f : results) {
            PaymentService.Result r = f.get(60, TimeUnit.SECONDS); // no caller sees an error
            ids.add(r.payment().getId());
            created += r.created() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(rows(order)).isEqualTo(1);
        assertThat(ids).containsOnly(ids.getFirst()); // every caller got the same payment
        assertThat(created).isEqualTo(1);
        assertThat(payments.getByOrder(order).getStatus()).isEqualTo(Payment.Status.APPROVED);
    }

    private ResultActions charge(long order, String amount, String token) throws Exception {
        return mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":" + order + ",\"amount\":" + amount + ",\"paymentToken\":\"" + token + "\"}"));
    }

    private int rows(long order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, order);
    }
}
