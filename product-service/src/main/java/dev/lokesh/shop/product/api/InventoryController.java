package dev.lokesh.shop.product.api;

import dev.lokesh.shop.product.api.ReservationDtos.ReservationResponse;
import dev.lokesh.shop.product.api.ReservationDtos.ReserveRequest;
import dev.lokesh.shop.product.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock reservations for checkout. Internal only (scope=internal service tokens, see
 * SecurityConfig). Every operation is idempotent, so order-service and its reconciler can
 * safely retry after a timeout without double-reserving or double-releasing stock.
 */
@RestController
@RequestMapping("/api/inventory/reservations")
public class InventoryController {

    private final ReservationService reservations;

    public InventoryController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /** All items or none. 200 with unit prices, including for a retry of the same request. */
    @PostMapping
    public ReservationResponse reserve(@Valid @RequestBody ReserveRequest request) {
        return reservations.reserve(request.orderId(), request.items());
    }

    @PostMapping("/{orderId}/commit")
    public ReservationResponse commit(@PathVariable @Positive long orderId) {
        return reservations.commit(orderId);
    }

    @DeleteMapping("/{orderId}")
    public ReservationResponse release(@PathVariable @Positive long orderId) {
        return reservations.release(orderId);
    }
}
