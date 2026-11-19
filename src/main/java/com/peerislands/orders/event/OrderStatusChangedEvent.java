package com.peerislands.orders.event;

import com.peerislands.orders.domain.OrderStatus;

import java.time.Instant;

/** Published after a single order's status was changed by a successful conditional update. */
public record OrderStatusChangedEvent(String orderId, OrderStatus from, OrderStatus to, Instant changedAt) {
}
