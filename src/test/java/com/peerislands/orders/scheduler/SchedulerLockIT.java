package com.peerislands.orders.scheduler;

import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/** ShedLock around the real scheduler bean (scheduling itself is disabled in tests). */
class SchedulerLockIT extends AbstractIntegrationTest {

    private static final String SHEDLOCK_COLLECTION = "shedLock";

    @Autowired
    private OrderStatusScheduler scheduler;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void clean() {
        mongoTemplate.remove(new Query(), Order.class);
        mongoTemplate.getCollection(SHEDLOCK_COLLECTION).deleteMany(new Document());
    }

    private Order insertPending() {
        OrderItem item = OrderItem.builder().productId("p1").productName("n").quantity(1)
                .unitPrice(new BigDecimal("1.00")).build();
        Instant createdAt = Instant.now().minusSeconds(5);
        return mongoTemplate.insert(Order.builder()
                .customerId("cust-1").items(List.of(item)).totalAmount(new BigDecimal("1.00"))
                .status(OrderStatus.PENDING).createdAt(createdAt).updatedAt(createdAt).build());
    }

    @Test
    void schedulingIsNotActiveInTests() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    void schedulerIsWrappedByShedLock() {
        assertThat(AopUtils.isAopProxy(scheduler)).isTrue();
    }

    @Test
    void runsWhenLockIsFreeAndRecordsTheLock() {
        Order order = insertPending();

        scheduler.promotePendingOrders();

        assertThat(mongoTemplate.findById(order.getId(), Order.class).getStatus()).isEqualTo(OrderStatus.PROCESSING);
        Document lock = mongoTemplate.getCollection(SHEDLOCK_COLLECTION)
                .find(eq("_id", OrderStatusScheduler.LOCK_NAME)).first();
        assertThat(lock).isNotNull();
        assertThat(lock.getString("lockedBy")).isNotBlank();
        assertThat(lock.getDate("lockUntil")).isBeforeOrEqualTo(new Date()); // released after the run
    }

    @Test
    void skipsWhenAnotherInstanceHoldsTheLock() {
        Order order = insertPending();
        Instant now = Instant.now();
        mongoTemplate.getCollection(SHEDLOCK_COLLECTION).insertOne(new Document("_id", OrderStatusScheduler.LOCK_NAME)
                .append("lockUntil", Date.from(now.plus(Duration.ofMinutes(10))))
                .append("lockedAt", Date.from(now))
                .append("lockedBy", "other-node"));

        scheduler.promotePendingOrders();

        assertThat(mongoTemplate.findById(order.getId(), Order.class).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void runsAgainOnceAnExpiredLockIsLeftBehindByACrashedInstance() {
        Order order = insertPending();
        Instant now = Instant.now();
        mongoTemplate.getCollection(SHEDLOCK_COLLECTION).insertOne(new Document("_id", OrderStatusScheduler.LOCK_NAME)
                .append("lockUntil", Date.from(now.minusSeconds(1)))   // lock-at-most-for elapsed
                .append("lockedAt", Date.from(now.minus(Duration.ofMinutes(4))))
                .append("lockedBy", "crashed-node"));

        scheduler.promotePendingOrders();

        assertThat(mongoTemplate.findById(order.getId(), Order.class).getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }
}
