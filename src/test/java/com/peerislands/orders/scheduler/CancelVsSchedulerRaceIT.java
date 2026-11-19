package com.peerislands.orders.scheduler;

import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import com.peerislands.orders.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key race: a client cancels a PENDING order at the same moment the scheduler promotes it.
 * Exactly one must win; both winning (a cancelled order that is also PROCESSING) must be impossible.
 */
class CancelVsSchedulerRaceIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private PendingOrderProcessor processor;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanOrders() {
        mongoTemplate.remove(new Query(), Order.class);
    }

    private String insertPending() {
        OrderItem item = OrderItem.builder().productId("p1").productName("n").quantity(1)
                .unitPrice(new BigDecimal("1.00")).build();
        Instant createdAt = Instant.now().minusSeconds(1); // eligible with the default min-pending-age PT0S
        return mongoTemplate.insert(Order.builder()
                .customerId("cust-1").items(List.of(item)).totalAmount(new BigDecimal("1.00"))
                .status(OrderStatus.PENDING).createdAt(createdAt).updatedAt(createdAt).build()).getId();
    }

    @RepeatedTest(200)
    void cancelAndSchedulerExactlyOneWins() throws Exception {
        String id = insertPending();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> cancelWon = pool.submit(() -> {
                ready.countDown();
                start.await();
                // The scheduler needs two round trips (fetch ids, then update) vs one for cancel. A small random
                // delay spreads the outcomes so both "cancel wins" and "scheduler wins" are exercised, including
                // cancel landing between the scheduler's fetch and its update.
                LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(0, 2_000_000));
                try {
                    orderService.cancel(id);
                    return true;
                } catch (InvalidStatusTransitionException lost) {
                    return false;
                }
            });
            Future<Boolean> schedulerWon = pool.submit(() -> {
                ready.countDown();
                start.await();
                return processor.processPendingOrders() == 1;
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            boolean cancelled = cancelWon.get(30, TimeUnit.SECONDS);
            boolean promoted = schedulerWon.get(30, TimeUnit.SECONDS);

            assertThat(cancelled ^ promoted).as("exactly one wins (cancel=%s, scheduler=%s)", cancelled, promoted)
                    .isTrue();
            Order after = mongoTemplate.findById(id, Order.class);
            assertThat(after.getStatus()).isEqualTo(cancelled ? OrderStatus.CANCELLED : OrderStatus.PROCESSING);
            assertThat(after.getVersion()).isEqualTo(1L);
        }
    }
}
