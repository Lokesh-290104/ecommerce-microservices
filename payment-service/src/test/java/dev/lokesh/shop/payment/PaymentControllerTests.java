package dev.lokesh.shop.payment;

import dev.lokesh.shop.payment.api.PaymentController;
import dev.lokesh.shop.payment.domain.Payment;
import dev.lokesh.shop.payment.error.ApiException;
import dev.lokesh.shop.payment.error.ErrorCode;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only (no database): security rules, validation and the status contract. */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtKeys.class, ProblemAuthHandlers.class})
class PaymentControllerTests {

    static final String INTERNAL = TestTokens.bearer(TestTokens.internal());
    static final String CHARGE = "{\"orderId\":42,\"amount\":19.99,\"paymentToken\":\"tok_visa\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PaymentService paymentService;

    @Test
    void onlyServiceTokensMayChargeOrRead() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(CHARGE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // Even a signed-in user can't charge or look up payments, not even for their own order.
        mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(1)))
                        .contentType(MediaType.APPLICATION_JSON).content(CHARGE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/payments").param("orderId", "42")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(1))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(paymentService);
    }

    @Test
    void newPaymentsAre201AndReplaysAre200() throws Exception {
        Payment payment = saved(new Payment(42L, new BigDecimal("19.99"), Payment.Status.APPROVED));
        when(paymentService.charge(anyLong(), any(), any()))
                .thenReturn(new PaymentService.Result(payment, true))
                .thenReturn(new PaymentService.Result(payment, false));

        mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON).content(CHARGE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.paymentToken").doesNotExist());
        mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON).content(CHARGE))
                .andExpect(status().isOk());
    }

    @Test
    void invalidChargesAreRejectedBeforeTheGateway() throws Exception {
        mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":0,\"amount\":0,\"paymentToken\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("orderId", "amount", "paymentToken")));
        mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"amount\":1.999,\"paymentToken\":\"tok\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    void missingPaymentsAndMismatchedRetriesKeepTheirCodes() throws Exception {
        when(paymentService.getByOrder(7)).thenThrow(new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "none"));
        when(paymentService.charge(anyLong(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.PAYMENT_MISMATCH, "different amount"));

        mvc.perform(get("/api/payments").param("orderId", "7").header(HttpHeaders.AUTHORIZATION, INTERNAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON).content(CHARGE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
    }

    /** A payment as it comes back from the database: with an id and a timestamp. */
    static Payment saved(Payment payment) {
        ReflectionTestUtils.setField(payment, "id", 1L);
        ReflectionTestUtils.setField(payment, "createdAt", Instant.parse("2026-09-28T12:00:00Z"));
        return payment;
    }
}
