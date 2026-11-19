package com.peerislands.orders.controller.dto;

import com.peerislands.orders.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Public view of an order. Internal fields (idempotencyKey, requestHash, version) are not exposed. */
public record OrderResponse(
        String id,
        String customerId,
        List<OrderItemResponse> items,
        BigDecimal totalAmount,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
