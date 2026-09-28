package dev.lokesh.shop.payment;

import dev.lokesh.shop.payment.api.PaymentController;
import dev.lokesh.shop.payment.domain.Payment;
import dev.lokesh.shop.payment.security.JwtKeys;
import dev.lokesh.shop.payment.security.ProblemAuthHandlers;
import dev.lokesh.shop.payment.security.SecurityConfig;
import dev.lokesh.shop.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static dev.lokesh.shop.payment.PaymentControllerTests.CHARGE;
import static dev.lokesh.shop.payment.PaymentControllerTests.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo switch (design D16): with PAYMENT_RESPONSE_DELAY_MS set, every charge response is
 * held back after the charge itself has happened, and reads are never delayed.
 */
@WebMvcTest(controllers = PaymentController.class, properties = "shop.payments.response-delay-ms=400")
@Import({SecurityConfig.class, JwtKeys.class, ProblemAuthHandlers.class})
class PaymentDelayTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PaymentService paymentService;

    @Test
    void chargeResponsesAreDelayedAfterTheChargeButReadsAreNot() throws Exception {
        Payment payment = PaymentControllerTests.saved(new Payment(42L, new BigDecimal("19.99"), Payment.Status.APPROVED));
        when(paymentService.charge(anyLong(), any(), any())).thenReturn(new PaymentService.Result(payment, true));
        when(paymentService.getByOrder(42)).thenReturn(payment);

        long start = System.nanoTime();
        mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON).content(CHARGE))
                .andExpect(status().isCreated());
        long chargeMs = (System.nanoTime() - start) / 1_000_000;
        verify(paymentService).charge(anyLong(), any(), any()); // the charge happened; only the answer waited

        start = System.nanoTime();
        mvc.perform(get("/api/payments").param("orderId", "42").header(HttpHeaders.AUTHORIZATION, INTERNAL))
                .andExpect(status().isOk());
        long readMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(chargeMs).isGreaterThanOrEqualTo(400);
        assertThat(readMs).isLessThan(400);
    }

    @Test
    void theDelayIsCappedSoAMisconfigurationCantHangThreadsForever() {
        PaymentController controller = new PaymentController(mock(PaymentService.class), 3_600_000);
        assertThat(controller).extracting("responseDelayMs").isEqualTo(30_000L);
    }
}
