package dev.lokesh.shop.order;

import dev.lokesh.shop.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static dev.lokesh.shop.order.domain.OrderStatus.CANCELLED;
import static dev.lokesh.shop.order.domain.OrderStatus.CREATED;
import static dev.lokesh.shop.order.domain.OrderStatus.PAID;
import static dev.lokesh.shop.order.domain.OrderStatus.PAYMENT_FAILED;
import static dev.lokesh.shop.order.domain.OrderStatus.PAYMENT_PENDING;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTests {

    @Test
    void createdCanGoAnywhereForward() {
        assertThat(CREATED.canTransitionTo(PAYMENT_PENDING)).isTrue();
        assertThat(CREATED.canTransitionTo(PAID)).isTrue();
        assertThat(CREATED.canTransitionTo(PAYMENT_FAILED)).isTrue();
        assertThat(CREATED.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    void aPendingPaymentOnlyEndsInPaidOrFailedNeverCancelled() {
        // Once a charge may be in flight, cancelling could leave money taken for a cancelled
        // order (expiry and void were cut, design D22), so the only exits are the charge results.
        assertThat(PAYMENT_PENDING.canTransitionTo(PAID)).isTrue();
        assertThat(PAYMENT_PENDING.canTransitionTo(PAYMENT_FAILED)).isTrue();
        assertThat(PAYMENT_PENDING.canTransitionTo(CANCELLED)).isFalse();
        assertThat(PAYMENT_PENDING.canTransitionTo(CREATED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PAYMENT_FAILED", "CANCELLED"})
    void finalStatesAreFinal(OrderStatus status) {
        assertThat(status.isFinal()).isTrue();
        for (OrderStatus next : OrderStatus.values()) {
            assertThat(status.canTransitionTo(next)).as(status + " -> " + next).isFalse();
        }
    }

    @Test
    void nothingGoesBackToCreated() {
        for (OrderStatus from : OrderStatus.values()) {
            assertThat(from.canTransitionTo(CREATED)).as(from + " -> CREATED").isFalse();
        }
    }
}
