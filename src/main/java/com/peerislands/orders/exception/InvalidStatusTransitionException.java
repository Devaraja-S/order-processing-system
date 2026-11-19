package com.peerislands.orders.exception;

import com.peerislands.orders.domain.OrderStatus;
import lombok.Getter;

/** The order exists but is not in the status required for the requested change (409). */
@Getter
public class InvalidStatusTransitionException extends RuntimeException {

    private final String orderId;
    private final OrderStatus currentStatus;
    private final OrderStatus targetStatus;

    public InvalidStatusTransitionException(String orderId, OrderStatus currentStatus, OrderStatus targetStatus) {
        super(message(orderId, currentStatus, targetStatus));
        this.orderId = orderId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    private static String message(String orderId, OrderStatus current, OrderStatus target) {
        if (target == OrderStatus.CANCELLED) {
            return "Order " + orderId + " is " + current + " and cannot be cancelled; only PENDING orders can be cancelled";
        }
        return "Order " + orderId + " is " + current + "; cannot change status to " + target;
    }
}
