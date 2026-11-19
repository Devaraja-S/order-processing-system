package com.peerislands.orders.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.scheduler.PendingOrderProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Cache-aside on GET /orders/{id} against real Redis, and eviction on every kind of status change. */
class OrderCacheIT extends AbstractIntegrationTest {

    private static final String BODY = """
            {"customerId": "cust-1", "items": [{"productId": "p1", "productName": "Pen", "quantity": 2, "unitPrice": 1.25}]}
            """;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private PendingOrderProcessor processor;

    @BeforeEach
    void clean() {
        mongoTemplate.remove(new Query(), Order.class);
        Set<String> keys = redis.keys(OrderCache.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String create() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(BODY, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asText();
    }

    private JsonNode get(String id) {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/orders/" + id, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private boolean cached(String id) {
        return Boolean.TRUE.equals(redis.hasKey(OrderCache.key(id)));
    }

    /** Changes the order in MongoDB without any event, so only a cache hit can still show the old value. */
    private void changeCustomerBehindTheCachesBack(String id) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("customerId", "changed-directly"), Order.class);
    }

    @Test
    void createDoesNotPopulateTheCache() {
        assertThat(cached(create())).isFalse();
    }

    @Test
    void firstGetPopulatesCacheWithConfiguredTtl() {
        String id = create();

        get(id);

        assertThat(cached(id)).isTrue();
        Long ttl = redis.getExpire(OrderCache.key(id), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void secondGetIsServedFromCache() {
        String id = create();
        get(id);

        changeCustomerBehindTheCachesBack(id);

        assertThat(get(id).path("customerId").asText()).isEqualTo("cust-1");
    }

    @Test
    void cancelEvictsSoNextGetIsFresh() {
        String id = create();
        get(id);

        assertThat(rest.postForEntity("/api/v1/orders/" + id + "/cancel", null, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(cached(id)).isFalse();
        assertThat(get(id).path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void patchEvictsSoNextGetIsFresh() {
        String id = create();
        get(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        rest.exchange("/api/v1/orders/" + id + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\": \"PROCESSING\"}", headers), JsonNode.class);

        assertThat(cached(id)).isFalse();
        assertThat(get(id).path("status").asText()).isEqualTo("PROCESSING");
    }

    @Test
    void schedulerBatchEvictsSoNextGetIsFresh() {
        String id = create();
        get(id);

        processor.processPendingOrders();

        assertThat(cached(id)).isFalse();
        assertThat(get(id).path("status").asText()).isEqualTo("PROCESSING");
    }

    @Test
    void failedCancelLeavesCacheUntouched() {
        String id = create();
        processor.processPendingOrders();          // now PROCESSING
        get(id);                                    // cached as PROCESSING

        assertThat(rest.postForEntity("/api/v1/orders/" + id + "/cancel", null, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(cached(id)).isTrue();
    }

    @Test
    void notFoundIsNotCached() {
        String missing = "6740000000000000000000ff";

        assertThat(rest.getForEntity("/api/v1/orders/" + missing, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cached(missing)).isFalse();
    }
}
