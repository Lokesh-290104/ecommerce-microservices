package dev.lokesh.shop.order.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /** v1 listing (step 8 baseline): no (user_id, created_at) index yet, lines loaded lazily. */
    Page<Order> findByUserId(Long userId, Pageable pageable);

    // --- reconciler scans (ids only; each order is then handled in its own short transaction) ---

    @Query("select o.id from CustomerOrder o where o.status = :status and o.createdAt < :before")
    List<Long> findIdsByStatusCreatedBefore(OrderStatus status, Instant before);

    @Query("select o.id from CustomerOrder o where o.status = :status and o.pendingSince < :before")
    List<Long> findIdsByStatusPendingSince(OrderStatus status, Instant before);

    @Query("select o.id from CustomerOrder o where o.inventoryAction in :actions")
    List<Long> findIdsByInventoryActionIn(List<InventoryAction> actions);
}
