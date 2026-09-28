package dev.lokesh.shop.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The order state machine. Every rule about which status may follow which is here and nowhere
 * else; {@link Order#transitionTo} refuses anything this does not allow.
 *
 * <pre>
 * CREATED --> PAYMENT_PENDING --> PAID
 *    |              '-----------> PAYMENT_FAILED
 *    '--> CANCELLED (OUT_OF_STOCK | INVALID_ITEMS | INVENTORY_UNAVAILABLE | TIMED_OUT)
 *    '--> PAID | PAYMENT_FAILED
 * </pre>
 */
public enum OrderStatus {
    CREATED, PAYMENT_PENDING, PAID, PAYMENT_FAILED, CANCELLED;

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }

    private Set<OrderStatus> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(PAYMENT_PENDING, PAID, PAYMENT_FAILED, CANCELLED);
            // No cancel while a charge may be in flight: expiry and void were cut (design D22).
            case PAYMENT_PENDING -> EnumSet.of(PAID, PAYMENT_FAILED);
            case PAID, PAYMENT_FAILED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }
}
