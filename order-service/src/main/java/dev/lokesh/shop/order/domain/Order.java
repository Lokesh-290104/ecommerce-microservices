package dev.lokesh.shop.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A checkout attempt. Every change goes through {@link #transitionTo}, which enforces the state
 * machine and records history; @Version makes concurrent writers (checkout vs reconciler)
 * detect each other instead of overwriting.
 */
@Entity(name = "CustomerOrder") // "Order" is a JPQL keyword
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64, columnDefinition = "char(64)")
    private String requestHash;

    @Column(name = "payment_token", nullable = false, updatable = false, length = 200)
    private String paymentToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private CancelReason cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_action", nullable = false, length = 20)
    private InventoryAction inventoryAction;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "pending_since")
    private Instant pendingSince;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderBy("productId")
    @BatchSize(size = 20) // step 8: lines for a whole page of orders in one query
    private List<OrderLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    @BatchSize(size = 20) // step 8: history for a whole page of orders in one query
    private List<StatusChange> history = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    public Order(long userId, String idempotencyKey, String requestHash, String paymentToken,
                 List<OrderLine> lines, Instant now) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.paymentToken = paymentToken;
        this.lines.addAll(lines);
        this.status = OrderStatus.CREATED;
        this.inventoryAction = InventoryAction.NONE;
        this.createdAt = now;
        this.updatedAt = now;
        history.add(new StatusChange(this, null, OrderStatus.CREATED, null, now));
    }

    /** @throws IllegalTransitionException if the state machine does not allow the move */
    public void transitionTo(OrderStatus next, String reason, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalTransitionException(id, status, next);
        }
        history.add(new StatusChange(this, status, next, reason, now));
        status = next;
        pendingSince = next == OrderStatus.PAYMENT_PENDING ? now : null;
        updatedAt = now;
    }

    public void cancel(CancelReason reason, InventoryAction owed, Instant now) {
        transitionTo(OrderStatus.CANCELLED, reason.name(), now);
        this.cancelReason = reason;
        this.inventoryAction = owed;
    }

    /** Prices every line from the reservation and fixes the order total. */
    public void applyPrices(Map<Long, BigDecimal> unitPrices) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLine line : lines) {
            BigDecimal price = unitPrices.get(line.getProductId());
            if (price == null) {
                throw new IllegalStateException("Reservation for order " + id + " has no price for product "
                        + line.getProductId());
            }
            line.price(price);
            total = total.add(price.multiply(BigDecimal.valueOf(line.getQuantity())));
        }
        this.totalAmount = total;
    }

    public void setInventoryAction(InventoryAction action, Instant now) {
        this.inventoryAction = action;
        this.updatedAt = now;
    }

    /** The first answer's HTTP status wins; replays return it (design D15). */
    public void recordResponseStatus(int httpStatus) {
        if (responseStatus == null) {
            responseStatus = httpStatus;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getPaymentToken() {
        return paymentToken;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public InventoryAction getInventoryAction() {
        return inventoryAction;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public Instant getPendingSince() {
        return pendingSince;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public List<StatusChange> getHistory() {
        return history;
    }
}
