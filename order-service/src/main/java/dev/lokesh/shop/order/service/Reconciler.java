package dev.lokesh.shop.order.service;

import dev.lokesh.shop.order.client.DownstreamRejectedException;
import dev.lokesh.shop.order.client.DownstreamUnavailableException;
import dev.lokesh.shop.order.client.PaymentClient;
import dev.lokesh.shop.order.domain.CancelReason;
import dev.lokesh.shop.order.domain.IllegalTransitionException;
import dev.lokesh.shop.order.domain.InventoryAction;
import dev.lokesh.shop.order.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Finishes what checkout couldn't (design: Reconciler table). Not transactional: like checkout,
 * it calls other services between short OrderStateService writes. Each order is handled on its
 * own; a @Version race means someone else moved it, so it is simply skipped until the next tick.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    /** A checkout normally leaves CREATED within seconds; 60 s means it died mid-flight. */
    static final Duration CREATED_TIMEOUT = Duration.ofSeconds(60);
    /** Don't race a checkout that is still charging (design D27). */
    static final Duration PENDING_GRACE = Duration.ofSeconds(10);

    private final OrderStateService state;
    private final PaymentClient payments;
    private final InventorySettlement settlement;
    private final Clock clock;

    public Reconciler(OrderStateService state, PaymentClient payments, InventorySettlement settlement, Clock clock) {
        this.state = state;
        this.payments = payments;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shop.reconciler.interval:15s}", initialDelayString = "${shop.reconciler.interval:15s}")
    public void tick() {
        try {
            runOnce(clock.instant());
        } catch (RuntimeException e) {
            log.error("Reconciler tick failed; will retry next tick", e);
        }
    }

    public void runOnce(Instant now) {
        for (long id : state.createdBefore(now.minus(CREATED_TIMEOUT))) {
            guarded(id, () -> settlement.settle(state.cancel(id, CancelReason.TIMED_OUT, InventoryAction.RELEASE_PENDING)));
        }
        for (long id : state.pendingSinceBefore(now.minus(PENDING_GRACE))) {
            guarded(id, () -> settlePayment(id));
        }
        for (long id : state.withPendingInventoryAction()) {
            guarded(id, () -> state.find(id).ifPresent(settlement::settle));
        }
    }

    /** Ask payments what happened; if it never got the charge, send it again (idempotent per order). */
    private void settlePayment(long id) {
        OrderView order = state.find(id).orElseThrow();
        if (order.status() != OrderStatus.PAYMENT_PENDING) {
            return;
        }
        Optional<PaymentClient.Outcome> outcome = payments.find(id);
        PaymentClient.Outcome result = outcome.isPresent() ? outcome.get()
                : payments.charge(id, order.totalAmount(), order.paymentToken());
        OrderView settled = result == PaymentClient.Outcome.APPROVED
                ? state.markPaid(id, "payment confirmed by reconciler")
                : state.markPaymentFailed(id, "payment declined (reconciler)");
        log.info("Order {}: reconciled to {}", id, settled.status());
        settlement.settle(settled);
    }

    private void guarded(long id, Runnable step) {
        try {
            step.run();
        } catch (DownstreamUnavailableException e) {
            log.info("Order {}: still waiting ({}); next tick", id, e.getMessage());
        } catch (OptimisticLockingFailureException | IllegalTransitionException e) {
            log.info("Order {}: moved by another writer; skipped", id);
        } catch (DownstreamRejectedException e) {
            log.error("Order {}: rejected while reconciling ({}); needs attention", id, e.getMessage());
        }
    }
}
