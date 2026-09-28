package dev.lokesh.shop.product.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Bodies for /api/inventory/reservations (internal: called by order-service only). */
public final class ReservationDtos {

    private ReservationDtos() {
    }

    public record ReserveRequest(
            @NotNull @Positive Long orderId,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ReserveItem> items) {
    }

    public record ReserveItem(@NotNull @Positive Long productId, @NotNull @Positive @Max(1000) Integer quantity) {
    }

    public enum Status { RESERVED, COMMITTED, RELEASED }

    public record ReservedItem(long productId, int quantity, BigDecimal unitPrice) {
    }

    /** Items are sorted by product id; unit prices are the ones captured at reserve time. */
    public record ReservationResponse(long orderId, Status status, List<ReservedItem> items) {
    }
}
