package dev.lokesh.shop.payment.api;

import dev.lokesh.shop.payment.domain.Payment;
import dev.lokesh.shop.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/** Payments API. Internal only: order-service's service token (see SecurityConfig). */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    /** The demo delay is for timeouts, not for tying up threads forever. */
    static final long MAX_DELAY_MS = 30_000;

    private final PaymentService paymentService;
    private final long responseDelayMs;

    public PaymentController(PaymentService paymentService,
                             @Value("${shop.payments.response-delay-ms:0}") long responseDelayMs) {
        this.paymentService = paymentService;
        this.responseDelayMs = Math.clamp(responseDelayMs, 0, MAX_DELAY_MS);
    }

    public record ChargeRequest(
            @NotNull @Positive Long orderId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            /* Card/wallet token from the client; never stored. */
            @NotBlank @Size(max = 200) String paymentToken) {
    }

    public record PaymentResponse(long id, long orderId, BigDecimal amount, Payment.Status status, Instant createdAt) {

        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderId(), p.getAmount(), p.getStatus(), p.getCreatedAt());
        }
    }

    /**
     * 201 for a new payment, 200 when replaying the stored one for a retried order. With the
     * demo delay set, the response is held back only after the payment is committed (the
     * service call has returned), so the caller times out on a charge that did happen (D16).
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> charge(@Valid @RequestBody ChargeRequest request) throws InterruptedException {
        PaymentService.Result result = paymentService.charge(request.orderId(), request.amount(), request.paymentToken());
        if (responseDelayMs > 0) {
            Thread.sleep(responseDelayMs);
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PaymentResponse.from(result.payment()));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable long id) {
        return PaymentResponse.from(paymentService.get(id));
    }

    /** Used by the order reconciler to find out what happened to a charge that timed out. */
    @GetMapping(params = "orderId")
    public PaymentResponse getByOrder(@RequestParam @Positive long orderId) {
        return PaymentResponse.from(paymentService.getByOrder(orderId));
    }
}
