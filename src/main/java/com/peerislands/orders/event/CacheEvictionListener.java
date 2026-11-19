package com.peerislands.orders.event;

import com.peerislands.orders.service.OrderCache;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** Observer: evicts cached orders whenever their status changes. Runs synchronously after the update. */
@Component
@RequiredArgsConstructor
public class CacheEvictionListener {

    private final OrderCache orderCache;

    @EventListener
    public void onStatusChanged(OrderStatusChangedEvent event) {
        orderCache.evict(List.of(event.orderId()));
    }

    @EventListener
    public void onOrdersProcessed(OrdersProcessedEvent event) {
        orderCache.evict(event.orderIds());
    }
}
