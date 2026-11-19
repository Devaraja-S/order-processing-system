package com.peerislands.orders.repository;

import com.peerislands.orders.domain.Order;

import java.time.Instant;

/** Keyset position in the newest-first order list: the (createdAt, id) of the last returned order. */
public record OrderCursor(Instant createdAt, String id) {

    public static OrderCursor of(Order order) {
        return new OrderCursor(order.getCreatedAt(), order.getId());
    }
}
