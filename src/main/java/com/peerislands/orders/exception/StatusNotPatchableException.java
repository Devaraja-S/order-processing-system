package com.peerislands.orders.exception;

import com.peerislands.orders.domain.OrderStatus;

/** PATCH /status target that is never valid on that endpoint: PENDING or CANCELLED (400). */
public class StatusNotPatchableException extends RuntimeException {

    public StatusNotPatchableException(String orderId, OrderStatus target) {
        super(message(orderId, target));
    }

    private static String message(String orderId, OrderStatus target) {
        if (target == OrderStatus.CANCELLED) {
            return "Status CANCELLED cannot be set via PATCH; use POST /api/v1/orders/" + orderId + "/cancel";
        }
        return "Status " + target + " cannot be set via PATCH; allowed targets are PROCESSING, SHIPPED, DELIVERED";
    }
}
