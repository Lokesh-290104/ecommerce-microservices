package dev.lokesh.shop.order.service;

import dev.lokesh.shop.order.domain.CancelReason;
import dev.lokesh.shop.order.domain.InventoryAction;
import dev.lokesh.shop.order.domain.Order;
import dev.lokesh.shop.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A detached, immutable copy of an order, taken inside a transaction. Checkout and the
 * reconciler work on these between transactions, so nothing lazy-loads outside one.
 */
public record OrderView(
        long id,
        long userId,
        String requestHash,
        String paymentToken,
        OrderStatus status,
        CancelReason cancelReason,
        InventoryAction inventoryAction,
        BigDecimal totalAmount,
        Integer responseStatus,
        Instant pendingSince,
        Instant createdAt,
        List<Line> lines,
        List<Change> history) {

    public record Line(long productId, int quantity, BigDecimal unitPrice) {
    }

    public record Change(OrderStatus from, OrderStatus to, String reason, Instant at) {
    }

    static OrderView of(Order o) {
        return new OrderView(o.getId(), o.getUserId(), o.getRequestHash(), o.getPaymentToken(), o.getStatus(),
                o.getCancelReason(), o.getInventoryAction(), o.getTotalAmount(), o.getResponseStatus(),
                o.getPendingSince(), o.getCreatedAt(),
                o.getLines().stream().map(l -> new Line(l.getProductId(), l.getQuantity(), l.getUnitPrice())).toList(),
                o.getHistory().stream().map(h -> new Change(h.getFrom(), h.getTo(), h.getReason(), h.getChangedAt())).toList());
    }
}
