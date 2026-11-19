package com.peerislands.orders.domain;

import com.peerislands.orders.TestcontainersConfig;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies how {@link Order} is stored in MongoDB. Guards against BigDecimal silently falling back to
 * String (Spring Data MongoDB's default) instead of Decimal128.
 */
@DataMongoTest
@Import(TestcontainersConfig.class)
class OrderMappingIT {

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollection() {
        mongoTemplate.dropCollection(Order.class);
    }

    private Order newOrder() {
        // Mongo stores millisecond precision, so truncate to compare round-tripped values.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<OrderItem> items = List.of(
                OrderItem.builder().productId("p1").productName("Keyboard").quantity(2)
                        .unitPrice(new BigDecimal("49.99")).build(),
                OrderItem.builder().productId("p2").productName("Mouse").quantity(1)
                        .unitPrice(new BigDecimal("19.50")).build());
        return Order.newPending("cust-1", items, "key-1", "hash-1", now);
    }

    @Test
    void insertAssignsIdAndInitialVersion() {
        Order saved = mongoTemplate.insert(newOrder());

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    void storesMoneyAsDecimal128AndStatusAsString() {
        Order saved = mongoTemplate.insert(newOrder());

        Document raw = mongoTemplate.getCollection("orders")
                .find(eq("_id", new ObjectId(saved.getId())))
                .first();

        assertThat(raw).isNotNull();
        assertThat(raw.get("totalAmount")).isInstanceOf(Decimal128.class)
                .isEqualTo(new Decimal128(new BigDecimal("119.48")));
        assertThat(raw.get("status")).isEqualTo("PENDING");
        assertThat(raw.get("version")).isEqualTo(0L);

        List<Document> rawItems = raw.getList("items", Document.class);
        assertThat(rawItems).hasSize(2);
        assertThat(rawItems).allSatisfy(item -> assertThat(item.get("unitPrice")).isInstanceOf(Decimal128.class));
        assertThat(rawItems.get(0).get("unitPrice")).isEqualTo(new Decimal128(new BigDecimal("49.99")));
    }

    @Test
    void roundTripPreservesAllFields() {
        Order original = newOrder();
        Order saved = mongoTemplate.insert(original);

        Order loaded = mongoTemplate.findById(saved.getId(), Order.class);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getCustomerId()).isEqualTo("cust-1");
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo("119.48");
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(loaded.getRequestHash()).isEqualTo("hash-1");
        assertThat(loaded.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(loaded.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getItems()).hasSize(2);
        assertThat(loaded.getItems().get(0).productName()).isEqualTo("Keyboard");
        assertThat(loaded.getItems().get(0).quantity()).isEqualTo(2);
        assertThat(loaded.getItems().get(0).unitPrice()).isEqualByComparingTo("49.99");
    }
}
