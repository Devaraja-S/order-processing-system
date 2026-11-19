package com.peerislands.orders.mapper;

import com.peerislands.orders.controller.dto.CreateOrderRequest;
import com.peerislands.orders.controller.dto.OrderItemRequest;
import com.peerislands.orders.controller.dto.OrderItemResponse;
import com.peerislands.orders.controller.dto.OrderResponse;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class OrderMapper {

    /**
     * Maps validated request items to domain items. Prices are normalized to scale 2 with
     * {@link RoundingMode#UNNECESSARY} (e.g. {@code 10.5} -> {@code 10.50}): validation already
     * guarantees at most 2 decimal places, so this never rounds (and would throw rather than round).
     */
    public List<OrderItem> toItems(CreateOrderRequest request) {
        return request.items().stream().map(this::toItem).toList();
    }

    private OrderItem toItem(OrderItemRequest item) {
        return OrderItem.builder()
                .productId(item.productId())
                .productName(item.productName())
                .quantity(item.quantity())
                .unitPrice(toMoney(item.unitPrice()))
                .build();
    }

    private static BigDecimal toMoney(BigDecimal value) {
        return value.setScale(Order.MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getItems().stream()
                        .map(i -> new OrderItemResponse(i.productId(), i.productName(), i.quantity(), i.unitPrice()))
                        .toList(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
