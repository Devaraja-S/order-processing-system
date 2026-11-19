package com.peerislands.orders;

import com.peerislands.orders.controller.dto.CreateOrderRequest;
import com.peerislands.orders.controller.dto.OrderItemRequest;

import java.math.BigDecimal;
import java.util.List;

/** Test fixtures for create-order requests. */
public final class TestOrders {

    private TestOrders() {
    }

    public static OrderItemRequest item(String productId, int quantity, String unitPrice) {
        return OrderItemRequest.builder()
                .productId(productId)
                .productName("Product " + productId)
                .quantity(quantity)
                .unitPrice(new BigDecimal(unitPrice))
                .build();
    }

    public static CreateOrderRequest request(String customerId, OrderItemRequest... items) {
        return CreateOrderRequest.builder().customerId(customerId).items(List.of(items)).build();
    }

    /** cust-1: 2 x 49.99 + 1 x 19.50 = 119.48 */
    public static CreateOrderRequest validRequest() {
        return request("cust-1", item("p1", 2, "49.99"), item("p2", 1, "19.50"));
    }
}
