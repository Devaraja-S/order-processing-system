package com.peerislands.orders.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Per-client token buckets stored in Redis (Bucket4j, compare-and-swap), shared by all instances.
 * Key {@code rate-limit:v1:{clientId}}; keys expire once the bucket would be full again.
 *
 * <p>Connects lazily, so the application starts even if Redis is down. After a failed connection attempt
 * or a failed Redis call it logs one warning and waits {@link #CONNECT_RETRY_BACKOFF} before using Redis
 * again; meanwhile {@link #tryConsume} throws {@link UnavailableException} immediately and the caller fails
 * open. Once connected, Lettuce reconnects automatically and rejects commands while disconnected instead of
 * queueing them.
 */
@Slf4j
@Component
public class RedisRateLimiter implements DisposableBean {

    static final String KEY_PREFIX = "rate-limit:v1:";
    static final Duration CONNECT_RETRY_BACKOFF = Duration.ofSeconds(5);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

    /** Outcome of one consume attempt. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    /** The limiter cannot reach Redis right now (callers fail open). */
    public static class UnavailableException extends RuntimeException {
        UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RedisClient client;
    private final BucketConfiguration bucketConfiguration;
    private volatile StatefulRedisConnection<byte[], byte[]> connection;
    private volatile ProxyManager<byte[]> proxyManager;
    private long nextConnectAttemptNanos;
    private boolean connectFailed;
    private volatile long unavailableUntilNanos = System.nanoTime();

    public RedisRateLimiter(RedisConnectionDetails connectionDetails, RedisProperties redisProperties,
                            RateLimitProperties properties) {
        this.client = RedisClient.create(redisUri(connectionDetails, redisProperties));
        Duration connectTimeout = redisProperties.getConnectTimeout() != null
                ? redisProperties.getConnectTimeout() : DEFAULT_TIMEOUT;
        this.client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .build());
        this.bucketConfiguration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.capacity())
                        .refillGreedy(properties.refillTokens(), properties.refillPeriod())
                        .build())
                .build();
    }

    private static RedisURI redisUri(RedisConnectionDetails details, RedisProperties redisProperties) {
        RedisConnectionDetails.Standalone standalone = details.getStandalone();
        if (standalone == null) {
            throw new IllegalStateException("Rate limiting supports standalone Redis only");
        }
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withTimeout(redisProperties.getTimeout() != null ? redisProperties.getTimeout() : DEFAULT_TIMEOUT);
        if (details.getPassword() != null) {
            if (details.getUsername() != null) {
                uri.withAuthentication(details.getUsername(), details.getPassword().toCharArray());
            } else {
                uri.withPassword(details.getPassword().toCharArray());
            }
        }
        return uri.build();
    }

    /**
     * Takes one token from the client's bucket.
     *
     * @throws RuntimeException if Redis is unavailable (callers should fail open)
     */
    public Decision tryConsume(String clientId) {
        if (System.nanoTime() - unavailableUntilNanos < 0) {
            throw new UnavailableException("Redis recently failed; waiting before retrying", null);
        }
        byte[] key = (KEY_PREFIX + clientId).getBytes(StandardCharsets.UTF_8);
        ConsumptionProbe probe;
        try {
            probe = proxyManager().builder()
                    .build(key, () -> bucketConfiguration)
                    .tryConsumeAndReturnRemaining(1);
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // Connected before, Redis failing now: log once, then skip Redis for the backoff window.
            unavailableUntilNanos = System.nanoTime() + CONNECT_RETRY_BACKOFF.toNanos();
            log.warn("Rate limiter Redis call failed ({}); requests are NOT rate limited, retrying in {}",
                    e.getClass().getSimpleName(), CONNECT_RETRY_BACKOFF);
            throw new UnavailableException("Redis call failed", e);
        }
        if (probe.isConsumed()) {
            return new Decision(true, 0);
        }
        long seconds = (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L;
        return new Decision(false, Math.max(1, seconds));
    }

    private ProxyManager<byte[]> proxyManager() {
        ProxyManager<byte[]> current = proxyManager;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (proxyManager != null) {
                return proxyManager;
            }
            long now = System.nanoTime();
            if (connectFailed && now - nextConnectAttemptNanos < 0) {
                throw new UnavailableException("Redis unavailable; waiting before reconnecting", null);
            }
            try {
                connection = client.connect(ByteArrayCodec.INSTANCE);
                proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                        .expirationAfterWrite(ExpirationAfterWriteStrategy
                                .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                        .build();
                connectFailed = false;
                log.info("Rate limiter connected to Redis");
                return proxyManager;
            } catch (RuntimeException e) {
                connectFailed = true;
                nextConnectAttemptNanos = now + CONNECT_RETRY_BACKOFF.toNanos();
                log.warn("Rate limiter could not connect to Redis ({}); requests are NOT rate limited, retrying in {}",
                        e.getClass().getSimpleName(), CONNECT_RETRY_BACKOFF);
                throw new UnavailableException("Could not connect to Redis", e);
            }
        }
    }

    @Override
    public void destroy() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
    }
}
