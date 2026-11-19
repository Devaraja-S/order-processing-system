package com.peerislands.orders.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.config.MongoIndexConfig;
import com.peerislands.orders.domain.Order;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

class OrderControllerIT extends AbstractIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";

    /** 2 x 49.99 + 1 x 19.50 = 119.48 */
    private static final String VALID_BODY = """
            {
              "customerId": "cust-1",
              "items": [
                {"productId": "p1", "productName": "Keyboard", "quantity": 2, "unitPrice": 49.99},
                {"productId": "p2", "productName": "Mouse", "quantity": 1, "unitPrice": 19.50}
              ]
            }
            """;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanOrders() {
        // remove (not drop) so the indexes created at startup survive
        mongoTemplate.remove(new Query(), Order.class);
    }

    private ResponseEntity<JsonNode> post(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set(OrderController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return rest.exchange(ORDERS, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String id) {
        return rest.getForEntity(ORDERS + "/" + id, JsonNode.class);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(status.value());
        assertThat(response.getBody().path("title").asText()).isNotBlank();
    }

    private Document rawOrder(String id) {
        return mongoTemplate.getCollection("orders").find(eq("_id", new ObjectId(id))).first();
    }

    // --- create + get ---

    @Test
    void createReturns201WithLocationAndServerComputedTotal() {
        ResponseEntity<JsonNode> response = post(VALID_BODY, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        String id = body.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo(ORDERS + "/" + id);
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("customerId").asText()).isEqualTo("cust-1");
        assertThat(body.path("totalAmount").decimalValue()).isEqualByComparingTo("119.48");
        assertThat(body.path("items")).hasSize(2);
        assertThat(body.path("createdAt").asText()).isNotBlank();
        assertThat(body.has("idempotencyKey")).isFalse();
        assertThat(body.has("requestHash")).isFalse();
        assertThat(body.has("version")).isFalse();
    }

    @Test
    void getReturnsCreatedOrder() {
        String id = post(VALID_BODY, null).getBody().path("id").asText();

        ResponseEntity<JsonNode> response = get(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("id").asText()).isEqualTo(id);
        assertThat(response.getBody().path("totalAmount").decimalValue()).isEqualByComparingTo("119.48");
    }

    @Test
    void getMissingOrderReturns404Problem() {
        assertProblem(get(new ObjectId().toHexString()), HttpStatus.NOT_FOUND);
    }

    @Test
    void getWithMalformedIdReturns404Problem() {
        assertProblem(get("not-an-object-id"), HttpStatus.NOT_FOUND);
    }

    @Test
    void storesMoneyAsDecimal128AndPriceAtScaleTwo() {
        String body = """
                {"customerId": "c", "items": [{"productId": "p1", "productName": "n", "quantity": 1, "unitPrice": 10.5}]}
                """;
        ResponseEntity<JsonNode> response = post(body, null);
        String id = response.getBody().path("id").asText();

        Document raw = rawOrder(id);
        assertThat(raw.get("totalAmount")).isEqualTo(new Decimal128(new BigDecimal("10.50")));
        assertThat(raw.getList("items", Document.class).getFirst().get("unitPrice"))
                .isEqualTo(new Decimal128(new BigDecimal("10.50")));
    }

    // --- validation / malformed input ---

    @Test
    void validationErrorsReturn400WithFieldErrors() {
        ResponseEntity<JsonNode> response = post("""
                {"customerId": " ", "items": []}
                """, null);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").findValuesAsText("field"))
                .contains("customerId", "items");
    }

    @Test
    void duplicateProductIdReturns400() {
        ResponseEntity<JsonNode> response = post("""
                {"customerId": "c", "items": [
                  {"productId": "p1", "productName": "a", "quantity": 1, "unitPrice": 1.00},
                  {"productId": "p1", "productName": "b", "quantity": 2, "unitPrice": 1.00}]}
                """, null);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").findValuesAsText("field")).contains("productIdsUnique");
    }

    @Test
    void priceWithThreeDecimalsReturns400() {
        ResponseEntity<JsonNode> response = post("""
                {"customerId": "c", "items": [{"productId": "p1", "productName": "n", "quantity": 1, "unitPrice": 1.001}]}
                """, null);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").findValuesAsText("field")).contains("items[0].unitPrice");
    }

    @Test
    void fractionalQuantityIsRejectedNotTruncated() {
        ResponseEntity<JsonNode> response = post("""
                {"customerId": "c", "items": [{"productId": "p1", "productName": "n", "quantity": 1.5, "unitPrice": 1.00}]}
                """, null);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("detail").asText()).contains("items[0].quantity");
        assertThat(mongoTemplate.count(new Query(), Order.class)).isZero();
    }

    @Test
    void unknownFieldSuchAsTotalAmountIsRejected() {
        ResponseEntity<JsonNode> response = post("""
                {"customerId": "c", "totalAmount": 0.01,
                 "items": [{"productId": "p1", "productName": "n", "quantity": 1, "unitPrice": 1.00}]}
                """, null);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("detail").asText()).contains("totalAmount");
    }

    @Test
    void malformedJsonReturns400() {
        ResponseEntity<JsonNode> response = post("{\"customerId\": ", null);

        assertProblem(response, HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingBodyReturns400() {
        ResponseEntity<JsonNode> response = post("", null);

        assertProblem(response, HttpStatus.BAD_REQUEST);
    }

    // --- idempotency ---

    @Test
    void sameKeySamePayloadReturns200WithOriginalOrder() {
        ResponseEntity<JsonNode> first = post(VALID_BODY, "key-1");
        ResponseEntity<JsonNode> second = post(VALID_BODY, "key-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getLocation()).isNull();
        assertThat(second.getBody().path("id").asText()).isEqualTo(first.getBody().path("id").asText());
        assertThat(mongoTemplate.count(new Query(), Order.class)).isOne();
    }

    @Test
    void sameKeyWithReorderedItemsIsTheSamePayload() {
        String reordered = """
                {
                  "items": [
                    {"productId": "p2", "productName": "Mouse", "quantity": 1, "unitPrice": 19.5},
                    {"productId": "p1", "productName": "Keyboard", "quantity": 2, "unitPrice": 49.99}
                  ],
                  "customerId": "cust-1"
                }
                """;
        String id = post(VALID_BODY, "key-2").getBody().path("id").asText();

        ResponseEntity<JsonNode> replay = post(reordered, "key-2");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("id").asText()).isEqualTo(id);
    }

    @Test
    void sameKeyDifferentPayloadReturns422() {
        post(VALID_BODY, "key-3");

        ResponseEntity<JsonNode> response = post(VALID_BODY.replace("\"quantity\": 2", "\"quantity\": 3"), "key-3");

        assertProblem(response, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mongoTemplate.count(new Query(), Order.class)).isOne();
    }

    @Test
    void differentKeysCreateDifferentOrders() {
        String a = post(VALID_BODY, "key-a").getBody().path("id").asText();
        String b = post(VALID_BODY, "key-b").getBody().path("id").asText();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void ordersWithoutKeyDoNotConflictAndStoreNoKeyField() {
        ResponseEntity<JsonNode> first = post(VALID_BODY, null);
        ResponseEntity<JsonNode> second = post(VALID_BODY, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Document raw = rawOrder(first.getBody().path("id").asText());
        assertThat(raw.containsKey("idempotencyKey")).isFalse();
        assertThat(raw.containsKey("requestHash")).isFalse();
    }

    @Test
    void blankKeyReturns400() {
        ResponseEntity<JsonNode> response = post(VALID_BODY, "   ");

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").findValuesAsText("field")).contains("Idempotency-Key");
    }

    @Test
    void keyOf128CharsIsAcceptedAnd129IsRejected() {
        assertThat(post(VALID_BODY, "k".repeat(128)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertProblem(post(VALID_BODY, "k".repeat(129)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void idempotencyKeyIndexIsUniqueAndSparse() {
        IndexInfo index = mongoTemplate.indexOps(Order.class).getIndexInfo().stream()
                .filter(i -> i.getName().equals(MongoIndexConfig.IDEMPOTENCY_KEY_INDEX))
                .findFirst()
                .orElseThrow();

        assertThat(index.isUnique()).isTrue();
        assertThat(index.isSparse()).isTrue();
        assertThat(index.getIndexFields()).singleElement()
                .satisfies(f -> assertThat(f.getKey()).isEqualTo("idempotencyKey"));
    }
}
