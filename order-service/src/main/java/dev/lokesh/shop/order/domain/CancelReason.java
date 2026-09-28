package dev.lokesh.shop.order.domain;

/** Why a CANCELLED order was cancelled. None of these ever took money. */
public enum CancelReason {
    /** product-service said there is not enough stock (409). */
    OUT_OF_STOCK,
    /** product-service did not recognise a product (422). */
    INVALID_ITEMS,
    /** product-service could not be reached, so no stock is held (maybe: release just in case). */
    INVENTORY_UNAVAILABLE,
    /** Stuck in CREATED for over 60 s (e.g. the service crashed mid-checkout); reclaimed by the reconciler. */
    TIMED_OUT
}
