package com.peerislands.orders.scheduler;

import com.peerislands.orders.event.OrdersProcessedEvent;
import com.peerislands.orders.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Moves PENDING orders older than {@code minPendingAge} to PROCESSING in batches.
 *
 * <p>Idempotent and resumable: progress lives only in MongoDB, and every batch update is conditional on
 * {@code status: PENDING}, so a re-run (or a run after a crash) only touches orders still PENDING, and an
 * order cancelled concurrently is never promoted. Terminates because each batch shrinks the set of
 * eligible orders and the cutoff is fixed for the run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderProcessor {

    private final OrderRepository orderRepository;
    private final SchedulerProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** @return number of orders moved to PROCESSING */
    public long processPendingOrders() {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Instant cutoff = now.minus(properties.minPendingAge());
        long total = 0;
        int batches = 0;

        while (true) {
            List<String> ids = orderRepository.findPendingIdsCreatedAtOrBefore(cutoff, properties.batchSize());
            if (ids.isEmpty()) {
                break;
            }
            long modified = orderRepository.markProcessing(ids, now);
            total += modified;
            batches++;
            eventPublisher.publishEvent(new OrdersProcessedEvent(ids, now));
            log.debug("Batch {}: fetched {} pending orders, moved {} to PROCESSING", batches, ids.size(), modified);
        }

        if (total > 0) {
            log.info("Moved {} PENDING orders to PROCESSING in {} batch(es) (cutoff {})", total, batches, cutoff);
        }
        return total;
    }
}
