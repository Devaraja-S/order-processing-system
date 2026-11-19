package com.peerislands.orders.scheduler;

import com.peerislands.orders.event.OrdersProcessedEvent;
import com.peerislands.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingOrderProcessorTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00.123456Z");
    private static final Instant NOW_MILLIS = Instant.parse("2026-06-01T12:00:00.123Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PendingOrderProcessor processor;

    @BeforeEach
    void setUp() {
        SchedulerProperties props = new SchedulerProperties(true, "0 */5 * * * *", Duration.ofMinutes(5), 2,
                Duration.ofMinutes(4));
        processor = new PendingOrderProcessor(orderRepository, props, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void usesCutoffOfNowMinusMinPendingAgeAndLoopsUntilEmpty() {
        Instant cutoff = NOW_MILLIS.minus(Duration.ofMinutes(5));
        when(orderRepository.findPendingIdsCreatedAtOrBefore(cutoff, 2))
                .thenReturn(List.of("a", "b"), List.of("c"), List.of());
        when(orderRepository.markProcessing(List.of("a", "b"), NOW_MILLIS)).thenReturn(2L);
        when(orderRepository.markProcessing(List.of("c"), NOW_MILLIS)).thenReturn(1L);

        long moved = processor.processPendingOrders();

        assertThat(moved).isEqualTo(3);
        InOrder order = inOrder(orderRepository, eventPublisher);
        order.verify(orderRepository).markProcessing(List.of("a", "b"), NOW_MILLIS);
        order.verify(eventPublisher).publishEvent(new OrdersProcessedEvent(List.of("a", "b"), NOW_MILLIS));
        order.verify(orderRepository).markProcessing(List.of("c"), NOW_MILLIS);
        order.verify(eventPublisher).publishEvent(new OrdersProcessedEvent(List.of("c"), NOW_MILLIS));
    }

    @Test
    void nothingPendingMeansNoUpdateAndNoEvent() {
        when(orderRepository.findPendingIdsCreatedAtOrBefore(any(), any(Integer.class))).thenReturn(List.of());

        assertThat(processor.processPendingOrders()).isZero();

        verify(orderRepository, never()).markProcessing(anyList(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void countsOnlyActuallyModifiedOrders() {
        when(orderRepository.findPendingIdsCreatedAtOrBefore(any(), any(Integer.class)))
                .thenReturn(List.of("a", "b"), List.of());
        // "b" was cancelled between fetch and update
        when(orderRepository.markProcessing(List.of("a", "b"), NOW_MILLIS)).thenReturn(1L);

        assertThat(processor.processPendingOrders()).isEqualTo(1);
        verify(eventPublisher).publishEvent(new OrdersProcessedEvent(List.of("a", "b"), NOW_MILLIS));
    }
}
