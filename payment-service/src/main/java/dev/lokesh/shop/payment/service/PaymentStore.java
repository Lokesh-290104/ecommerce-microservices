package dev.lokesh.shop.payment.service;

import dev.lokesh.shop.payment.domain.Payment;
import dev.lokesh.shop.payment.domain.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Short transactions for {@link PaymentService}. A separate bean, so the service calls go
 * through the transaction proxy and can handle a failed insert after its rollback.
 */
@Component
public class PaymentStore {

    private final PaymentRepository payments;

    public PaymentStore(PaymentRepository payments) {
        this.payments = payments;
    }

    /** Throws DataIntegrityViolationException if a payment for this order already exists. */
    @Transactional
    public Payment insert(Payment payment) {
        return payments.saveAndFlush(payment);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(long orderId) {
        return payments.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findById(long id) {
        return payments.findById(id);
    }
}
