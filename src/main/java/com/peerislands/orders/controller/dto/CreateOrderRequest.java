package com.peerislands.orders.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Create-order request. Deliberately has no totalAmount/status/timestamps: those are server-side only,
 * and unknown JSON fields are rejected.
 */
@Builder
public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid OrderItemRequest> items) {

    /** Duplicate productIds within one order are rejected (callers must merge quantities). */
    @JsonIgnore
    @AssertTrue(message = "productId must be unique within an order")
    public boolean isProductIdsUnique() {
        if (items == null) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        return items.stream()
                .filter(Objects::nonNull)
                .map(OrderItemRequest::productId)
                .filter(Objects::nonNull)
                .allMatch(seen::add);
    }
}
