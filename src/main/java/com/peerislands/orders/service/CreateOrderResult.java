package com.peerislands.orders.service;

import com.peerislands.orders.domain.Order;

/**
 * Outcome of a create request.
 *
 * @param created true if a new order was inserted (201), false if an existing order was returned for
 *                an idempotent replay (200)
 */
public record CreateOrderResult(Order order, boolean created) {
}
