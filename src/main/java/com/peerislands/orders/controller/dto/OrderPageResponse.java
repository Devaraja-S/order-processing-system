package com.peerislands.orders.controller.dto;

import java.util.List;

/** Keyset page: {@code nextCursor} is null on the last page. */
public record OrderPageResponse(List<OrderResponse> items, String nextCursor) {
}
