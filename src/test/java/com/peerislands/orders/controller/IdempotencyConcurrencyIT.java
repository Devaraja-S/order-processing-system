package com.peerislands.orders.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many simultaneous POSTs with the same Idempotency-Key and body must create exactly one order;
 * every other request gets that same order back with 200.
 */
class IdempotencyConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 10;

    private static final String BODY = """
            {"customerId": "cust-1", "items": [{"productId": "p1", "productName": "n", "quantity": 1, "unitPrice": 5.00}]}
            """;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanOrders() {
        mongoTemplate.remove(new Query(), Order.class);
    }

    @RepeatedTest(5)
    void concurrentRequestsWithSameKeyCreateExactlyOneOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(OrderController.IDEMPOTENCY_KEY_HEADER, key);
        HttpEntity<String> request = new HttpEntity<>(BODY, headers);

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                responses.add(f.get(30, TimeUnit.SECONDS));
            }

            List<HttpStatusCode> statuses = responses.stream().map(ResponseEntity::getStatusCode).toList();
            assertThat(statuses).filteredOn(s -> s.equals(HttpStatus.CREATED)).hasSize(1);
            assertThat(statuses).filteredOn(s -> s.equals(HttpStatus.OK)).hasSize(THREADS - 1);
            assertThat(responses).extracting(r -> r.getBody().path("id").asText()).containsOnly(
                    responses.getFirst().getBody().path("id").asText());
        }

        long stored = mongoTemplate.count(Query.query(Criteria.where("idempotencyKey").is(key)), Order.class);
        assertThat(stored).isOne();
    }
}
