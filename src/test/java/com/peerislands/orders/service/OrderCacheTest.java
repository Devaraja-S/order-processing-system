package com.peerislands.orders.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.peerislands.orders.config.OrdersCacheProperties;
import com.peerislands.orders.controller.dto.OrderItemResponse;
import com.peerislands.orders.controller.dto.OrderResponse;
import com.peerislands.orders.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCacheTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    private static final OrderResponse ORDER = new OrderResponse("o1", "cust-1",
            List.of(new OrderItemResponse("p1", "Pen", 2, new BigDecimal("1.25"))),
            new BigDecimal("2.50"), OrderStatus.PENDING,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final AtomicLong nanos = new AtomicLong(1_000);
    private OrderCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        cache = new OrderCache(redis, objectMapper, new OrdersCacheProperties(TTL), nanos::get);
    }

    private void passBackoff() {
        nanos.addAndGet(OrderCache.RETRY_BACKOFF.toNanos() + 1);
    }

    @Test
    void missReturnsEmpty() {
        when(values.get("orders:v1:o1")).thenReturn(null);

        assertThat(cache.get("o1")).isEmpty();
    }

    @Test
    void putStoresJsonWithTtlAndGetReadsItBack() throws Exception {
        cache.put(ORDER);

        verify(values).set(eq("orders:v1:o1"), anyString(), eq(TTL));
        when(values.get("orders:v1:o1")).thenReturn(objectMapper.writeValueAsString(ORDER));
        assertThat(cache.get("o1")).contains(ORDER);
    }

    @Test
    void evictDeletesAllKeys() {
        cache.evict(List.of("a", "b"));

        verify(redis).delete(List.of("orders:v1:a", "orders:v1:b"));
    }

    @Test
    void evictWithNoIdsDoesNothing() {
        cache.evict(List.of());

        verifyNoInteractions(redis);
    }

    @Test
    void unreadableEntryIsTreatedAsMissAndDeleted() {
        when(values.get("orders:v1:o1")).thenReturn("{not json");

        assertThat(cache.get("o1")).isEmpty();
        verify(redis).delete(List.of("orders:v1:o1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailuresAreSwallowed() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(any(Collection.class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("o1")).isEmpty();
        passBackoff();
        assertThatCode(() -> cache.put(ORDER)).doesNotThrowAnyException();
        passBackoff();
        assertThatCode(() -> cache.evict(List.of("o1"))).doesNotThrowAnyException();
    }

    @Test
    void afterAFailureRedisIsSkippedUntilTheBackoffElapses() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        cache.get("o1");                       // fails, starts the backoff

        cache.get("o2");                       // within the backoff: Redis is not called
        cache.put(ORDER);
        cache.evict(List.of("o3"));
        verify(redis, times(1)).opsForValue();

        passBackoff();
        cache.get("o4");                       // backoff over: Redis is tried again
        verify(redis, times(2)).opsForValue();
    }
}
