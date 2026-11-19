package com.peerislands.orders.controller.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.peerislands.orders.TestOrders.item;
import static com.peerislands.orders.TestOrders.request;
import static com.peerislands.orders.TestOrders.validRequest;
import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<String> violatedPaths(CreateOrderRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static CreateOrderRequest withItem(OrderItemRequest item) {
        return CreateOrderRequest.builder().customerId("cust-1").items(List.of(item)).build();
    }

    private static OrderItemRequest itemWithPrice(String price) {
        return OrderItemRequest.builder().productId("p1").productName("n").quantity(1)
                .unitPrice(price == null ? null : new BigDecimal(price)).build();
    }

    private static OrderItemRequest itemWithQuantity(Integer quantity) {
        return OrderItemRequest.builder().productId("p1").productName("n").quantity(quantity)
                .unitPrice(BigDecimal.ONE).build();
    }

    @Test
    void validRequestHasNoViolations() {
        assertThat(violatedPaths(validRequest())).isEmpty();
    }

    // --- customerId ---

    @Test
    void customerIdIsRequiredAndNotBlank() {
        assertThat(violatedPaths(request(null, item("p1", 1, "1")))).containsExactly("customerId");
        assertThat(violatedPaths(request("   ", item("p1", 1, "1")))).containsExactly("customerId");
    }

    @Test
    void customerIdMax64Chars() {
        assertThat(violatedPaths(request("c".repeat(64), item("p1", 1, "1")))).isEmpty();
        assertThat(violatedPaths(request("c".repeat(65), item("p1", 1, "1")))).containsExactly("customerId");
    }

    // --- items ---

    @Test
    void itemsAreRequiredAndNotEmpty() {
        assertThat(violatedPaths(CreateOrderRequest.builder().customerId("c").items(null).build()))
                .containsExactly("items");
        assertThat(violatedPaths(CreateOrderRequest.builder().customerId("c").items(List.of()).build()))
                .containsExactly("items");
    }

    @Test
    void itemsBetween1And50() {
        assertThat(violatedPaths(withItems(50))).isEmpty();
        assertThat(violatedPaths(withItems(51))).containsExactly("items");
    }

    private static CreateOrderRequest withItems(int count) {
        List<OrderItemRequest> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(item("p" + i, 1, "1.00"));
        }
        return CreateOrderRequest.builder().customerId("c").items(items).build();
    }

    @Test
    void nullItemIsRejected() {
        List<OrderItemRequest> items = new ArrayList<>();
        items.add(null);
        assertThat(violatedPaths(CreateOrderRequest.builder().customerId("c").items(items).build()))
                .singleElement().asString().startsWith("items[0]");
    }

    @Test
    void duplicateProductIdIsRejected() {
        assertThat(violatedPaths(request("c", item("p1", 1, "1"), item("p1", 2, "2"))))
                .containsExactly("productIdsUnique");
    }

    // --- productId / productName ---

    @Test
    void productIdRequiredNotBlankMax64() {
        assertThat(violatedPaths(withItem(item(" ", 1, "1")))).containsExactly("items[0].productId");
        assertThat(violatedPaths(withItem(item("p".repeat(64), 1, "1")))).isEmpty();
        assertThat(violatedPaths(withItem(item("p".repeat(65), 1, "1")))).containsExactly("items[0].productId");
    }

    @Test
    void productNameRequiredNotBlankMax200() {
        OrderItemRequest blank = OrderItemRequest.builder().productId("p1").productName("")
                .quantity(1).unitPrice(BigDecimal.ONE).build();
        OrderItemRequest max = blank.toBuilder().productName("n".repeat(200)).build();
        OrderItemRequest tooLong = blank.toBuilder().productName("n".repeat(201)).build();

        assertThat(violatedPaths(withItem(blank))).containsExactly("items[0].productName");
        assertThat(violatedPaths(withItem(max))).isEmpty();
        assertThat(violatedPaths(withItem(tooLong))).containsExactly("items[0].productName");
    }

    // --- quantity ---

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void quantityBoundariesAccepted(int quantity) {
        assertThat(violatedPaths(withItem(itemWithQuantity(quantity)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void quantityOutOfRangeRejected(int quantity) {
        assertThat(violatedPaths(withItem(itemWithQuantity(quantity)))).containsExactly("items[0].quantity");
    }

    @Test
    void quantityRequired() {
        assertThat(violatedPaths(withItem(itemWithQuantity(null)))).containsExactly("items[0].quantity");
    }

    // --- unitPrice ---

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "19.99", "10.5", "1000000", "1000000.00"})
    void unitPriceAccepted(String price) {
        assertThat(violatedPaths(withItem(itemWithPrice(price)))).isEmpty();
    }

    /** "10.500" is rejected too: at most 2 decimal places as written (strict, never rounded). */
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "1000000.01", "1.001", "0.001", "10.500"})
    void unitPriceRejected(String price) {
        assertThat(violatedPaths(withItem(itemWithPrice(price)))).containsExactly("items[0].unitPrice");
    }

    @Test
    void unitPriceRequired() {
        assertThat(violatedPaths(withItem(itemWithPrice(null)))).containsExactly("items[0].unitPrice");
    }
}
