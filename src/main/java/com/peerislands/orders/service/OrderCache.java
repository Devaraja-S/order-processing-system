package com.peerislands.orders.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peerislands.orders.config.OrdersCacheProperties;
import com.peerislands.orders.controller.dto.OrderResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Cache-aside store for GET /orders/{id} responses in Redis (key {@code orders:v1:{id}}, TTL from
 * {@code orders.cache.order-ttl}). MongoDB stays the source of truth.
 *
 * <p>Redis problems never break a request: every operation catches errors, logs a warning and carries
 * on (a failed get is a cache miss). After a failure Redis is skipped for {@link #RETRY_BACKOFF}, so an
 * outage costs at most one Redis timeout per backoff window instead of one per request.
 */
@Slf4j
@Component
public class OrderCache {

    static final String KEY_PREFIX = "orders:v1:";
    static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final LongSupplier nanoTime;
    private volatile long skipUntilNanos;
    private volatile boolean skipping;

    @Autowired
    public OrderCache(StringRedisTemplate redis, ObjectMapper objectMapper, OrdersCacheProperties properties) {
        this(redis, objectMapper, properties, System::nanoTime);
    }

    OrderCache(StringRedisTemplate redis, ObjectMapper objectMapper, OrdersCacheProperties properties,
               LongSupplier nanoTime) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = properties.orderTtl();
        this.nanoTime = nanoTime;
    }

    static String key(String orderId) {
        return KEY_PREFIX + orderId;
    }

    public Optional<OrderResponse> get(String orderId) {
        if (backingOff()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(orderId));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, OrderResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("Discarding unreadable cache entry for order {}: {}", orderId, e.getOriginalMessage());
            evict(List.of(orderId));
            return Optional.empty();
        } catch (RuntimeException e) {
            onRedisFailure("get", e);
            return Optional.empty();
        }
    }

    public void put(OrderResponse order) {
        if (backingOff()) {
            return;
        }
        try {
            redis.opsForValue().set(key(order.id()), objectMapper.writeValueAsString(order), ttl);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize order {} for caching: {}", order.id(), e.getOriginalMessage());
        } catch (RuntimeException e) {
            onRedisFailure("put", e);
        }
    }

    public void evict(Collection<String> orderIds) {
        if (orderIds.isEmpty() || backingOff()) {
            return;
        }
        try {
            redis.delete(orderIds.stream().map(OrderCache::key).toList());
        } catch (RuntimeException e) {
            // Missed eviction: the stale entry expires within the TTL (documented in docs/DESIGN.md §9).
            onRedisFailure("evict", e);
        }
    }

    private boolean backingOff() {
        return skipping && nanoTime.getAsLong() - skipUntilNanos < 0;
    }

    private void onRedisFailure(String operation, RuntimeException e) {
        skipUntilNanos = nanoTime.getAsLong() + RETRY_BACKOFF.toNanos();
        skipping = true;
        log.warn("Redis cache {} failed ({}); serving from MongoDB, skipping Redis for {}",
                operation, e.getClass().getSimpleName(), RETRY_BACKOFF);
    }
}
