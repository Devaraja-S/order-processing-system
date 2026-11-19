package com.peerislands.orders.exception;

import lombok.Getter;

@Getter
public class OrderNotFoundException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super("Order " + orderId + " not found");
        this.orderId = orderId;
    }
}
