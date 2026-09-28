package dev.lokesh.shop.order.service;

import dev.lokesh.shop.order.client.DownstreamRejectedException;
import dev.lokesh.shop.order.client.DownstreamUnavailableException;
import dev.lokesh.shop.order.client.InventoryClient;
import dev.lokesh.shop.order.domain.IllegalTransitionException;
import dev.lokesh.shop.order.domain.InventoryAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Pays the stock work an order owes (commit after PAID, release after a failure or cancel).
 * Used by checkout right after the status change, and by the reconciler for anything left over.
 * Both product-side calls are idempotent, so trying twice is always safe.
 */
@Component
public class InventorySettlement {

    private static final Logger log = LoggerFactory.getLogger(InventorySettlement.class);

    private final InventoryClient inventory;
    private final OrderStateService state;

    public InventorySettlement(InventoryClient inventory, OrderStateService state) {
        this.inventory = inventory;
        this.state = state;
    }

    /** Returns the order as it is afterwards. Never throws for downstream trouble. */
    public OrderView settle(OrderView order) {
        InventoryAction action = order.inventoryAction();
        if (!action.isPending()) {
            return order;
        }
        try {
            if (action == InventoryAction.COMMIT_PENDING) {
                inventory.commit(order.id());
            } else {
                inventory.release(order.id());
            }
            return finish(order, action, InventoryAction.DONE);
        } catch (DownstreamUnavailableException e) {
            log.warn("Order {}: {} not done yet ({}); the reconciler will retry", order.id(), action, e.getMessage());
            return order;
        } catch (DownstreamRejectedException e) {
            // A 4xx won't fix itself by retrying: stop, and make it loud (design D24).
            log.error("Order {}: {} REJECTED by product-service ({}). Marked FAILED; needs manual attention.",
                    order.id(), action, e.getMessage());
            return finish(order, action, InventoryAction.FAILED);
        }
    }

    private OrderView finish(OrderView order, InventoryAction attempted, InventoryAction outcome) {
        try {
            return state.finishInventoryAction(order.id(), attempted, outcome);
        } catch (OptimisticLockingFailureException | IllegalTransitionException raced) {
            return state.find(order.id()).orElse(order); // someone else moved it; they own the next step
        }
    }
}
