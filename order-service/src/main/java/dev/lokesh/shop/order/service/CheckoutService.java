package dev.lokesh.shop.order.service;

import dev.lokesh.shop.order.client.DownstreamRejectedException;
import dev.lokesh.shop.order.client.DownstreamUnavailableException;
import dev.lokesh.shop.order.client.InventoryClient;
import dev.lokesh.shop.order.client.PaymentClient;
import dev.lokesh.shop.order.client.UserClient;
import dev.lokesh.shop.order.domain.CancelReason;
import dev.lokesh.shop.order.domain.IllegalTransitionException;
import dev.lokesh.shop.order.domain.InventoryAction;
import dev.lokesh.shop.order.domain.OrderLine;
import dev.lokesh.shop.order.domain.OrderStatus;
import dev.lokesh.shop.order.error.ApiException;
import dev.lokesh.shop.order.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The checkout flow from the design. Deliberately NOT @Transactional (design D18): it calls
 * three services, and every DB write goes through a short transaction on
 * {@link OrderStateService} between those calls, so no connection or lock is ever held across
 * the network. The order is committed as PAYMENT_PENDING before payment is attempted, so a
 * crash or outage at any point leaves a state the reconciler knows how to finish.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    public record Item(long productId, int quantity) {
    }

    /** The order as it stands, the HTTP status for it, and a hint for the client. */
    public record Result(OrderView order, int httpStatus, String message) {
    }

    private final OrderStateService state;
    private final UserClient users;
    private final InventoryClient inventory;
    private final PaymentClient payments;
    private final InventorySettlement settlement;

    public CheckoutService(OrderStateService state, UserClient users, InventoryClient inventory,
                           PaymentClient payments, InventorySettlement settlement) {
        this.state = state;
        this.users = users;
        this.inventory = inventory;
        this.payments = payments;
        this.settlement = settlement;
    }

    public Result checkout(long userId, String idempotencyKey, List<Item> items, String paymentToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Send an Idempotency-Key header (1-100 characters), e.g. a UUID per checkout.");
        }
        SortedMap<Long, Integer> lines = sorted(items);
        String hash = requestHash(lines, paymentToken);

        // 1. A retry (double click, client timeout): answer from the order we already have.
        var existing = state.findByKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        // 2. Is the buyer real? Nothing has been written yet, so failing here is clean.
        try {
            if (!users.isActiveUser(userId)) {
                throw new ApiException(ErrorCode.USER_NOT_FOUND, "User " + userId + " does not exist or was deleted.");
            }
        } catch (DownstreamUnavailableException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Can't verify your account right now; nothing was ordered or charged. Retry shortly.");
        }

        // 3. Claim the idempotency key. Two identical requests racing: one wins, the other replays it.
        OrderView order;
        try {
            order = state.create(userId, idempotencyKey, hash, paymentToken,
                    lines.entrySet().stream().map(e -> new OrderLine(e.getKey(), e.getValue())).toList());
        } catch (DataIntegrityViolationException raced) {
            return replay(state.findByKey(userId, idempotencyKey).orElseThrow(() -> raced), hash);
        }
        long id = order.id();

        // 4. Hold the stock (no transaction open while we wait).
        Map<Long, BigDecimal> prices;
        try {
            prices = inventory.reserve(id, lines.entrySet().stream()
                    .map(e -> new InventoryClient.Item(e.getKey(), e.getValue())).toList());
        } catch (DownstreamRejectedException e) {
            boolean outOfStock = e.status() == 409;
            CancelReason reason = outOfStock ? CancelReason.OUT_OF_STOCK : CancelReason.INVALID_ITEMS;
            return respond(write(id, () -> state.cancel(id, reason, InventoryAction.NONE)), outOfStock ? 409 : 422);
        } catch (DownstreamUnavailableException e) {
            // Maybe the reserve happened and only the answer was lost: release to be safe (idempotent).
            OrderView cancelled = write(id, () -> state.cancel(id, CancelReason.INVENTORY_UNAVAILABLE,
                    InventoryAction.RELEASE_PENDING));
            return respond(settlement.settle(cancelled), 503);
        }

        // 5. Committed as PAYMENT_PENDING before charging: if we die now, the reconciler finishes it.
        order = write(id, () -> state.markReserved(id, prices));
        if (order.status() != OrderStatus.PAYMENT_PENDING) {
            return respond(order, statusFor(order));
        }

        // 6. Charge (retried inline; idempotent per order on the payment side).
        PaymentClient.Outcome outcome;
        try {
            outcome = payments.charge(id, order.totalAmount(), paymentToken);
        } catch (DownstreamUnavailableException e) {
            log.info("Order {}: payment unavailable ({}); left PAYMENT_PENDING for the reconciler", id, e.getMessage());
            return respond(order, 202);
        } catch (DownstreamRejectedException e) {
            // Should not happen (amounts come from our own order); leave it for the reconciler to look up.
            log.error("Order {}: payment-service rejected the charge ({}); left PAYMENT_PENDING", id, e.getMessage());
            return respond(order, 202);
        }

        // 7. Final state, then settle the stock (commit or release); leftovers go to the reconciler.
        if (outcome == PaymentClient.Outcome.APPROVED) {
            return respond(settlement.settle(write(id, () -> state.markPaid(id, "payment approved"))), 201);
        }
        return respond(settlement.settle(write(id, () -> state.markPaymentFailed(id, "payment declined"))), 402);
    }

    /** Same key + same request: the original outcome. Same key + different request: refused. */
    private Result replay(OrderView order, String hash) {
        if (!order.requestHash().equals(hash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different order; use a new key for a new order.");
        }
        int status = order.responseStatus() != null ? order.responseStatus() : statusFor(order);
        return new Result(order, status, messageFor(order, status));
    }

    /** Records the first answer's status (design D15) and builds the response. */
    private Result respond(OrderView order, int httpStatus) {
        OrderView recorded = write(order.id(), () -> state.recordResponseStatus(order.id(), httpStatus));
        int status = recorded.responseStatus() != null ? recorded.responseStatus() : httpStatus;
        return new Result(recorded, status, messageFor(recorded, status));
    }

    /**
     * Runs one state write. If the reconciler got there first (a @Version race or a move the
     * state machine no longer allows), we don't fight it: we return the order as it now is.
     */
    private OrderView write(long orderId, Supplier<OrderView> change) {
        try {
            return change.get();
        } catch (OptimisticLockingFailureException | IllegalTransitionException raced) {
            log.info("Order {}: lost a race to another writer ({}); answering with the current state", orderId,
                    raced.getMessage());
            return state.find(orderId).orElseThrow();
        }
    }

    /** Status for an order whose first answer was never recorded (in flight, or a crash). */
    static int statusFor(OrderView order) {
        return switch (order.status()) {
            case PAID -> 201;
            case CREATED, PAYMENT_PENDING -> 202;
            case PAYMENT_FAILED -> 402;
            case CANCELLED -> 409;
        };
    }

    private static String messageFor(OrderView order, int status) {
        return switch (order.status()) {
            case PAID -> "Paid.";
            case CREATED, PAYMENT_PENDING -> "We're confirming your payment; this order will update on its own. "
                    + "Check GET /api/orders/" + order.id() + ".";
            case PAYMENT_FAILED -> "Payment was declined and nothing was charged. Retry with a new Idempotency-Key.";
            case CANCELLED -> (status == 503 ? "The store couldn't be reached" : "The order couldn't be placed")
                    + " (" + order.cancelReason() + "); nothing was charged. Retry with a new Idempotency-Key.";
        };
    }

    private static SortedMap<Long, Integer> sorted(List<Item> items) {
        SortedMap<Long, Integer> lines = new TreeMap<>();
        for (Item item : items) {
            if (lines.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw new ApiException(ErrorCode.DUPLICATE_ITEMS,
                        "Product " + item.productId() + " appears more than once; send one line per product.");
            }
        }
        return lines;
    }

    /** The idempotency hash of a checkout request (public for tests and tooling). */
    public static String requestHash(List<Item> items, String paymentToken) {
        return requestHash(sorted(items), paymentToken);
    }

    /** SHA-256 of the request in a canonical form (items sorted by product id). */
    static String requestHash(SortedMap<Long, Integer> lines, String paymentToken) {
        StringBuilder canonical = new StringBuilder();
        lines.forEach((product, quantity) -> canonical.append(product).append('x').append(quantity).append(';'));
        canonical.append('|').append(paymentToken);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
