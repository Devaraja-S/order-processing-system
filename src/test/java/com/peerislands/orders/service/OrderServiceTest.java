package com.peerislands.orders.service;

import com.peerislands.orders.controller.dto.CreateOrderRequest;
import com.peerislands.orders.controller.dto.OrderResponse;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.event.OrderStatusChangedEvent;
import com.peerislands.orders.exception.IdempotencyKeyReuseException;
import com.peerislands.orders.exception.InvalidCursorException;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import com.peerislands.orders.exception.OrderNotFoundException;
import com.peerislands.orders.exception.StatusNotPatchableException;
import com.peerislands.orders.mapper.CursorCodec;
import com.peerislands.orders.mapper.OrderMapper;
import com.peerislands.orders.repository.OrderCursor;
import com.peerislands.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.peerislands.orders.TestOrders.item;
import static com.peerislands.orders.TestOrders.request;
import static com.peerislands.orders.TestOrders.validRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String KEY = "key-123";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OrderCache orderCache;

    private final RequestHasher requestHasher = new RequestHasher();
    private final OrderMapper orderMapper = new OrderMapper();
    private final CursorCodec cursorCodec = new CursorCodec();
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = serviceAt(NOW);
    }

    private OrderService serviceAt(Instant instant) {
        return new OrderService(orderRepository, orderMapper, requestHasher, cursorCodec, orderCache, eventPublisher,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void insertAssignsId() {
        when(orderRepository.insert(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0, Order.class).withId("new-id").withVersion(0L));
    }

    private Order existingOrder(CreateOrderRequest request) {
        return Order.newPending(request.customerId(), orderMapper.toItems(request), KEY,
                        requestHasher.hash(request), NOW.minusSeconds(60))
                .withId("existing-id").withVersion(0L);
    }

    @Test
    void createWithoutKeyInsertsPendingOrderWithServerSideTotal() {
        insertAssignsId();

        CreateOrderResult result = orderService.create(validRequest(), null);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).insert(captor.capture());
        Order inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(inserted.getTotalAmount()).isEqualTo(new BigDecimal("119.48"));
        assertThat(inserted.getCreatedAt()).isEqualTo(NOW);
        assertThat(inserted.getUpdatedAt()).isEqualTo(NOW);
        assertThat(inserted.getIdempotencyKey()).isNull();
        assertThat(inserted.getRequestHash()).isNull();

        assertThat(result.created()).isTrue();
        assertThat(result.order().getId()).isEqualTo("new-id");
        verify(orderRepository, never()).findByIdempotencyKey(anyString());
    }

    @Test
    void createWithNewKeyStoresKeyAndRequestHash() {
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        insertAssignsId();

        CreateOrderResult result = orderService.create(validRequest(), KEY);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).insert(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(captor.getValue().getRequestHash()).isEqualTo(requestHasher.hash(validRequest()));
        assertThat(result.created()).isTrue();
    }

    @Test
    void sameKeySamePayloadReturnsOriginalWithoutInsert() {
        Order existing = existingOrder(validRequest());
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        CreateOrderResult result = orderService.create(validRequest(), KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(existing);
        verify(orderRepository, never()).insert(any(Order.class));
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existingOrder(validRequest())));

        CreateOrderRequest different = request("cust-1", item("p1", 3, "49.99"));

        assertThatThrownBy(() -> orderService.create(different, KEY))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        verify(orderRepository, never()).insert(any(Order.class));
    }

    @Test
    void lostInsertRaceWithSamePayloadReturnsWinner() {
        Order winner = existingOrder(validRequest());
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(orderRepository.insert(any(Order.class))).thenThrow(new DuplicateKeyException("E11000"));

        CreateOrderResult result = orderService.create(validRequest(), KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(winner);
    }

    @Test
    void lostInsertRaceWithDifferentPayloadIsRejected() {
        Order winner = existingOrder(request("cust-1", item("p9", 1, "5.00")));
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(orderRepository.insert(any(Order.class))).thenThrow(new DuplicateKeyException("E11000"));

        assertThatThrownBy(() -> orderService.create(validRequest(), KEY))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void duplicateKeyWithoutWinnerFailsLoudly() {
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.empty());
        when(orderRepository.insert(any(Order.class))).thenThrow(new DuplicateKeyException("E11000"));

        assertThatThrownBy(() -> orderService.create(validRequest(), KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void getOrderCacheHitDoesNotTouchMongo() {
        OrderResponse cached = orderMapper.toResponse(existingOrder(validRequest()));
        when(orderCache.get("existing-id")).thenReturn(Optional.of(cached));

        assertThat(orderService.getOrder("existing-id")).isSameAs(cached);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void getOrderCacheMissReadsMongoAndCachesResponse() {
        Order order = existingOrder(validRequest());
        when(orderCache.get("existing-id")).thenReturn(Optional.empty());
        when(orderRepository.findById("existing-id")).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder("existing-id");

        assertThat(response).isEqualTo(orderMapper.toResponse(order));
        verify(orderCache).put(response);
    }

    @Test
    void getOrderMissingThrowsNotFoundAndCachesNothing() {
        when(orderCache.get("missing")).thenReturn(Optional.empty());
        when(orderRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder("missing"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("missing");
        verify(orderCache, never()).put(any());
    }

    @Test
    void createTruncatesCreatedAtToMillis() {
        insertAssignsId();
        Instant withNanos = Instant.parse("2026-11-19T10:00:00.123456789Z");

        serviceAt(withNanos).create(validRequest(), null);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).insert(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(Instant.parse("2026-11-19T10:00:00.123Z"));
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(Instant.parse("2026-11-19T10:00:00.123Z"));
    }

    // --- list ---

    private static Order orderAt(String id, Instant createdAt) {
        return Order.builder().id(id).customerId("c").status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ONE).createdAt(createdAt).updatedAt(createdAt).version(0L).build();
    }

    private static final String ID_1 = "6740000000000000000000a1";
    private static final String ID_2 = "6740000000000000000000a2";
    private static final String ID_3 = "6740000000000000000000a3";

    @Test
    void listFetchesOneExtraRowAndReturnsNullCursorOnLastPage() {
        List<Order> rows = List.of(orderAt(ID_2, NOW), orderAt(ID_1, NOW.minusSeconds(1)));
        when(orderRepository.findPage(null, null, 3)).thenReturn(rows);

        OrderPage page = orderService.list(null, 2, null);

        assertThat(page.items()).containsExactlyElementsOf(rows);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void listTrimsExtraRowAndBuildsCursorFromLastReturnedItem() {
        Order newest = orderAt(ID_3, NOW);
        Order middle = orderAt(ID_2, NOW.minusSeconds(1));
        Order extra = orderAt(ID_1, NOW.minusSeconds(2));
        when(orderRepository.findPage(OrderStatus.PENDING, null, 3)).thenReturn(List.of(newest, middle, extra));

        OrderPage page = orderService.list(OrderStatus.PENDING, 2, null);

        assertThat(page.items()).containsExactly(newest, middle);
        assertThat(cursorCodec.decode(page.nextCursor()))
                .isEqualTo(new OrderCursor(middle.getCreatedAt(), middle.getId()));
    }

    @Test
    void listDecodesCursorAndPassesItToRepository() {
        OrderCursor cursor = new OrderCursor(NOW, ID_2);
        when(orderRepository.findPage(OrderStatus.SHIPPED, cursor, 11)).thenReturn(List.of());

        OrderPage page = orderService.list(OrderStatus.SHIPPED, 10, cursorCodec.encode(cursor));

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void listWithMalformedCursorFailsBeforeQuerying() {
        assertThatThrownBy(() -> orderService.list(null, 10, "not*a*cursor"))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(orderRepository);
    }

    // --- cancel / update status ---

    private static Order withStatus(OrderStatus status) {
        return orderAt(ID_1, NOW).toBuilder().status(status).build();
    }

    @Test
    void cancelUsesConditionalUpdateFromPendingAndPublishesEvent() {
        Order cancelled = withStatus(OrderStatus.CANCELLED);
        when(orderRepository.transitionStatus(ID_1, OrderStatus.PENDING, OrderStatus.CANCELLED, NOW))
                .thenReturn(Optional.of(cancelled));

        Order result = orderService.cancel(ID_1);

        assertThat(result).isSameAs(cancelled);
        verify(eventPublisher).publishEvent(
                new OrderStatusChangedEvent(ID_1, OrderStatus.PENDING, OrderStatus.CANCELLED, NOW));
        verify(orderRepository, never()).findById(anyString());
    }

    @Test
    void cancelWhenNotPendingIsConflictWithCurrentStatusAndNoEvent() {
        when(orderRepository.transitionStatus(ID_1, OrderStatus.PENDING, OrderStatus.CANCELLED, NOW))
                .thenReturn(Optional.empty());
        when(orderRepository.findById(ID_1)).thenReturn(Optional.of(withStatus(OrderStatus.SHIPPED)));

        assertThatThrownBy(() -> orderService.cancel(ID_1))
                .isInstanceOfSatisfying(InvalidStatusTransitionException.class, e -> {
                    assertThat(e.getCurrentStatus()).isEqualTo(OrderStatus.SHIPPED);
                    assertThat(e.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
                });
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelMissingOrderIsNotFound() {
        when(orderRepository.transitionStatus(ID_1, OrderStatus.PENDING, OrderStatus.CANCELLED, NOW))
                .thenReturn(Optional.empty());
        when(orderRepository.findById(ID_1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancel(ID_1)).isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateStatusFiltersOnRequiredPredecessor() {
        when(orderRepository.transitionStatus(ID_1, OrderStatus.PENDING, OrderStatus.PROCESSING, NOW))
                .thenReturn(Optional.of(withStatus(OrderStatus.PROCESSING)));
        when(orderRepository.transitionStatus(ID_1, OrderStatus.PROCESSING, OrderStatus.SHIPPED, NOW))
                .thenReturn(Optional.of(withStatus(OrderStatus.SHIPPED)));
        when(orderRepository.transitionStatus(ID_1, OrderStatus.SHIPPED, OrderStatus.DELIVERED, NOW))
                .thenReturn(Optional.of(withStatus(OrderStatus.DELIVERED)));

        assertThat(orderService.updateStatus(ID_1, OrderStatus.PROCESSING).getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(orderService.updateStatus(ID_1, OrderStatus.SHIPPED).getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(orderService.updateStatus(ID_1, OrderStatus.DELIVERED).getStatus()).isEqualTo(OrderStatus.DELIVERED);

        verify(eventPublisher).publishEvent(
                new OrderStatusChangedEvent(ID_1, OrderStatus.PROCESSING, OrderStatus.SHIPPED, NOW));
    }

    @Test
    void updateStatusToPendingOrCancelledIsRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> orderService.updateStatus(ID_1, OrderStatus.PENDING))
                .isInstanceOf(StatusNotPatchableException.class);
        assertThatThrownBy(() -> orderService.updateStatus(ID_1, OrderStatus.CANCELLED))
                .isInstanceOf(StatusNotPatchableException.class)
                .hasMessageContaining("/api/v1/orders/" + ID_1 + "/cancel");
        verifyNoInteractions(orderRepository, eventPublisher);
    }

    @Test
    void updateStatusConflictReportsCurrentStatus() {
        when(orderRepository.transitionStatus(ID_1, OrderStatus.PROCESSING, OrderStatus.SHIPPED, NOW))
                .thenReturn(Optional.empty());
        when(orderRepository.findById(ID_1)).thenReturn(Optional.of(withStatus(OrderStatus.PENDING)));

        assertThatThrownBy(() -> orderService.updateStatus(ID_1, OrderStatus.SHIPPED))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("is PENDING")
                .hasMessageContaining("SHIPPED");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateStatusMissingOrderIsNotFound() {
        when(orderRepository.transitionStatus(ID_1, OrderStatus.SHIPPED, OrderStatus.DELIVERED, NOW))
                .thenReturn(Optional.empty());
        when(orderRepository.findById(ID_1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.updateStatus(ID_1, OrderStatus.DELIVERED))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
