package dev.lokesh.shop.order;

import dev.lokesh.shop.order.domain.CancelReason;
import dev.lokesh.shop.order.domain.IllegalTransitionException;
import dev.lokesh.shop.order.domain.InventoryAction;
import dev.lokesh.shop.order.domain.OrderLine;
import dev.lokesh.shop.order.domain.OrderStatus;
import dev.lokesh.shop.order.service.OrderStateService;
import dev.lokesh.shop.order.service.OrderView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The order state service against real MySQL: schema, transitions, history, idempotency key. */
@Testcontainers(disabledWithoutDocker = true)
class OrderStateIT extends MySqlTestSupport {

    @Autowired
    OrderStateService state;

    @Test
    void aHappyOrderMovesThroughTheStatesAndRecordsEveryStep() {
        OrderView order = create(7);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.inventoryAction()).isEqualTo(InventoryAction.NONE);

        OrderView reserved = state.markReserved(order.id(), Map.of(1L, new BigDecimal("10.00"), 2L, new BigDecimal("2.50")));
        assertThat(reserved.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reserved.totalAmount()).isEqualByComparingTo("25.00"); // 2 x 10.00 + 2 x 2.50
        assertThat(reserved.pendingSince()).isNotNull();
        assertThat(reserved.lines()).extracting(OrderView.Line::unitPrice)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("2.50"));

        OrderView paid = state.markPaid(order.id(), "payment approved");
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.inventoryAction()).isEqualTo(InventoryAction.COMMIT_PENDING);
        assertThat(paid.pendingSince()).isNull();

        OrderView done = state.finishInventoryAction(order.id(), InventoryAction.COMMIT_PENDING, InventoryAction.DONE);
        assertThat(done.inventoryAction()).isEqualTo(InventoryAction.DONE);
        assertThat(done.history()).extracting(OrderView.Change::to)
                .containsExactly(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING, OrderStatus.PAID);

        // What we returned is exactly what a fresh read sees (timestamps included).
        assertThat(state.find(order.id())).contains(done);
    }

    @Test
    void illegalTransitionsAreRefusedAndChangeNothing() {
        OrderView order = create(7);
        state.markReserved(order.id(), Map.of(1L, BigDecimal.ONE, 2L, BigDecimal.ONE));

        assertThrows(IllegalTransitionException.class,
                () -> state.cancel(order.id(), CancelReason.TIMED_OUT, InventoryAction.RELEASE_PENDING));
        assertThat(state.find(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void cancellingRecordsTheReasonAndTheStockOwed() {
        OrderView cancelled = state.cancel(create(7).id(), CancelReason.INVENTORY_UNAVAILABLE, InventoryAction.RELEASE_PENDING);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.INVENTORY_UNAVAILABLE);
        assertThat(cancelled.inventoryAction()).isEqualTo(InventoryAction.RELEASE_PENDING);
        assertThat(state.withPendingInventoryAction()).contains(cancelled.id());
    }

    @Test
    void theIdempotencyKeyIsUniquePerUser() {
        String key = UUID.randomUUID().toString();
        state.create(7, key, "a".repeat(64), "tok", lines());

        assertThrows(DataIntegrityViolationException.class, () -> state.create(7, key, "a".repeat(64), "tok", lines()));
        assertThat(state.create(8, key, "a".repeat(64), "tok", lines()).userId()).isEqualTo(8); // another user may reuse it
        assertThat(state.findByKey(7, key)).isPresent();
    }

    @Test
    void theFirstResponseStatusWins() {
        OrderView order = create(7);
        state.recordResponseStatus(order.id(), 202);
        assertThat(state.recordResponseStatus(order.id(), 201).responseStatus()).isEqualTo(202);
    }

    @Test
    void finishingAnInventoryActionIsSkippedIfSomeoneElseAlreadyDidIt() {
        OrderView order = state.cancel(create(7).id(), CancelReason.INVENTORY_UNAVAILABLE, InventoryAction.RELEASE_PENDING);
        state.finishInventoryAction(order.id(), InventoryAction.RELEASE_PENDING, InventoryAction.DONE);

        OrderView again = state.finishInventoryAction(order.id(), InventoryAction.RELEASE_PENDING, InventoryAction.FAILED);
        assertThat(again.inventoryAction()).isEqualTo(InventoryAction.DONE);
    }

    private OrderView create(long userId) {
        return state.create(userId, UUID.randomUUID().toString(), "a".repeat(64), "tok_visa", lines());
    }

    private static List<OrderLine> lines() {
        return List.of(new OrderLine(1L, 2), new OrderLine(2L, 2));
    }
}
