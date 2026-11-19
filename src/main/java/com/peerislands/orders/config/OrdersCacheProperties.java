package com.peerislands.orders.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Order cache configuration ({@code orders.cache.*}).
 *
 * @param orderTtl time-to-live of a cached GET /orders/{id} response
 */
@Validated
@ConfigurationProperties(prefix = "orders.cache")
public record OrdersCacheProperties(@NotNull Duration orderTtl) {

    @AssertTrue(message = "must be positive")
    public boolean isOrderTtlValid() {
        return orderTtl == null || orderTtl.isPositive();
    }
}
