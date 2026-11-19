package com.peerislands.orders.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin trigger for {@link PendingOrderProcessor}. ShedLock ensures only one instance runs it at a time.
 * The cron trigger fires only when {@code orders.scheduler.enabled=true}
 * (see {@code SchedulingEnabledConfig}); the bean itself always exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusScheduler {

    public static final String LOCK_NAME = "pendingOrdersToProcessing";

    private final PendingOrderProcessor processor;

    @Scheduled(cron = "${orders.scheduler.cron}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "${orders.scheduler.lock-at-most-for}")
    public void promotePendingOrders() {
        try {
            processor.processPendingOrders();
        } catch (RuntimeException e) {
            // Nothing is lost: the next run picks up whatever is still PENDING.
            log.error("Pending-order job failed; it will be retried on the next run", e);
        }
    }
}
