package com.peerislands.orders.controller.dto;

import com.peerislands.orders.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /orders/{id}/status. PENDING and CANCELLED are rejected by the service (400). */
public record UpdateStatusRequest(@NotNull OrderStatus status) {
}
