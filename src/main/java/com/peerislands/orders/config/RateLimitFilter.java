package com.peerislands.orders.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Rate limits {@code /api/v1/*} per client IP. Over the limit: 429 ProblemDetail with {@code Retry-After}.
 * If the limiter cannot reach Redis the request is allowed (fail open).
 *
 * <p>Client IP is {@link HttpServletRequest#getRemoteAddr()}; raw {@code X-Forwarded-For} is never trusted.
 * Behind a proxy, set {@code server.forward-headers-strategy} so the container resolves the real client.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    static final String API_PREFIX = "/api/v1/";

    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RedisRateLimiter.Decision decision;
        try {
            decision = rateLimiter.tryConsume(request.getRemoteAddr());
        } catch (RedisRateLimiter.UnavailableException e) {
            log.debug("Rate limiter unavailable; allowing request: {}", e.getMessage());
            chain.doFilter(request, response);
            return;
        } catch (RuntimeException e) {
            log.warn("Rate limiter failed ({}); allowing request", e.getClass().getSimpleName());
            chain.doFilter(request, response);
            return;
        }

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(request, response, decision.retryAfterSeconds());
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfter)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded; retry after " + retryAfter + " second(s)");
        problem.setTitle("Too many requests");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
