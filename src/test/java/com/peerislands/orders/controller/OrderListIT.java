package com.peerislands.orders.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderListIT extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Newest first; ties on createdAt broken by _id descending (hex order == ObjectId order). */
    private static final Comparator<Order> NEWEST_FIRST = Comparator
            .comparing(Order::getCreatedAt).reversed()
            .thenComparing(Order::getId, Comparator.reverseOrder());

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanOrders() {
        mongoTemplate.remove(new Query(), Order.class);
    }

    // --- helpers ---

    private Order insert(OrderStatus status, Instant createdAt) {
        OrderItem item = OrderItem.builder().productId("p1").productName("n").quantity(1)
                .unitPrice(new BigDecimal("1.00")).build();
        return mongoTemplate.insert(Order.builder()
                .customerId("cust-1").items(List.of(item)).totalAmount(new BigDecimal("1.00"))
                .status(status).createdAt(createdAt).updatedAt(createdAt).build());
    }

    /** Inserts {@code count} orders one second apart starting at {@code start}. */
    private List<Order> insertMany(int count, OrderStatus status, Instant start) {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(insert(status, start.plusSeconds(i)));
        }
        return orders;
    }

    private static List<String> expectedIds(List<Order> orders) {
        return orders.stream().sorted(NEWEST_FIRST).map(Order::getId).toList();
    }

    private ResponseEntity<JsonNode> list(String query) {
        return rest.getForEntity("/api/v1/orders" + (query.isEmpty() ? "" : "?" + query), JsonNode.class);
    }

    private ResponseEntity<JsonNode> list(OrderStatus status, Integer size, String cursor) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/api/v1/orders");
        if (status != null) {
            uri.queryParam("status", status);
        }
        if (size != null) {
            uri.queryParam("size", size);
        }
        if (cursor != null) {
            uri.queryParam("cursor", cursor);
        }
        return rest.getForEntity(uri.toUriString(), JsonNode.class);
    }

    private static List<String> ids(JsonNode page) {
        return page.path("items").findValuesAsText("id");
    }

    private static String nextCursor(JsonNode page) {
        JsonNode next = page.path("nextCursor");
        return next.isNull() || next.isMissingNode() ? null : next.asText();
    }

    /** Follows nextCursor until the last page; returns the page sizes and all ids in order. */
    private record Walk(List<Integer> pageSizes, List<String> ids) {
    }

    private Walk walk(OrderStatus status, int size) {
        List<Integer> pageSizes = new ArrayList<>();
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 100; guard++) {
            ResponseEntity<JsonNode> response = list(status, size, cursor);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode page = response.getBody();
            pageSizes.add(page.path("items").size());
            all.addAll(ids(page));
            cursor = nextCursor(page);
            if (cursor == null) {
                return new Walk(pageSizes, all);
            }
        }
        throw new AssertionError("pagination did not terminate");
    }

    // --- tests ---

    @Test
    void emptyCollectionReturnsNoItemsAndNullCursor() {
        ResponseEntity<JsonNode> response = list("");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("items")).isEmpty();
        assertThat(response.getBody().has("nextCursor")).isTrue();
        assertThat(response.getBody().path("nextCursor").isNull()).isTrue();
    }

    @Test
    void defaultPageSizeIs20NewestFirst() {
        List<Order> orders = insertMany(25, OrderStatus.PENDING, T0);

        JsonNode page = list("").getBody();

        assertThat(ids(page)).containsExactlyElementsOf(expectedIds(orders).subList(0, 20));
        assertThat(nextCursor(page)).isNotNull();
    }

    @Test
    void walkingAllPagesReturnsEveryOrderExactlyOnceInOrder() {
        List<Order> orders = insertMany(25, OrderStatus.PENDING, T0);

        Walk walk = walk(null, 10);

        assertThat(walk.pageSizes()).containsExactly(10, 10, 5);
        assertThat(walk.ids()).containsExactlyElementsOf(expectedIds(orders));
    }

    @Test
    void exactMultipleOfPageSizeHasNoEmptyExtraPage() {
        insertMany(20, OrderStatus.PENDING, T0);

        Walk walk = walk(null, 10);

        assertThat(walk.pageSizes()).containsExactly(10, 10);
    }

    @Test
    void tiesOnCreatedAtAcrossPageBoundariesAreNeitherDuplicatedNorSkipped() {
        List<Order> orders = new ArrayList<>(insertMany(3, OrderStatus.PENDING, T0));
        Instant tie = T0.plusSeconds(100);
        for (int i = 0; i < 12; i++) {
            orders.add(insert(OrderStatus.PENDING, tie));       // 12 orders with identical createdAt
        }
        orders.addAll(insertMany(3, OrderStatus.PENDING, T0.plusSeconds(200)));

        Walk walk = walk(null, 5);

        assertThat(walk.ids()).containsExactlyElementsOf(expectedIds(orders));
        assertThat(new HashSet<>(walk.ids())).hasSize(orders.size());
    }

    @Test
    void statusFilterAppliesAcrossPages() {
        List<Order> pending = insertMany(15, OrderStatus.PENDING, T0);
        insertMany(10, OrderStatus.SHIPPED, T0.plusMillis(500));

        Walk walk = walk(OrderStatus.PENDING, 4);

        assertThat(walk.pageSizes()).containsExactly(4, 4, 4, 3);
        assertThat(walk.ids()).containsExactlyElementsOf(expectedIds(pending));
    }

    @Test
    void ordersCreatedBetweenPageRequestsDoNotShiftLaterPages() {
        List<Order> orders = insertMany(10, OrderStatus.PENDING, T0);
        List<String> expected = expectedIds(orders);

        JsonNode first = list(null, 5, null).getBody();
        insertMany(3, OrderStatus.PENDING, T0.plusSeconds(1000)); // newer than everything
        JsonNode second = list(null, 5, nextCursor(first)).getBody();

        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 5));
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(5, 10));
        assertThat(nextCursor(second)).isNull();
    }

    @Test
    void responseItemsHaveOrderShape() {
        insert(OrderStatus.SHIPPED, T0);

        JsonNode item = list("").getBody().path("items").get(0);

        assertThat(item.path("status").asText()).isEqualTo("SHIPPED");
        assertThat(item.path("totalAmount").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(item.path("createdAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(item.has("idempotencyKey")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "size=0", "size=101", "size=-1", "size=abc",
            "status=BOGUS", "status=pending",
            "cursor=!!!", "cursor=Z2FyYmFnZQ"   // "garbage" in Base64: decodes, but wrong structure
    })
    void invalidParametersReturn400Problem(String query) {
        ResponseEntity<JsonNode> response = list(query);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("status").asInt()).isEqualTo(400);
    }

    @Test
    void invalidStatusDetailListsAllowedValues() {
        JsonNode body = list("status=BOGUS").getBody();

        assertThat(body.path("detail").asText()).contains("status").contains("PENDING").contains("DELIVERED");
    }

    @Test
    void size1And100AreAccepted() {
        assertThat(list("size=1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list("size=100").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void cursorWithValidShapeButUnknownPositionIsJustAPosition() {
        insertMany(3, OrderStatus.PENDING, T0);
        // A well-formed cursor pointing before every order: returns an empty last page, not an error.
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("0:" + "0".repeat(24)).getBytes(StandardCharsets.UTF_8));

        ResponseEntity<JsonNode> response = list(null, 10, cursor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("items")).isEmpty();
    }

    @Test
    void createdOrderAppearsFirstWithSameTimestampAsCreateResponse() {
        insertMany(3, OrderStatus.PENDING, T0);
        ResponseEntity<JsonNode> created = rest.postForEntity("/api/v1/orders", java.util.Map.of(
                "customerId", "cust-9",
                "items", List.of(java.util.Map.of("productId", "p1", "productName", "n", "quantity", 1,
                        "unitPrice", new BigDecimal("2.50")))), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode first = list("size=1").getBody().path("items").get(0);

        assertThat(first.path("id").asText()).isEqualTo(created.getBody().path("id").asText());
        // createdAt is truncated to millis, so the create response matches what is stored and listed.
        assertThat(first.path("createdAt").asText()).isEqualTo(created.getBody().path("createdAt").asText());
    }

    @Test
    void walkIdsAreUnique() {
        insertMany(30, OrderStatus.PENDING, T0);

        Set<String> unique = new HashSet<>(walk(null, 7).ids());

        assertThat(unique).hasSize(30);
    }
}
