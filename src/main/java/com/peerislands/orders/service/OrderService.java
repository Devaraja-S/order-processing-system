package com.peerislands.orders.service;

import com.peerislands.orders.controller.dto.CreateOrderRequest;
import com.peerislands.orders.controller.dto.OrderResponse;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.event.OrderStatusChangedEvent;
import com.peerislands.orders.exception.IdempotencyKeyReuseException;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import com.peerislands.orders.exception.OrderNotFoundException;
import com.peerislands.orders.exception.StatusNotPatchableException;
import com.peerislands.orders.mapper.CursorCodec;
import com.peerislands.orders.mapper.OrderMapper;
import com.peerislands.orders.repository.OrderCursor;
import com.peerislands.orders.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final RequestHasher requestHasher;
    private final CursorCodec cursorCodec;
    private final OrderCache orderCache;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Creates a PENDING order. With an idempotency key, a repeated request with the same payload
     * returns the original order; the same key with a different payload is rejected.
     * Concurrent duplicates are resolved by the unique index on idempotencyKey.
     */
    public CreateOrderResult create(CreateOrderRequest request, String idempotencyKey) {
        List<OrderItem> items = orderMapper.toItems(request);
        Instant now = now();

        if (idempotencyKey == null) {
            Order order = orderRepository.insert(Order.newPending(request.customerId(), items, null, null, now));
            log.info("Created order {}", order.getId());
            return new CreateOrderResult(order, true);
        }

        String requestHash = requestHasher.hash(request);

        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        try {
            Order order = orderRepository.insert(
                    Order.newPending(request.customerId(), items, idempotencyKey, requestHash, now));
            log.info("Created order {} with idempotency key", order.getId());
            return new CreateOrderResult(order, true);
        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key inserted first; the unique index rejected ours.
            log.info("Idempotency key race lost; returning the order created by the concurrent request");
            Order winner = orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Duplicate idempotency key reported but no order found", e));
            return replay(winner, requestHash);
        }
    }

    /**
     * Cache-aside read: Redis first, MongoDB on a miss (or if Redis is unavailable), then cache the result.
     * Status changes evict the entry via events; the TTL bounds any remaining staleness.
     */
    public OrderResponse getOrder(String id) {
        return orderCache.get(id).orElseGet(() -> {
            Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
            OrderResponse response = orderMapper.toResponse(order);
            orderCache.put(response);
            return response;
        });
    }

    /**
     * Keyset page of orders, newest first. Fetches {@code size + 1} rows to know whether another page
     * exists, so a result that is an exact multiple of {@code size} never yields an empty extra page.
     *
     * @param cursor opaque cursor from a previous page, or null for the first page
     */
    public OrderPage list(OrderStatus status, int size, String cursor) {
        OrderCursor after = cursor == null ? null : cursorCodec.decode(cursor);

        List<Order> rows = orderRepository.findPage(status, after, size + 1);
        if (rows.size() <= size) {
            return new OrderPage(rows, null);
        }
        List<Order> page = List.copyOf(rows.subList(0, size));
        return new OrderPage(page, cursorCodec.encode(OrderCursor.of(page.getLast())));
    }

    /** Cancels a PENDING order. 404 if missing, 409 if it is no longer PENDING. */
    public Order cancel(String id) {
        return transition(id, OrderStatus.CANCELLED);
    }

    /**
     * Moves an order to {@code target} (PROCESSING, SHIPPED or DELIVERED). PENDING and CANCELLED are
     * rejected up front (400), before touching the database.
     */
    public Order updateStatus(String id, OrderStatus target) {
        if (target == OrderStatus.PENDING || target == OrderStatus.CANCELLED) {
            throw new StatusNotPatchableException(id, target);
        }
        return transition(id, target);
    }

    /**
     * Single conditional atomic update filtered on the status the state machine requires for
     * {@code target}. Never read-check-write: the read below runs only after the update matched nothing,
     * to choose between 404 and 409 and to report the current status.
     */
    private Order transition(String id, OrderStatus target) {
        OrderStatus expected = OrderStatus.requiredPredecessorOf(target)
                .orElseThrow(() -> new IllegalArgumentException("No status can transition to " + target));
        Instant now = now();

        Optional<Order> updated = orderRepository.transitionStatus(id, expected, target, now);
        if (updated.isPresent()) {
            log.info("Order {} status {} -> {}", id, expected, target);
            eventPublisher.publishEvent(new OrderStatusChangedEvent(id, expected, target, now));
            return updated.get();
        }

        Order current = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        throw new InvalidStatusTransitionException(id, current.getStatus(), target);
    }

    /** MongoDB dates have millisecond precision; truncate so returned values equal stored ones. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static CreateOrderResult replay(Order existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new IdempotencyKeyReuseException();
        }
        return new CreateOrderResult(existing, false);
    }
}
