package com.peerislands.orders.domain;

import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Order aggregate, stored in the {@code orders} collection with items embedded.
 *
 * <p>Immutable on purpose: there are no status setters or transition methods. Status changes are
 * applied only as conditional atomic updates in MongoDB (never read-check-write in Java).
 * {@code id} and {@code version} have withers so Spring Data can populate them after insert.
 */
@Value
@Document("orders")
public class Order {

    public static final int MONEY_SCALE = 2;
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

    @With
    @Id
    String id;

    String customerId;

    List<OrderItem> items;

    @Field(targetType = FieldType.DECIMAL128)
    BigDecimal totalAmount;

    OrderStatus status;

    String idempotencyKey;

    String requestHash;

    Instant createdAt;

    Instant updatedAt;

    @With
    @Version
    Long version;

    @Builder(toBuilder = true)
    Order(String id, String customerId, List<OrderItem> items, BigDecimal totalAmount, OrderStatus status,
          String idempotencyKey, String requestHash, Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.customerId = customerId;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.totalAmount = totalAmount;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * Creates a new PENDING order with a server-computed total. {@code id} and {@code version}
     * stay null so MongoDB / Spring Data assign them on insert.
     */
    public static Order newPending(String customerId, List<OrderItem> items, String idempotencyKey,
                                   String requestHash, Instant now) {
        return Order.builder()
                .customerId(customerId)
                .items(items)
                .totalAmount(calculateTotal(items))
                .status(OrderStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Sum of quantity x unitPrice over all items, at scale 2 with HALF_EVEN rounding. */
    public static BigDecimal calculateTotal(List<OrderItem> items) {
        return items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
