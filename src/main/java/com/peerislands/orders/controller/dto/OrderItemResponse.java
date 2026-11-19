package com.peerislands.orders.controller.dto;

import java.math.BigDecimal;

public record OrderItemResponse(String productId, String productName, int quantity, BigDecimal unitPrice) {
}
