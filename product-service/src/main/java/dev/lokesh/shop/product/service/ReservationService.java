package dev.lokesh.shop.product.service;

import dev.lokesh.shop.product.api.ReservationDtos.ReservationResponse;
import dev.lokesh.shop.product.api.ReservationDtos.ReserveItem;
import dev.lokesh.shop.product.api.ReservationDtos.ReservedItem;
import dev.lokesh.shop.product.error.ApiException;
import dev.lokesh.shop.product.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Idempotent inventory reservations for order-service. Deliberately not @Transactional: when
 * a reserve loses the race for its order id, that transaction is already rolled back, so the
 * existing reservation is read in a fresh transaction and replayed to the caller.
 */
@Service
public class ReservationService {

    static final int MAX_LOCK_RETRIES = 3;

    private final ReservationStore store;

    public ReservationService(ReservationStore store) {
        this.store = store;
    }

    public ReservationResponse reserve(long orderId, List<ReserveItem> requested) {
        SortedMap<Long, Integer> items = toSortedMap(requested);
        return retryOnLockFailure(() -> {
            try {
                return store.reserve(orderId, items);
            } catch (DuplicateKeyException alreadyUsed) {
                ReservationResponse existing = store.find(orderId).orElseThrow(() -> alreadyUsed);
                return replay(existing, items);
            }
        });
    }

    public ReservationResponse commit(long orderId) {
        return retryOnLockFailure(() -> store.commit(orderId));
    }

    public ReservationResponse release(long orderId) {
        return retryOnLockFailure(() -> {
            try {
                return store.release(orderId);
            } catch (DuplicateKeyException reservedMeanwhile) {
                // A reserve created the row between our check and our tombstone: release that one.
                return store.release(orderId);
            }
        });
    }

    /**
     * A retry of the same reserve gets the original result (same prices). The same order id
     * with different items, or one that was released or already paid, is refused.
     */
    private static ReservationResponse replay(ReservationResponse existing, SortedMap<Long, Integer> items) {
        switch (existing.status()) {
            case RELEASED -> throw new ApiException(ErrorCode.RESERVATION_RELEASED,
                    "The reservation for order " + existing.orderId() + " was released; use a new order.");
            case COMMITTED -> throw new ApiException(ErrorCode.RESERVATION_COMMITTED,
                    "Order " + existing.orderId() + " is already paid.");
            case RESERVED -> {
                Map<Long, Integer> stored = existing.items().stream()
                        .collect(Collectors.toMap(ReservedItem::productId, ReservedItem::quantity));
                if (!stored.equals(items)) {
                    throw new ApiException(ErrorCode.RESERVATION_MISMATCH,
                            "Order " + existing.orderId() + " was already reserved with different items.");
                }
                return existing;
            }
        }
        throw new IllegalStateException("unreachable: " + existing.status());
    }

    private static SortedMap<Long, Integer> toSortedMap(List<ReserveItem> requested) {
        SortedMap<Long, Integer> items = new TreeMap<>();
        for (ReserveItem item : requested) {
            if (items.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw new ApiException(ErrorCode.INVALID_ITEMS,
                        "Product " + item.productId() + " appears more than once; send one line per product.");
            }
        }
        return items;
    }

    /**
     * Every operation is idempotent, so losing a lock race is safe to retry. The typical case:
     * a release checking a missing row takes a gap lock that deadlocks with a reserve inserting
     * that order id; MySQL kills one of them and a retry sees the winner's committed result.
     */
    private static <T> T retryOnLockFailure(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt == MAX_LOCK_RETRIES) {
                    throw e;
                }
            }
        }
    }
}
