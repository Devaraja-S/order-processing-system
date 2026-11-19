package com.peerislands.orders.service;

import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent status changes on one order: the conditional atomic update guarantees exactly one winner.
 * Races the service directly (no HTTP) so the calls genuinely overlap in MongoDB.
 */
class StatusConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanOrders() {
        mongoTemplate.remove(new Query(), Order.class);
    }

    private Order insertPending() {
        OrderItem item = OrderItem.builder().productId("p1").productName("n").quantity(1)
                .unitPrice(new BigDecimal("1.00")).build();
        Instant now = Instant.now();
        return mongoTemplate.insert(Order.builder()
                .customerId("cust-1").items(List.of(item)).totalAmount(new BigDecimal("1.00"))
                .status(OrderStatus.PENDING).createdAt(now).updatedAt(now).build());
    }

    /** Runs all tasks released at the same instant; returns which ones succeeded (true) or lost (false). */
    private static List<Boolean> race(List<Callable<?>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        task.call();
                        return true;
                    } catch (InvalidStatusTransitionException lost) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @RepeatedTest(50)
    void cancelVersusPatchToProcessingHasExactlyOneWinner() throws Exception {
        String id = insertPending().getId();

        List<Boolean> results = race(List.of(
                () -> orderService.cancel(id),
                () -> orderService.updateStatus(id, OrderStatus.PROCESSING)));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        Order after = mongoTemplate.findById(id, Order.class);
        OrderStatus expected = results.get(0) ? OrderStatus.CANCELLED : OrderStatus.PROCESSING;
        assertThat(after.getStatus()).isEqualTo(expected);
        assertThat(after.getVersion()).isEqualTo(1L);
    }

    @RepeatedTest(20)
    void manyConcurrentCancelsHaveExactlyOneWinner() throws Exception {
        String id = insertPending().getId();
        List<Callable<?>> cancels = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cancels.add(() -> orderService.cancel(id));
        }

        List<Boolean> results = race(cancels);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        Order after = mongoTemplate.findById(id, Order.class);
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.getVersion()).isEqualTo(1L);
    }
}
