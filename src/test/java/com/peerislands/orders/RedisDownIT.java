package com.peerislands.orders;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis points at a port where nothing listens. MongoDB is the source of truth, so the API must keep
 * working: the cache falls back to MongoDB and the rate limiter fails open (with a 1-request limit, any
 * second request would otherwise be 429).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "orders.scheduler.enabled=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=1",
        "orders.rate-limit.capacity=1",
        "orders.rate-limit.refill-tokens=1",
        "orders.rate-limit.refill-period=PT1H"
})
@Import(MongoOnlyTestcontainersConfig.class)
class RedisDownIT {

    private static final Duration FAST = Duration.ofSeconds(3);

    @Autowired
    private TestRestTemplate rest;

    private static <T> T timed(Supplier<T> call) {
        long start = System.nanoTime();
        T result = call.get();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(FAST);
        return result;
    }

    @Test
    void apiKeepsWorkingWithoutRedis() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"customerId": "c1", "items": [{"productId": "p1", "productName": "Pen", "quantity": 1, "unitPrice": 2.00}]}
                """;

        ResponseEntity<JsonNode> created = timed(() -> rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().path("id").asText();

        for (int i = 0; i < 2; i++) {
            ResponseEntity<JsonNode> got = timed(() -> rest.getForEntity("/api/v1/orders/" + id, JsonNode.class));
            assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(got.getBody().path("status").asText()).isEqualTo("PENDING");
        }

        ResponseEntity<JsonNode> list = timed(() -> rest.getForEntity("/api/v1/orders", JsonNode.class));
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> cancelled = timed(() ->
                rest.postForEntity("/api/v1/orders/" + id + "/cancel", null, JsonNode.class));
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/v1/orders/" + id, JsonNode.class).getBody().path("status").asText())
                .isEqualTo("CANCELLED");
    }
}
