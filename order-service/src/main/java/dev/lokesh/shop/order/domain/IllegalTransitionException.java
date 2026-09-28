package dev.lokesh.shop.order.domain;

/** A write lost a race: another writer already moved the order somewhere this move can't follow. */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(Long orderId, OrderStatus from, OrderStatus to) {
        super("Order " + orderId + " can't go from " + from + " to " + to + ".");
    }
}
