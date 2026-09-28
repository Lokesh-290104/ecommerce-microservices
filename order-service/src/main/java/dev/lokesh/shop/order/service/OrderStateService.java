package dev.lokesh.shop.order.service;

import dev.lokesh.shop.order.domain.CancelReason;
import dev.lokesh.shop.order.domain.InventoryAction;
import dev.lokesh.shop.order.domain.Order;
import dev.lokesh.shop.order.domain.OrderLine;
import dev.lokesh.shop.order.domain.OrderRepository;
import dev.lokesh.shop.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every write to an order, each as one short transaction (design D18). This bean never calls
 * another service: CheckoutService and the Reconciler call it between their HTTP calls, so no
 * database connection or lock is held while waiting on the network. Each write flushes before
 * returning, so a lost @Version race or an illegal transition surfaces to the caller right away.
 */
@Service
public class OrderStateService {

    private final OrderRepository orders;
    private final Clock clock;

    public OrderStateService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    /** @throws org.springframework.dao.DataIntegrityViolationException if (user, key) already exists */
    @Transactional
    public OrderView create(long userId, String idempotencyKey, String requestHash, String paymentToken,
                            List<OrderLine> lines) {
        return OrderView.of(orders.saveAndFlush(new Order(userId, idempotencyKey, requestHash, paymentToken, lines, now())));
    }

    /** Stock is held: record prices and total, and wait for payment. */
    @Transactional
    public OrderView markReserved(long orderId, Map<Long, BigDecimal> unitPrices) {
        Order order = load(orderId);
        order.applyPrices(unitPrices);
        order.transitionTo(OrderStatus.PAYMENT_PENDING, "stock reserved", now());
        return save(order);
    }

    @Transactional
    public OrderView cancel(long orderId, CancelReason reason, InventoryAction owed) {
        Order order = load(orderId);
        order.cancel(reason, owed, now());
        return save(order);
    }

    /** Paid: the reserved stock must now be committed (shipped). */
    @Transactional
    public OrderView markPaid(long orderId, String reason) {
        Order order = load(orderId);
        order.transitionTo(OrderStatus.PAID, reason, now());
        order.setInventoryAction(InventoryAction.COMMIT_PENDING, now());
        return save(order);
    }

    /** Declined: the reserved stock must now be released. */
    @Transactional
    public OrderView markPaymentFailed(long orderId, String reason) {
        Order order = load(orderId);
        order.transitionTo(OrderStatus.PAYMENT_FAILED, reason, now());
        order.setInventoryAction(InventoryAction.RELEASE_PENDING, now());
        return save(order);
    }

    /** Only moves the action on if it is still the one we attempted (another writer may have finished it). */
    @Transactional
    public OrderView finishInventoryAction(long orderId, InventoryAction attempted, InventoryAction outcome) {
        Order order = load(orderId);
        if (order.getInventoryAction() == attempted) {
            order.setInventoryAction(outcome, now());
            return save(order);
        }
        return OrderView.of(order);
    }

    @Transactional
    public OrderView recordResponseStatus(long orderId, int httpStatus) {
        Order order = load(orderId);
        order.recordResponseStatus(httpStatus);
        return save(order);
    }

    @Transactional(readOnly = true)
    public Optional<OrderView> find(long orderId) {
        return orders.findById(orderId).map(OrderView::of);
    }

    @Transactional(readOnly = true)
    public Optional<OrderView> findByKey(long userId, String idempotencyKey) {
        return orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(OrderView::of);
    }

    /**
     * The caller's orders (step 8): lines and history load in one batch each for the whole page
     * (@BatchSize on Order), instead of one query per order as in v1. Two-step ID paging was
     * measured too and lost here (p95 44.9 vs 33.3 ms): with two collections it needs two fetch
     * queries and the lines join multiplies rows. See benchmarks/.
     */
    @Transactional(readOnly = true)
    public Page<OrderView> listForUser(long userId, Pageable pageable) {
        return orders.findByUserId(userId, pageable).map(OrderView::of);
    }

    // --- reconciler scans ---

    @Transactional(readOnly = true)
    public List<Long> createdBefore(Instant before) {
        return orders.findIdsByStatusCreatedBefore(OrderStatus.CREATED, before);
    }

    @Transactional(readOnly = true)
    public List<Long> pendingSinceBefore(Instant before) {
        return orders.findIdsByStatusPendingSince(OrderStatus.PAYMENT_PENDING, before);
    }

    @Transactional(readOnly = true)
    public List<Long> withPendingInventoryAction() {
        return orders.findIdsByInventoryActionIn(List.of(InventoryAction.RELEASE_PENDING, InventoryAction.COMMIT_PENDING));
    }

    /** Microseconds, matching DATETIME(6), so what we return equals what we later read back. */
    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Order load(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new IllegalStateException("No order " + orderId));
    }

    private OrderView save(Order order) {
        return OrderView.of(orders.saveAndFlush(order));
    }
}
