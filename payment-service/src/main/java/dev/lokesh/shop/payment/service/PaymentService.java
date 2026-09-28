package dev.lokesh.shop.payment.service;

import dev.lokesh.shop.payment.domain.Payment;
import dev.lokesh.shop.payment.error.ApiException;
import dev.lokesh.shop.payment.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Charges an order at most once (design D12). Not @Transactional itself: when two charges for
 * one order race, the loser's INSERT violates UNIQUE(order_id) and its transaction is rolled
 * back, so the winner is read in a fresh transaction and returned to both callers.
 */
@Service
public class PaymentService {

    public record Result(Payment payment, boolean created) {
    }

    private final PaymentStore store;
    private final PaymentGateway gateway;

    public PaymentService(PaymentStore store, PaymentGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public Result charge(long orderId, BigDecimal amount, String paymentToken) {
        var existing = store.findByOrderId(orderId);
        if (existing.isPresent()) {
            return replay(existing.get(), amount);
        }
        Payment.Status status = gateway.charge(paymentToken);
        try {
            return new Result(store.insert(new Payment(orderId, amount, status)), true);
        } catch (DataIntegrityViolationException lostTheRace) {
            Payment winner = store.findByOrderId(orderId).orElseThrow(() -> lostTheRace);
            return replay(winner, amount);
        }
    }

    public Payment get(long id) {
        return store.findById(id).orElseThrow(() -> notFound("No payment with id " + id + "."));
    }

    public Payment getByOrder(long orderId) {
        return store.findByOrderId(orderId).orElseThrow(() -> notFound("No payment for order " + orderId + "."));
    }

    /** A retry returns the stored outcome; the same order with another amount is refused. */
    private static Result replay(Payment payment, BigDecimal amount) {
        if (payment.getAmount().compareTo(amount) != 0) {
            throw new ApiException(ErrorCode.PAYMENT_MISMATCH, "Order " + payment.getOrderId()
                    + " was already charged " + payment.getAmount() + ", not " + amount + ".");
        }
        return new Result(payment, false);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.PAYMENT_NOT_FOUND, detail);
    }
}
