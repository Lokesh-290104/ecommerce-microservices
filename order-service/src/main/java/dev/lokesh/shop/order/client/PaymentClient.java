package dev.lokesh.shop.order.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * payment-service. Charging is retried inline (it is idempotent per order on the payment side,
 * so a retry can never charge twice); the lookup is not retried, since the reconciler asks
 * again on its next tick. Both share one breaker: if payments is down, both stop calling it.
 */
@Component
public class PaymentClient {

    static final String SERVICE = "payment-service";

    public enum Outcome { APPROVED, DECLINED }

    record ChargeRequest(long orderId, BigDecimal amount, String paymentToken) {
    }

    record Payment(long id, long orderId, BigDecimal amount, Outcome status) {
    }

    private final RestClient http;
    private final DownstreamCalls calls;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient http, DownstreamCalls calls,
                         CircuitBreakerRegistry breakers, RetryRegistry retries) {
        this.http = http;
        this.calls = calls;
        this.breaker = breakers.circuitBreaker("paymentCB");
        this.retry = retries.retry("paymentRetry");
    }

    public Outcome charge(long orderId, BigDecimal amount, String paymentToken) {
        return calls.call(SERVICE, breaker, retry, () -> http.post()
                .uri("/api/payments")
                .body(new ChargeRequest(orderId, amount, paymentToken))
                .retrieve().body(Payment.class)).status();
    }

    /** What happened to this order's charge; empty if payments never received it. */
    public Optional<Outcome> find(long orderId) {
        try {
            return Optional.of(calls.call(SERVICE, breaker, null, () -> http.get()
                    .uri(uri -> uri.path("/api/payments").queryParam("orderId", orderId).build())
                    .retrieve().body(Payment.class)).status());
        } catch (DownstreamRejectedException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
