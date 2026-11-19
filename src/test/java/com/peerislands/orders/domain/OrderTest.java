package com.peerislands.orders.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static OrderItem item(String productId, int quantity, String unitPrice) {
        return OrderItem.builder()
                .productId(productId)
                .productName("Product " + productId)
                .quantity(quantity)
                .unitPrice(new BigDecimal(unitPrice))
                .build();
    }

    @Test
    void lineTotalIsQuantityTimesUnitPrice() {
        assertThat(item("p1", 3, "19.99").lineTotal()).isEqualByComparingTo("59.97");
    }

    @Test
    void totalSumsAllItems() {
        List<OrderItem> items = List.of(item("p1", 2, "10.50"), item("p2", 1, "0.99"), item("p3", 3, "100.00"));

        // 2 x 10.50 + 1 x 0.99 + 3 x 100.00 = 21.00 + 0.99 + 300.00
        assertThat(Order.calculateTotal(items)).isEqualTo(new BigDecimal("321.99"));
    }

    @Test
    void totalAlwaysHasScaleTwo() {
        assertThat(Order.calculateTotal(List.of(item("p1", 1, "5")))).isEqualTo(new BigDecimal("5.00"));
        assertThat(Order.calculateTotal(List.of(item("p1", 1, "5.5")))).isEqualTo(new BigDecimal("5.50"));
    }

    @Test
    void totalUsesHalfEvenRounding() {
        // HALF_UP would give 1.01 and 1.03; HALF_EVEN rounds ties to the even neighbour.
        assertThat(Order.calculateTotal(List.of(item("p1", 1, "1.005")))).isEqualTo(new BigDecimal("1.00"));
        assertThat(Order.calculateTotal(List.of(item("p1", 1, "1.025")))).isEqualTo(new BigDecimal("1.02"));
        assertThat(Order.calculateTotal(List.of(item("p1", 1, "1.015")))).isEqualTo(new BigDecimal("1.02"));
    }

    @Test
    void totalIsExactAtMaximumAllowedValues() {
        // 50 items x quantity 100 x unitPrice 999,999.99
        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(item("p" + i, 100, "999999.99"));
        }

        assertThat(Order.calculateTotal(items)).isEqualTo(new BigDecimal("4999999950.00"));
    }

    @Test
    void newPendingSetsDefaultsAndComputesTotal() {
        Instant now = Instant.parse("2026-11-19T10:15:30Z");
        List<OrderItem> items = List.of(item("p1", 2, "10.00"), item("p2", 1, "5.25"));

        Order order = Order.newPending("cust-1", items, "key-1", "hash-1", now);

        assertThat(order.getId()).isNull();
        assertThat(order.getVersion()).isNull();
        assertThat(order.getCustomerId()).isEqualTo("cust-1");
        assertThat(order.getItems()).containsExactlyElementsOf(items);
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("25.25"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(order.getRequestHash()).isEqualTo("hash-1");
        assertThat(order.getCreatedAt()).isEqualTo(now);
        assertThat(order.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void newPendingWithoutIdempotencyKey() {
        Order order = Order.newPending("cust-1", List.of(item("p1", 1, "1.00")), null, null, Instant.now());

        assertThat(order.getIdempotencyKey()).isNull();
        assertThat(order.getRequestHash()).isNull();
    }

    @Test
    void itemsAreDefensivelyCopiedAndUnmodifiable() {
        List<OrderItem> source = new ArrayList<>(List.of(item("p1", 1, "1.00")));
        Order order = Order.newPending("cust-1", source, null, null, Instant.now());

        source.add(item("p2", 1, "2.00"));

        assertThat(order.getItems()).hasSize(1);
        assertThatThrownBy(() -> order.getItems().add(item("p3", 1, "3.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
