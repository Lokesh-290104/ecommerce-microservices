package dev.lokesh.shop.payment.service;

import dev.lokesh.shop.payment.domain.Payment;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real card processor: the token "tok_decline" is declined, anything else is
 * approved. Deterministic on purpose, so demos and tests can choose the outcome.
 */
@Component
public class PaymentGateway {

    public static final String DECLINE_TOKEN = "tok_decline";

    public Payment.Status charge(String paymentToken) {
        return DECLINE_TOKEN.equals(paymentToken) ? Payment.Status.DECLINED : Payment.Status.APPROVED;
    }
}
