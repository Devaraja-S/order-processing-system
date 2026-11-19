package com.peerislands.orders.repository;

import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.config.MongoIndexConfig;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Indexes exist with the agreed definitions, and the list queries actually use them: the winning plan
 * is an index scan on the expected index with no in-memory SORT stage.
 */
class OrderIndexIT extends AbstractIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void seed() {
        mongoTemplate.remove(new Query(), Order.class);
        // Mostly non-PENDING data so the planner has a real choice for the status-filtered query.
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            OrderStatus status = i % 10 == 0 ? OrderStatus.PENDING : OrderStatus.DELIVERED;
            Instant createdAt = t0.plusSeconds(i);
            orders.add(Order.builder().customerId("c" + (i % 7))
                    .items(List.of(OrderItem.builder().productId("p").productName("n").quantity(1)
                            .unitPrice(BigDecimal.ONE).build()))
                    .totalAmount(BigDecimal.ONE).status(status).createdAt(createdAt).updatedAt(createdAt)
                    .build());
        }
        mongoTemplate.insertAll(orders);
    }

    private Map<String, IndexInfo> indexes() {
        return mongoTemplate.indexOps(Order.class).getIndexInfo().stream()
                .collect(Collectors.toMap(IndexInfo::getName, Function.identity()));
    }

    private static List<String> keys(IndexInfo index) {
        return index.getIndexFields().stream()
                .map(f -> f.getKey() + ":" + (f.getDirection() == Sort.Direction.ASC ? "1" : "-1"))
                .toList();
    }

    @Test
    void allIndexesExistWithAgreedDefinitions() {
        Map<String, IndexInfo> indexes = indexes();

        assertThat(keys(indexes.get(MongoIndexConfig.STATUS_CREATED_AT_INDEX)))
                .containsExactly("status:1", "createdAt:1", "_id:1");
        assertThat(keys(indexes.get(MongoIndexConfig.CREATED_AT_INDEX)))
                .containsExactly("createdAt:1", "_id:1");
        assertThat(keys(indexes.get(MongoIndexConfig.CUSTOMER_CREATED_AT_INDEX)))
                .containsExactly("customerId:1", "createdAt:-1");

        IndexInfo idempotency = indexes.get(MongoIndexConfig.IDEMPOTENCY_KEY_INDEX);
        assertThat(keys(idempotency)).containsExactly("idempotencyKey:1");
        assertThat(idempotency.isUnique()).isTrue();
        assertThat(idempotency.isSparse()).isTrue();
    }

    @Test
    void statusFilteredFirstPageUsesStatusIndexWithoutSort() {
        assertPlan(OrderRepositoryCustomImpl.pageQuery(OrderStatus.PENDING, null, 21),
                MongoIndexConfig.STATUS_CREATED_AT_INDEX);
    }

    @Test
    void statusFilteredPageAfterCursorUsesStatusIndexWithoutSort() {
        OrderCursor cursor = new OrderCursor(Instant.parse("2026-01-01T00:02:00Z"), new ObjectId().toHexString());
        assertPlan(OrderRepositoryCustomImpl.pageQuery(OrderStatus.PENDING, cursor, 21),
                MongoIndexConfig.STATUS_CREATED_AT_INDEX);
    }

    @Test
    void unfilteredFirstPageUsesCreatedAtIndexWithoutSort() {
        assertPlan(OrderRepositoryCustomImpl.pageQuery(null, null, 21), MongoIndexConfig.CREATED_AT_INDEX);
    }

    @Test
    void unfilteredPageAfterCursorUsesCreatedAtIndexWithoutSort() {
        OrderCursor cursor = new OrderCursor(Instant.parse("2026-01-01T00:02:00Z"), new ObjectId().toHexString());
        assertPlan(OrderRepositoryCustomImpl.pageQuery(null, cursor, 21), MongoIndexConfig.CREATED_AT_INDEX);
    }

    @Test
    void schedulerIdQueryIsCoveredByStatusIndexWithoutSort() {
        Query query = OrderRepositoryCustomImpl.pendingIdsQuery(Instant.parse("2026-01-01T01:00:00Z"), 1000);

        Document winningPlan = explain(query);

        assertPlan(query, MongoIndexConfig.STATUS_CREATED_AT_INDEX);
        // Covered: answered from the index alone, no document fetch.
        assertThat(collect(winningPlan, "stage")).as("stages in %s", winningPlan.toJson()).doesNotContain("FETCH");
    }

    @Test
    void schedulerIdQueryReturnsOldestPendingFirst() {
        List<String> ids = mongoTemplate.find(
                        OrderRepositoryCustomImpl.pendingIdsQuery(Instant.parse("2026-01-01T01:00:00Z"), 5),
                        Document.class, "orders").stream()
                .map(d -> d.getObjectId("_id").toHexString()).toList();

        List<Order> expected = mongoTemplate.find(new Query(org.springframework.data.mongodb.core.query.Criteria
                        .where("status").is(OrderStatus.PENDING))
                .with(Sort.by("createdAt")).limit(5), Order.class);
        assertThat(ids).containsExactlyElementsOf(expected.stream().map(Order::getId).toList());
    }

    // --- explain helpers ---

    private void assertPlan(Query query, String expectedIndex) {
        Document winningPlan = explain(query);
        List<String> stages = collect(winningPlan, "stage");
        List<String> indexNames = collect(winningPlan, "indexName");

        assertThat(indexNames).as("indexes used in %s", winningPlan.toJson()).containsOnly(expectedIndex);
        assertThat(stages).as("stages in %s", winningPlan.toJson()).contains("IXSCAN").doesNotContain("SORT");
    }

    private Document explain(Query query) {
        MongoPersistentEntity<?> entity =
                mongoTemplate.getConverter().getMappingContext().getRequiredPersistentEntity(Order.class);
        QueryMapper mapper = new QueryMapper(mongoTemplate.getConverter());
        Document find = new Document("find", "orders")
                .append("filter", mapper.getMappedObject(query.getQueryObject(), entity))
                .append("sort", mapper.getMappedSort(query.getSortObject(), entity))
                .append("limit", query.getLimit());
        if (!query.getFieldsObject().isEmpty()) {
            find.append("projection", query.getFieldsObject());
        }
        Document result = mongoTemplate.getDb().runCommand(
                new Document("explain", find).append("verbosity", "queryPlanner"));
        return result.get("queryPlanner", Document.class).get("winningPlan", Document.class);
    }

    /** Recursively collects every string value stored under {@code key} in a (nested) explain document. */
    private static List<String> collect(Object node, String key) {
        List<String> values = new ArrayList<>();
        if (node instanceof Document doc) {
            doc.forEach((k, v) -> {
                if (k.equals(key) && v instanceof String s) {
                    values.add(s);
                } else {
                    values.addAll(collect(v, key));
                }
            });
        } else if (node instanceof List<?> list) {
            list.forEach(item -> values.addAll(collect(item, key)));
        }
        return values;
    }
}
