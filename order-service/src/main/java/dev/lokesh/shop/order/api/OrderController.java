package dev.lokesh.shop.order.api;

import dev.lokesh.shop.order.domain.CancelReason;
import dev.lokesh.shop.order.domain.OrderStatus;
import dev.lokesh.shop.order.error.ApiException;
import dev.lokesh.shop.order.error.ErrorCode;
import dev.lokesh.shop.order.security.Tokens;
import dev.lokesh.shop.order.service.CheckoutService;
import dev.lokesh.shop.order.service.OrderStateService;
import dev.lokesh.shop.order.service.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Orders API for signed-in users. The buyer is always the token's subject, never the body. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final CheckoutService checkout;
    private final OrderStateService orders;

    public OrderController(CheckoutService checkout, OrderStateService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    public record CheckoutRequest(
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ItemRequest> items,
            @NotBlank @Size(max = 200) String paymentToken) {
    }

    public record ItemRequest(@NotNull @Positive Long productId, @NotNull @Positive @Max(1000) Integer quantity) {
    }

    public record OrderResponse(long id, OrderStatus status, CancelReason cancelReason, BigDecimal totalAmount,
                                List<OrderView.Line> items, List<OrderView.Change> history, Instant createdAt,
                                String message) {

        static OrderResponse of(OrderView o, String message) {
            return new OrderResponse(o.id(), o.status(), o.cancelReason(), o.totalAmount(), o.lines(), o.history(),
                    o.createdAt(), message);
        }
    }

    public record OrderPage(List<OrderResponse> content, int page, int size, long totalElements, int totalPages) {
    }

    /**
     * 201 paid, 202 payment still being confirmed (settles on its own), 402 declined,
     * 409 out of stock, 422 invalid items, 503 a dependency is down. Retrying with the same
     * Idempotency-Key returns the same order and status; nothing is ever charged twice.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> place(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody CheckoutRequest request,
                                               @AuthenticationPrincipal Jwt caller) {
        CheckoutService.Result result = checkout.checkout(userId(caller), key,
                request.items().stream().map(i -> new CheckoutService.Item(i.productId(), i.quantity())).toList(),
                request.paymentToken());
        return ResponseEntity.status(result.httpStatus()).body(OrderResponse.of(result.order(), result.message()));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt caller) {
        long userId = userId(caller);
        OrderView order = orders.find(id)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "No order with id " + id + "."));
        if (order.userId() != userId) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This order belongs to another user.");
        }
        return OrderResponse.of(order, null);
    }

    /** The caller's own orders, newest first. */
    @GetMapping
    public OrderPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                          @AuthenticationPrincipal Jwt caller) {
        var result = orders.listForUser(userId(caller),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new OrderPage(result.getContent().stream().map(o -> OrderResponse.of(o, null)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /** Orders are placed and read by users; a service token has no user to act for. */
    private static long userId(Jwt caller) {
        if (Tokens.isInternal(caller)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Orders are placed and read with a user token.");
        }
        return Long.parseLong(caller.getSubject());
    }
}
