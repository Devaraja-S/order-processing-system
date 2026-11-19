package com.peerislands.orders.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Activates {@code @Scheduled} triggers only when {@code orders.scheduler.enabled=true} (default). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "orders.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingEnabledConfig {
}
