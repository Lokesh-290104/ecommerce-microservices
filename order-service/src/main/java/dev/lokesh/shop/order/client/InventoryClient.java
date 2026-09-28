package dev.lokesh.shop.order.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * product-service stock reservations. No inline retry (design): a failed reserve fails the
 * checkout, and a failed commit/release is retried by the reconciler instead of the caller.
 * All three calls are idempotent per order id on the product side.
 */
@Component
public class InventoryClient {

    static final String SERVICE = "product-service";

    public record Item(long productId, int quantity) {
    }

    record ReserveRequest(long orderId, List<Item> items) {
    }

    record ReservedItem(long productId, int quantity, BigDecimal unitPrice) {
    }

    record Reservation(long orderId, String status, List<ReservedItem> items) {
    }

    private final RestClient http;
    private final DownstreamCalls calls;
    private final CircuitBreaker breaker;

    public InventoryClient(@Qualifier("productRestClient") RestClient http, DownstreamCalls calls,
                           CircuitBreakerRegistry breakers) {
        this.http = http;
        this.calls = calls;
        this.breaker = breakers.circuitBreaker("inventoryCB");
    }

    /** Holds every item or none. Returns the unit price of each product. */
    public Map<Long, BigDecimal> reserve(long orderId, List<Item> items) {
        Reservation reservation = calls.call(SERVICE, breaker, null, () -> http.post()
                .uri("/api/inventory/reservations")
                .body(new ReserveRequest(orderId, items))
                .retrieve().body(Reservation.class));
        return reservation.items().stream().collect(Collectors.toMap(ReservedItem::productId, ReservedItem::unitPrice));
    }

    public void commit(long orderId) {
        calls.call(SERVICE, breaker, null, () -> http.post()
                .uri("/api/inventory/reservations/{orderId}/commit", orderId).retrieve().toBodilessEntity());
    }

    public void release(long orderId) {
        calls.call(SERVICE, breaker, null, () -> http.delete()
                .uri("/api/inventory/reservations/{orderId}", orderId).retrieve().toBodilessEntity());
    }
}
