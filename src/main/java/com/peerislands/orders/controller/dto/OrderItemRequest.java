package com.peerislands.orders.controller.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;

@Builder(toBuilder = true)
public record OrderItemRequest(
        @NotBlank @Size(max = 64) String productId,
        @NotBlank @Size(max = 200) String productName,
        @NotNull @Min(1) @Max(100) Integer quantity,
        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("1000000")
        @Digits(integer = 7, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal unitPrice) {
}
