package com.peerislands.orders.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Per-client-IP rate limit ({@code orders.rate-limit.*}): a bucket of {@code capacity} tokens, refilled
 * greedily with {@code refillTokens} per {@code refillPeriod}.
 */
@Validated
@ConfigurationProperties(prefix = "orders.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(1) long capacity,
        @Min(1) long refillTokens,
        @NotNull Duration refillPeriod) {

    @AssertTrue(message = "must be positive")
    public boolean isRefillPeriodValid() {
        return refillPeriod == null || refillPeriod.isPositive();
    }
}
