package com.peerislands.orders.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.peerislands.orders.domain.OrderStatus.CANCELLED;
import static com.peerislands.orders.domain.OrderStatus.DELIVERED;
import static com.peerislands.orders.domain.OrderStatus.PENDING;
import static com.peerislands.orders.domain.OrderStatus.PROCESSING;
import static com.peerislands.orders.domain.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStatusTest {

    /** Expected transition table, written independently of the enum (from docs/DESIGN.md §4). */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
            PENDING, Set.of(PROCESSING, CANCELLED),
            PROCESSING, Set.of(SHIPPED),
            SHIPPED, Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of());

    static Stream<Arguments> allPairs() {
        return Stream.of(OrderStatus.values())
                .flatMap(from -> Stream.of(OrderStatus.values())
                        .map(to -> Arguments.of(from, to, EXPECTED.get(from).contains(to))));
    }

    static Stream<Arguments> validTransitions() {
        return allPairs().filter(a -> (boolean) a.get()[2]);
    }

    static Stream<Arguments> invalidTransitions() {
        return allPairs().filter(a -> !(boolean) a.get()[2]);
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("validTransitions")
    void validTransitionIsAllowed(OrderStatus from, OrderStatus to, boolean ignored) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("invalidTransitions")
    void invalidTransitionIsRejected(OrderStatus from, OrderStatus to, boolean ignored) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void tableCoversExactlyFourValidAndTwentyOneInvalidPairs() {
        // Valid: PENDING->PROCESSING, PENDING->CANCELLED, PROCESSING->SHIPPED, SHIPPED->DELIVERED
        assertThat(validTransitions().count()).isEqualTo(4);
        assertThat(invalidTransitions().count()).isEqualTo(21);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void allowedNextMatchesExpectedTable(OrderStatus status) {
        assertThat(status.allowedNext()).containsExactlyInAnyOrderElementsOf(EXPECTED.get(status));
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void allowedNextIsImmutable(OrderStatus status) {
        assertThatThrownBy(() -> status.allowedNext().add(PENDING))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED"})
    void terminalStatuses(OrderStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "PROCESSING", "SHIPPED"})
    void nonTerminalStatuses(OrderStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    @Test
    void requiredPredecessorOfEachTarget() {
        assertThat(OrderStatus.requiredPredecessorOf(PENDING)).isEmpty();
        assertThat(OrderStatus.requiredPredecessorOf(PROCESSING)).contains(PENDING);
        assertThat(OrderStatus.requiredPredecessorOf(SHIPPED)).contains(PROCESSING);
        assertThat(OrderStatus.requiredPredecessorOf(DELIVERED)).contains(SHIPPED);
        assertThat(OrderStatus.requiredPredecessorOf(CANCELLED)).contains(PENDING);
    }

    @Test
    void requiredPredecessorOfRejectsNull() {
        assertThatThrownBy(() -> OrderStatus.requiredPredecessorOf(null))
                .isInstanceOf(NullPointerException.class);
    }
}
