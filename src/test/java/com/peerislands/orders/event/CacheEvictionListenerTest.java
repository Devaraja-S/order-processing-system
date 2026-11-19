package com.peerislands.orders.event;

import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.service.OrderCache;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CacheEvictionListenerTest {

    private final OrderCache cache = mock(OrderCache.class);
    private final CacheEvictionListener listener = new CacheEvictionListener(cache);

    @Test
    void statusChangeEvictsThatOrder() {
        listener.onStatusChanged(new OrderStatusChangedEvent("o1", OrderStatus.PENDING, OrderStatus.CANCELLED,
                Instant.now()));

        verify(cache).evict(List.of("o1"));
    }

    @Test
    void schedulerBatchEvictsAllOrdersInTheBatch() {
        listener.onOrdersProcessed(new OrdersProcessedEvent(List.of("a", "b", "c"), Instant.now()));

        verify(cache).evict(List.of("a", "b", "c"));
    }
}
