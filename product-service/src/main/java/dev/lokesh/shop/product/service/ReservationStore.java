package dev.lokesh.shop.product.service;

import dev.lokesh.shop.product.api.ReservationDtos.ReservationResponse;
import dev.lokesh.shop.product.api.ReservationDtos.ReservedItem;
import dev.lokesh.shop.product.api.ReservationDtos.Status;
import dev.lokesh.shop.product.error.ApiException;
import dev.lokesh.shop.product.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;

/**
 * The reservation state machine as short, single-purpose transactions, all in plain SQL so
 * each stock change is one conditional UPDATE that the database checks atomically.
 * Kept in its own bean so {@link ReservationService} calls always go through the transaction
 * proxy (no self-invocation) and can react to a failed transaction from outside it.
 */
@Component
public class ReservationStore {

    private final JdbcClient jdbc;

    public ReservationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the order id, then reserves every item or none. Items are updated in product id
     * order, so two orders touching the same products lock rows in the same order and can't
     * deadlock. Throws DuplicateKeyException if this order id was already used; any ApiException
     * rolls the whole reservation (including the claimed id) back.
     */
    @Transactional
    public ReservationResponse reserve(long orderId, SortedMap<Long, Integer> items) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO reservations (order_id, status, created_at, updated_at) VALUES (?, 'RESERVED', ?, ?)")
                .params(orderId, now, now).update();

        Map<Long, BigDecimal> prices = prices(items.keySet());
        for (Map.Entry<Long, Integer> item : items.entrySet()) {
            long productId = item.getKey();
            int quantity = item.getValue();
            if (!prices.containsKey(productId)) {
                throw new ApiException(ErrorCode.UNKNOWN_PRODUCT, "No product with id " + productId + ".");
            }
            int updated = jdbc.sql("""
                            UPDATE inventory SET reserved = reserved + :q
                            WHERE product_id = :id AND on_hand - reserved >= :q""")
                    .param("q", quantity).param("id", productId).update();
            if (updated == 0) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                        "Not enough stock for product " + productId + " (wanted " + quantity + ").");
            }
            jdbc.sql("INSERT INTO reservation_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)")
                    .params(orderId, productId, quantity, prices.get(productId)).update();
        }
        return new ReservationResponse(orderId, Status.RESERVED, items.entrySet().stream()
                .map(e -> new ReservedItem(e.getKey(), e.getValue(), prices.get(e.getKey())))
                .toList());
    }

    @Transactional(readOnly = true)
    public Optional<ReservationResponse> find(long orderId) {
        return status(orderId, false).map(status -> new ReservationResponse(orderId, status, items(orderId)));
    }

    /** Paid: the reserved units leave the warehouse. Repeating it is a no-op. */
    @Transactional
    public ReservationResponse commit(long orderId) {
        Status status = status(orderId, true).orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_ACTIVE,
                "Order " + orderId + " has no reservation to commit."));
        List<ReservedItem> items = items(orderId);
        switch (status) {
            case COMMITTED -> {
                return new ReservationResponse(orderId, status, items);
            }
            case RELEASED -> throw new ApiException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "The reservation for order " + orderId + " was released and can't be committed.");
            case RESERVED -> {
                for (ReservedItem item : items) {
                    jdbc.sql("""
                                    UPDATE inventory SET on_hand = on_hand - :q, reserved = reserved - :q
                                    WHERE product_id = :id""")
                            .param("q", item.quantity()).param("id", item.productId()).update();
                }
                setStatus(orderId, Status.COMMITTED);
                return new ReservationResponse(orderId, Status.COMMITTED, items);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * Payment failed or the order timed out: give the stock back. Releasing an order that was
     * never reserved writes a RELEASED tombstone, so a reserve arriving late is refused.
     * Throws DuplicateKeyException if a reserve created the row concurrently (caller retries).
     */
    @Transactional
    public ReservationResponse release(long orderId) {
        Optional<Status> status = status(orderId, true);
        if (status.isEmpty()) {
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.sql("INSERT INTO reservations (order_id, status, created_at, updated_at) VALUES (?, 'RELEASED', ?, ?)")
                    .params(orderId, now, now).update();
            return new ReservationResponse(orderId, Status.RELEASED, List.of());
        }
        List<ReservedItem> items = items(orderId);
        switch (status.get()) {
            case RELEASED -> {
                return new ReservationResponse(orderId, Status.RELEASED, items);
            }
            case COMMITTED -> throw new ApiException(ErrorCode.RESERVATION_COMMITTED,
                    "Order " + orderId + " is paid; its stock can't be released.");
            case RESERVED -> {
                for (ReservedItem item : items) {
                    jdbc.sql("UPDATE inventory SET reserved = reserved - :q WHERE product_id = :id")
                            .param("q", item.quantity()).param("id", item.productId()).update();
                }
                setStatus(orderId, Status.RELEASED);
                return new ReservationResponse(orderId, Status.RELEASED, items);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** With lock = true the row stays locked until commit, serializing commit/release per order. */
    private Optional<Status> status(long orderId, boolean lock) {
        return jdbc.sql("SELECT status FROM reservations WHERE order_id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(orderId).query(String.class).optional().map(Status::valueOf);
    }

    private List<ReservedItem> items(long orderId) {
        return jdbc.sql("""
                        SELECT product_id, quantity, unit_price FROM reservation_items
                        WHERE order_id = ? ORDER BY product_id""")
                .param(orderId)
                .query((rs, n) -> new ReservedItem(rs.getLong(1), rs.getInt(2), rs.getBigDecimal(3)))
                .list();
    }

    private Map<Long, BigDecimal> prices(Set<Long> productIds) {
        return jdbc.sql("SELECT id, price FROM products WHERE id IN (:ids)")
                .param("ids", productIds)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getBigDecimal(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void setStatus(long orderId, Status status) {
        jdbc.sql("UPDATE reservations SET status = ?, updated_at = ? WHERE order_id = ?")
                .params(status.name(), Timestamp.from(Instant.now()), orderId).update();
    }
}
