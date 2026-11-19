package com.peerislands.orders.event;

import java.time.Instant;
import java.util.List;

/**
 * Published by the scheduler after each batch update PENDING -> PROCESSING. Contains every ID fetched for
 * the batch; an order cancelled between fetch and update is included too (a harmless extra cache eviction).
 */
public record OrdersProcessedEvent(List<String> orderIds, Instant changedAt) {

    public OrdersProcessedEvent {
        orderIds = List.copyOf(orderIds);
    }
}
