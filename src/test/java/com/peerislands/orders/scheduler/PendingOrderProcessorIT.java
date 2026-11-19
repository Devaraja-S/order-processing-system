package com.peerislands.orders.scheduler;

import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.event.OrdersProcessedEvent;
import com.peerislands.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Processor against real MongoDB. Built by hand with its own properties and a fixed clock so each test
 * controls min-pending-age and batch size without needing another Spring context.
 */
class PendingOrderProcessorIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final List<OrdersProcessedEvent> events = new ArrayList<>();

    @BeforeEach
    void cleanOrders() {
        mongoTemplate.remove(new Query(), Order.class);
        events.clear();
    }

    private PendingOrderProcessor processor(Duration minPendingAge, int batchSize) {
        SchedulerProperties props = new SchedulerProperties(true, "0 */5 * * * *", minPendingAge, batchSize,
                Duration.ofMinutes(4));
        return new PendingOrderProcessor(orderRepository, props,
                event -> events.add((OrdersProcessedEvent) event), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Order insert(OrderStatus status, Instant createdAt) {
        OrderItem item = OrderItem.builder().productId("p1").productName("n").quantity(1)
                .unitPrice(new BigDecimal("1.00")).build();
        return mongoTemplate.insert(Order.builder()
                .customerId("cust-1").items(List.of(item)).totalAmount(new BigDecimal("1.00"))
                .status(status).createdAt(createdAt).updatedAt(createdAt).build());
    }

    private Order reload(Order order) {
        return mongoTemplate.findById(order.getId(), Order.class);
    }

    @Test
    void promotesPendingOrdersAndSetsUpdatedAtAndVersion() {
        Order a = insert(OrderStatus.PENDING, NOW.minusSeconds(30));
        Order b = insert(OrderStatus.PENDING, NOW.minusSeconds(10));

        long moved = processor(Duration.ZERO, 1000).processPendingOrders();

        assertThat(moved).isEqualTo(2);
        for (Order order : List.of(a, b)) {
            Order after = reload(order);
            assertThat(after.getStatus()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(after.getVersion()).isEqualTo(1L);
            assertThat(after.getUpdatedAt()).isEqualTo(NOW);
        }
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.orderIds()).containsExactly(a.getId(), b.getId()); // oldest first
            assertThat(e.changedAt()).isEqualTo(NOW);
        });
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"})
    void nonPendingOrdersAreUntouched(OrderStatus status) {
        Order order = insert(status, NOW.minusSeconds(60));

        assertThat(processor(Duration.ZERO, 1000).processPendingOrders()).isZero();

        Order after = reload(order);
        assertThat(after.getStatus()).isEqualTo(status);
        assertThat(after.getVersion()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void respectsMinPendingAgeWithInclusiveBoundary() {
        Order sixMinutesOld = insert(OrderStatus.PENDING, NOW.minus(Duration.ofMinutes(6)));
        Order exactlyFive = insert(OrderStatus.PENDING, NOW.minus(Duration.ofMinutes(5)));
        Order fourMinutesOld = insert(OrderStatus.PENDING, NOW.minus(Duration.ofMinutes(4)));

        long moved = processor(Duration.ofMinutes(5), 1000).processPendingOrders();

        assertThat(moved).isEqualTo(2);
        assertThat(reload(sixMinutesOld).getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(reload(exactlyFive).getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(reload(fourMinutesOld).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void minPendingAgeZeroPromotesEvenBrandNewOrders() {
        Order justCreated = insert(OrderStatus.PENDING, NOW);

        processor(Duration.ZERO, 1000).processPendingOrders();

        assertThat(reload(justCreated).getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    void processesInBatchesOldestFirst() {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            orders.add(insert(OrderStatus.PENDING, NOW.minusSeconds(100 - i))); // index 0 is oldest
        }

        long moved = processor(Duration.ZERO, 10).processPendingOrders();

        assertThat(moved).isEqualTo(25);
        assertThat(events).extracting(e -> e.orderIds().size()).containsExactly(10, 10, 5);
        assertThat(events.getFirst().orderIds())
                .containsExactlyElementsOf(orders.subList(0, 10).stream().map(Order::getId).toList());
        assertThat(mongoTemplate.findAll(Order.class)).allSatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(o.getVersion()).isEqualTo(1L);
        });
    }

    @Test
    void reRunIsANoOp() {
        insert(OrderStatus.PENDING, NOW.minusSeconds(5));
        PendingOrderProcessor processor = processor(Duration.ZERO, 1000);
        processor.processPendingOrders();
        events.clear();

        assertThat(processor.processPendingOrders()).isZero();

        assertThat(events).isEmpty();
        assertThat(mongoTemplate.findAll(Order.class)).allSatisfy(o -> assertThat(o.getVersion()).isEqualTo(1L));
    }

    @Test
    void resumesAfterSimulatedCrashMidRun() {
        // State left by a run that crashed after its first batch: some already PROCESSING, rest PENDING.
        List<Order> alreadyDone = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            alreadyDone.add(insert(OrderStatus.PENDING, NOW.minusSeconds(60 - i)));
        }
        orderRepository.markProcessing(alreadyDone.stream().map(Order::getId).toList(), NOW.minusSeconds(1));
        List<Order> remaining = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            remaining.add(insert(OrderStatus.PENDING, NOW.minusSeconds(30 - i)));
        }

        long moved = processor(Duration.ZERO, 2).processPendingOrders();

        assertThat(moved).isEqualTo(4);
        alreadyDone.forEach(o -> {
            Order after = reload(o);
            assertThat(after.getVersion()).isEqualTo(1L);                 // not touched again
            assertThat(after.getUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
        });
        remaining.forEach(o -> assertThat(reload(o).getStatus()).isEqualTo(OrderStatus.PROCESSING));
    }

    @Test
    void emptyCollectionDoesNothing() {
        assertThat(processor(Duration.ZERO, 1000).processPendingOrders()).isZero();
        assertThat(events).isEmpty();
    }
}
