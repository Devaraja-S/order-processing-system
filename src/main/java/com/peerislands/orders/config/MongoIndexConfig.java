package com.peerislands.orders.config;

import com.peerislands.orders.domain.Order;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

/**
 * Creates the indexes on the {@code orders} collection at startup, before the app serves requests.
 * Explicit (rather than Spring Data auto-index-creation) so every index is visible in one place.
 * Creating an index that already exists with the same definition is a no-op.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class MongoIndexConfig {

    public static final String IDEMPOTENCY_KEY_INDEX = "idempotencyKey_unique";
    public static final String STATUS_CREATED_AT_INDEX = "status_createdAt_id";
    public static final String CREATED_AT_INDEX = "createdAt_id";
    public static final String CUSTOMER_CREATED_AT_INDEX = "customerId_createdAt";

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    void createIndexes() {
        IndexOperations orders = mongoTemplate.indexOps(Order.class);

        // List by status (keyset, scanned backwards for newest first) and the PENDING scheduler scan.
        orders.createIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC)
                .on("_id", Sort.Direction.ASC)
                .named(STATUS_CREATED_AT_INDEX));

        // Unfiltered list (keyset, scanned backwards for newest first).
        orders.createIndex(new Index()
                .on("createdAt", Sort.Direction.ASC)
                .on("_id", Sort.Direction.ASC)
                .named(CREATED_AT_INDEX));

        // Future customer order history (no endpoint yet; see docs/DESIGN.md).
        orders.createIndex(new Index()
                .on("customerId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named(CUSTOMER_CREATED_AT_INDEX));

        // Duplicate-order prevention. Sparse: orders without a key have no idempotencyKey field
        // (Spring Data does not write nulls), so they never collide.
        orders.createIndex(new Index()
                .on("idempotencyKey", Sort.Direction.ASC)
                .unique()
                .sparse()
                .named(IDEMPOTENCY_KEY_INDEX));

        log.info("Ensured MongoDB indexes on 'orders'");
    }
}
