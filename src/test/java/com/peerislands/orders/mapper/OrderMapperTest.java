package com.peerislands.orders.mapper;

import com.peerislands.orders.controller.dto.OrderResponse;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.peerislands.orders.TestOrders.item;
import static com.peerislands.orders.TestOrders.request;
import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperTest {

    private final OrderMapper mapper = new OrderMapper();

    @Test
    void toItemsNormalizesPricesToScaleTwoWithoutRounding() {
        List<OrderItem> items = mapper.toItems(request("c",
                item("p1", 1, "10.5"), item("p2", 1, "10.500"), item("p3", 1, "7")));

        assertThat(items).extracting(OrderItem::unitPrice)
                .containsExactly(new BigDecimal("10.50"), new BigDecimal("10.50"), new BigDecimal("7.00"));
    }

    @Test
    void toItemsCopiesAllFields() {
        OrderItem mapped = mapper.toItems(request("c", item("p1", 3, "2.25"))).getFirst();

        assertThat(mapped.productId()).isEqualTo("p1");
        assertThat(mapped.productName()).isEqualTo("Product p1");
        assertThat(mapped.quantity()).isEqualTo(3);
    }

    @Test
    void toResponseExposesPublicFieldsOnly() {
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        Order order = Order.newPending("cust-1",
                        List.of(OrderItem.builder().productId("p1").productName("Keyboard").quantity(2)
                                .unitPrice(new BigDecimal("49.99")).build()),
                        "key", "hash", now)
                .withId("order-1").withVersion(3L);

        OrderResponse response = mapper.toResponse(order);

        assertThat(response.id()).isEqualTo("order-1");
        assertThat(response.customerId()).isEqualTo("cust-1");
        assertThat(response.totalAmount()).isEqualTo(new BigDecimal("99.98"));
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.updatedAt()).isEqualTo(now);
        assertThat(response.items()).singleElement().satisfies(i -> {
            assertThat(i.productId()).isEqualTo("p1");
            assertThat(i.productName()).isEqualTo("Keyboard");
            assertThat(i.quantity()).isEqualTo(2);
            assertThat(i.unitPrice()).isEqualTo(new BigDecimal("49.99"));
        });
    }
}
