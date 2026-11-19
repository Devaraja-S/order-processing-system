package com.peerislands.orders.config;

import com.peerislands.orders.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting against real Redis with a tiny bucket (3 requests, no meaningful refill during the test).
 * Own context because the limit differs from the shared integration-test context.
 */
@SpringBootTest(properties = {
        "orders.scheduler.enabled=false",
        "orders.rate-limit.capacity=3",
        "orders.rate-limit.refill-tokens=3",
        "orders.rate-limit.refill-period=PT1H"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RateLimitIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearBuckets() {
        Set<String> keys = redis.keys(RedisRateLimiter.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static MockHttpServletRequestBuilder listFrom(String ip) {
        return get("/api/v1/orders").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @Test
    void fourthRequestWithinTheWindowIsRejectedWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(listFrom("203.0.113.1")).andExpect(status().isOk());
        }

        MvcResult rejected = mockMvc.perform(listFrom("203.0.113.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
        // 3 tokens per hour, refilled greedily: one token every 20 minutes.
        assertThat(retryAfter).isBetween(1L, 1200L);
    }

    @Test
    void eachClientIpHasItsOwnBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(listFrom("203.0.113.2")).andExpect(status().isOk());
        }
        mockMvc.perform(listFrom("203.0.113.2")).andExpect(status().isTooManyRequests());

        mockMvc.perform(listFrom("203.0.113.3")).andExpect(status().isOk());
    }

    @Test
    void forwardedForHeaderIsNotTrusted() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(listFrom("203.0.113.4")).andExpect(status().isOk());
        }

        // Spoofing a different client via X-Forwarded-For does not get a fresh bucket.
        mockMvc.perform(listFrom("203.0.113.4").header("X-Forwarded-For", "198.51.100.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void actuatorIsNotRateLimited() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(get("/actuator/health").with(request -> {
                request.setRemoteAddr("203.0.113.5");
                return request;
            })).andExpect(status().isOk());
        }
    }

    @Test
    void bucketIsStoredInRedisSoAllInstancesShareIt() throws Exception {
        mockMvc.perform(listFrom("203.0.113.6")).andExpect(status().isOk());

        assertThat(redis.hasKey(RedisRateLimiter.KEY_PREFIX + "203.0.113.6")).isTrue();
        assertThat(redis.getExpire(RedisRateLimiter.KEY_PREFIX + "203.0.113.6")).isPositive();
    }
}
