package com.peerislands.orders.scheduler;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Scheduler configuration ({@code orders.scheduler.*}). Every value can be overridden via environment
 * variables, e.g. {@code ORDERS_SCHEDULER_BATCH_SIZE}. Invalid values fail startup.
 *
 * @param enabled       whether the cron trigger fires (the scheduler bean exists either way)
 * @param cron          Spring cron expression for the job
 * @param minPendingAge minimum age before a PENDING order is promoted (PT0S = literal requirement)
 * @param batchSize     order IDs fetched and updated per batch
 * @param lockAtMostFor ShedLock upper bound on how long a crashed node can hold the lock
 */
@Validated
@ConfigurationProperties(prefix = "orders.scheduler")
public record SchedulerProperties(
        boolean enabled,
        @NotBlank String cron,
        @NotNull Duration minPendingAge,
        @Min(1) @Max(10_000) int batchSize,
        @NotNull Duration lockAtMostFor) {

    @AssertTrue(message = "must be a valid Spring cron expression")
    public boolean isCronValid() {
        return cron == null || CronExpression.isValidExpression(cron);
    }

    @AssertTrue(message = "must not be negative")
    public boolean isMinPendingAgeValid() {
        return minPendingAge == null || !minPendingAge.isNegative();
    }

    @AssertTrue(message = "must be positive")
    public boolean isLockAtMostForValid() {
        return lockAtMostFor == null || lockAtMostFor.isPositive();
    }
}
