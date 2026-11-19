package com.peerislands.orders.service;

import com.peerislands.orders.domain.Order;

import java.util.List;

/**
 * One page of orders, newest first.
 *
 * @param nextCursor opaque cursor for the next page, or null if this is the last page
 */
public record OrderPage(List<Order> items, String nextCursor) {
}
