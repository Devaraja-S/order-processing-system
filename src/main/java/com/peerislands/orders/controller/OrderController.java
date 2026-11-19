package com.peerislands.orders.controller;

import com.peerislands.orders.controller.dto.CreateOrderRequest;
import com.peerislands.orders.controller.dto.OrderPageResponse;
import com.peerislands.orders.controller.dto.OrderResponse;
import com.peerislands.orders.controller.dto.UpdateStatusRequest;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.mapper.OrderMapper;
import com.peerislands.orders.service.CreateOrderResult;
import com.peerislands.orders.service.OrderPage;
import com.peerislands.orders.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String DEFAULT_PAGE_SIZE = "20";
    public static final int MAX_PAGE_SIZE = 100;

    private final OrderService orderService;
    private final OrderMapper orderMapper;

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            @Size(min = 1, max = 128)
            @Pattern(regexp = ".*\\S.*", message = "must not be blank")
            String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        CreateOrderResult result = orderService.create(request, idempotencyKey);
        OrderResponse body = orderMapper.toResponse(result.order());

        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable String id) {
        return orderService.getOrder(id);
    }

    /** 200 with the updated order; 400 for PENDING/CANCELLED targets; 404 missing; 409 invalid transition. */
    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable String id, @Valid @RequestBody UpdateStatusRequest request) {
        return orderMapper.toResponse(orderService.updateStatus(id, request.status()));
    }

    /** 200 with the cancelled order; 404 missing; 409 if the order is no longer PENDING. */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable String id) {
        return orderMapper.toResponse(orderService.cancel(id));
    }

    /** Newest first, keyset pagination. Size outside 1..100 is rejected (never clamped). */
    @GetMapping
    public OrderPageResponse list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) String cursor) {

        OrderPage page = orderService.list(status, size, cursor);
        return new OrderPageResponse(
                page.items().stream().map(orderMapper::toResponse).toList(),
                page.nextCursor());
    }
}
