package com.peerislands.orders.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the Redis-backed features' settings: order cache and rate limiting. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OrdersCacheProperties.class, RateLimitProperties.class})
public class RedisFeaturesConfig {
}
