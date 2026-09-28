package dev.lokesh.shop.order.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // Two-step listing (step 8, design D21): page over ids only (index on user_id, created_at),
    // then fetch those orders with lines, then with history. Two collections of one entity
    // can't be JOIN FETCHed together (MultipleBagFetchException), hence two fetch queries.

    @Query(value = "select o.id from CustomerOrder o where o.userId = :userId",
            countQuery = "select count(o) from CustomerOrder o where o.userId = :userId")
    Page<Long> findIdsByUserId(Long userId, Pageable pageable);

    @Query("select distinct o from CustomerOrder o left join fetch o.lines where o.id in :ids")
    List<Order> findWithLinesByIdIn(Collection<Long> ids);

    @Query("select distinct o from CustomerOrder o left join fetch o.history where o.id in :ids")
    List<Order> findWithHistoryByIdIn(Collection<Long> ids);

    // --- reconciler scans (ids only; each order is then handled in its own short transaction) ---

    @Query("select o.id from CustomerOrder o where o.status = :status and o.createdAt < :before")
    List<Long> findIdsByStatusCreatedBefore(OrderStatus status, Instant before);

    @Query("select o.id from CustomerOrder o where o.status = :status and o.pendingSince < :before")
    List<Long> findIdsByStatusPendingSince(OrderStatus status, Instant before);

    @Query("select o.id from CustomerOrder o where o.inventoryAction in :actions")
    List<Long> findIdsByInventoryActionIn(List<InventoryAction> actions);
}
