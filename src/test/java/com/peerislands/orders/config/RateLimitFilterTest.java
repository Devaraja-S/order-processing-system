package com.peerislands.orders.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private RateLimitFilter filter(boolean enabled) {
        return new RateLimitFilter(limiter, new RateLimitProperties(enabled, 100, 100, Duration.ofMinutes(1)),
                objectMapper);
    }

    private static MockHttpServletRequest request(String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void allowedRequestPassesThrough() throws Exception {
        when(limiter.tryConsume("10.0.0.1")).thenReturn(new RedisRateLimiter.Decision(true, 0));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request("/api/v1/orders", "10.0.0.1"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void exhaustedBucketReturns429ProblemWithRetryAfter() throws Exception {
        when(limiter.tryConsume("10.0.0.1")).thenReturn(new RedisRateLimiter.Decision(false, 7));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request("/api/v1/orders/abc", "10.0.0.1"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("7");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("status").asInt()).isEqualTo(429);
        assertThat(body.path("title").asText()).isEqualTo("Too many requests");
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/orders/abc");
    }

    @Test
    void redisFailureFailsOpen() throws Exception {
        when(limiter.tryConsume(anyString())).thenThrow(new RuntimeException("connection refused"));
        MockFilterChain chain = new MockFilterChain();

        filter(true).doFilter(request("/api/v1/orders", "10.0.0.1"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void limiterBackingOffFailsOpen() throws Exception {
        when(limiter.tryConsume(anyString()))
                .thenThrow(new RedisRateLimiter.UnavailableException("backing off", null));
        MockFilterChain chain = new MockFilterChain();

        filter(true).doFilter(request("/api/v1/orders", "10.0.0.1"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void nonApiPathsAreNotLimited() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter(true).doFilter(request("/actuator/health", "10.0.0.1"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(limiter);
    }

    @Test
    void disabledFilterDoesNotLimit() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter(false).doFilter(request("/api/v1/orders", "10.0.0.1"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(limiter);
    }
}
