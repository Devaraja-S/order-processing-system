package com.peerislands.orders.domain;

import lombok.Builder;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;

/**
 * Line item embedded in an {@link Order}. {@code productName} and {@code unitPrice} are a snapshot
 * taken at order time.
 */
@Builder
public record OrderItem(
        String productId,
        String productName,
        int quantity,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal unitPrice) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
