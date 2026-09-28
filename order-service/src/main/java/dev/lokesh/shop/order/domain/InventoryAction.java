package dev.lokesh.shop.order.domain;

/**
 * Stock work owed to product-service once an order reaches a final state. Written in the same
 * transaction as the status change, then attempted; if the call fails, the reconciler retries
 * until it is DONE (success) or FAILED (a 4xx that retrying can't fix; logged loudly, design D24).
 */
public enum InventoryAction {
    NONE, RELEASE_PENDING, COMMIT_PENDING, DONE, FAILED;

    public boolean isPending() {
        return this == RELEASE_PENDING || this == COMMIT_PENDING;
    }
}
