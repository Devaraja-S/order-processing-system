package com.peerislands.orders.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.event.OrderStatusChangedEvent;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import com.peerislands.orders.service.OrderService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RecordApplicationEvents
class OrderStatusIT extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Valid PATCH transitions, written independently of OrderStatus (docs/DESIGN.md §4). */
    private static final Set<List<OrderStatus>> VALID_PATCH = Set.of(
            List.of(OrderStatus.PENDING, OrderStatus.PROCESSING),
            List.of(OrderStatus.PROCESSING, OrderStatus.SHIPPED),
            List.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED));

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void cleanOrders() {
        mongoTemplate.remove(new Query(), Order.class);
    }

    // --- helpers ---

    private Order insert(OrderStatus status) {
        OrderItem item = OrderItem.builder().productId("p1").productName("n").quantity(1)
                .unitPrice(new BigDecimal("1.00")).build();
        return mongoTemplate.insert(Order.builder()
                .customerId("cust-1").items(List.of(item)).totalAmount(new BigDecimal("1.00"))
                .status(status).createdAt(T0).updatedAt(T0).build());
    }

    private Order reload(String id) {
        return mongoTemplate.findById(id, Order.class);
    }

    private ResponseEntity<JsonNode> patch(String id, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/orders/" + id + "/status", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patchStatus(String id, OrderStatus status) {
        return patch(id, "{\"status\": \"" + status + "\"}");
    }

    private ResponseEntity<JsonNode> cancel(String id) {
        return rest.exchange("/api/v1/orders/" + id + "/cancel", HttpMethod.POST, HttpEntity.EMPTY, JsonNode.class);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("status").asInt()).isEqualTo(status.value());
    }

    private void assertUnchanged(Order before) {
        Order after = reload(before.getId());
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    // --- PATCH: full matrix ---

    static Stream<Arguments> patchMatrix() {
        return Stream.of(OrderStatus.values()).flatMap(current ->
                Stream.of(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED)
                        .map(target -> Arguments.of(current, target, VALID_PATCH.contains(List.of(current, target)))));
    }

    @ParameterizedTest(name = "PATCH {0} -> {1}: valid={2}")
    @MethodSource("patchMatrix")
    void patchStatusMatrix(OrderStatus current, OrderStatus target, boolean valid) {
        Order before = insert(current);

        ResponseEntity<JsonNode> response = patchStatus(before.getId(), target);

        if (valid) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("status").asText()).isEqualTo(target.name());
            Order after = reload(before.getId());
            assertThat(after.getStatus()).isEqualTo(target);
            assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
            assertThat(after.getUpdatedAt()).isAfter(before.getUpdatedAt());
        } else {
            assertProblem(response, HttpStatus.CONFLICT);
            assertThat(response.getBody().path("currentStatus").asText()).isEqualTo(current.name());
            assertThat(response.getBody().path("targetStatus").asText()).isEqualTo(target.name());
            assertUnchanged(before);
        }
    }

    @Test
    void fullLifecycleViaPatchIncrementsVersionEachStep() {
        Order order = insert(OrderStatus.PENDING);

        assertThat(patchStatus(order.getId(), OrderStatus.PROCESSING).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchStatus(order.getId(), OrderStatus.SHIPPED).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchStatus(order.getId(), OrderStatus.DELIVERED).getStatusCode()).isEqualTo(HttpStatus.OK);

        Order after = reload(order.getId());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(after.getVersion()).isEqualTo(3L);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "CANCELLED"})
    void patchToPendingOrCancelledIsBadRequestAndChangesNothing(OrderStatus target) {
        Order before = insert(OrderStatus.PENDING);

        ResponseEntity<JsonNode> response = patchStatus(before.getId(), target);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertUnchanged(before);
    }

    @Test
    void patchToCancelledPointsToCancelEndpoint() {
        Order order = insert(OrderStatus.PENDING);

        JsonNode body = patchStatus(order.getId(), OrderStatus.CANCELLED).getBody();

        assertThat(body.path("detail").asText()).contains("/api/v1/orders/" + order.getId() + "/cancel");
    }

    @Test
    void patchToCancelledIsBadRequestEvenForMissingOrder() {
        assertProblem(patchStatus(new ObjectId().toHexString(), OrderStatus.CANCELLED), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\": null}", "{\"status\": \"BOGUS\"}", "{\"status\": \"shipped\"}",
            "{\"status\": \"SHIPPED\", \"extra\": 1}", "not json"})
    void malformedPatchBodyIsBadRequest(String body) {
        Order before = insert(OrderStatus.PROCESSING);

        assertProblem(patch(before.getId(), body), HttpStatus.BAD_REQUEST);
        assertUnchanged(before);
    }

    @Test
    void patchMissingOrderIsNotFound() {
        assertProblem(patchStatus(new ObjectId().toHexString(), OrderStatus.SHIPPED), HttpStatus.NOT_FOUND);
        assertProblem(patchStatus("not-an-object-id", OrderStatus.SHIPPED), HttpStatus.NOT_FOUND);
    }

    // --- cancel ---

    @Test
    void cancelPendingOrderSucceeds() {
        Order before = insert(OrderStatus.PENDING);

        ResponseEntity<JsonNode> response = cancel(before.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("CANCELLED");
        Order after = reload(before.getId());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getUpdatedAt()).isAfter(before.getUpdatedAt());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"})
    void cancelNonPendingOrderIsConflictAndChangesNothing(OrderStatus current) {
        Order before = insert(current);

        ResponseEntity<JsonNode> response = cancel(before.getId());

        assertProblem(response, HttpStatus.CONFLICT);
        assertThat(response.getBody().path("currentStatus").asText()).isEqualTo(current.name());
        assertThat(response.getBody().path("detail").asText()).contains("only PENDING orders can be cancelled");
        assertUnchanged(before);
    }

    @Test
    void cancelMissingOrderIsNotFound() {
        assertProblem(cancel(new ObjectId().toHexString()), HttpStatus.NOT_FOUND);
        assertProblem(cancel("not-an-object-id"), HttpStatus.NOT_FOUND);
    }

    @Test
    void cancellingTwiceIsConflictTheSecondTime() {
        Order order = insert(OrderStatus.PENDING);

        assertThat(cancel(order.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(cancel(order.getId()), HttpStatus.CONFLICT);
        assertThat(reload(order.getId()).getVersion()).isEqualTo(1L);
    }

    @Test
    void getAfterCancelShowsCancelled() {
        Order order = insert(OrderStatus.PENDING);
        cancel(order.getId());

        JsonNode body = rest.getForEntity("/api/v1/orders/" + order.getId(), JsonNode.class).getBody();

        assertThat(body.path("status").asText()).isEqualTo("CANCELLED");
    }

    // --- events (service called on the test thread so @RecordApplicationEvents sees them) ---

    @Test
    void successfulChangesPublishEvents() {
        Order order = insert(OrderStatus.PENDING);

        orderService.updateStatus(order.getId(), OrderStatus.PROCESSING);

        assertThat(events.stream(OrderStatusChangedEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.orderId()).isEqualTo(order.getId());
            assertThat(e.from()).isEqualTo(OrderStatus.PENDING);
            assertThat(e.to()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(e.changedAt()).isEqualTo(reload(order.getId()).getUpdatedAt());
        });
    }

    @Test
    void failedChangesPublishNoEvent() {
        Order order = insert(OrderStatus.SHIPPED);

        assertThatThrownBy(() -> orderService.cancel(order.getId()))
                .isInstanceOf(InvalidStatusTransitionException.class);

        assertThat(events.stream(OrderStatusChangedEvent.class)).isEmpty();
    }
}
