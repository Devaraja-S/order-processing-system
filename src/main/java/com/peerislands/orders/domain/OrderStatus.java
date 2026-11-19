package com.peerislands.orders.domain;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Order lifecycle (State pattern): each constant declares the statuses it may move to.
 *
 * <pre>
 * PENDING    -> PROCESSING, CANCELLED
 * PROCESSING -> SHIPPED
 * SHIPPED    -> DELIVERED
 * DELIVERED, CANCELLED -> terminal
 * </pre>
 *
 * <p>This enum only defines the rules. Status changes themselves are applied as conditional atomic
 * updates in MongoDB, filtered on {@link #requiredPredecessorOf(OrderStatus)}.
 */
public enum OrderStatus {

    PENDING {
        @Override
        public Set<OrderStatus> allowedNext() {
            return Set.of(PROCESSING, CANCELLED);
        }
    },
    PROCESSING {
        @Override
        public Set<OrderStatus> allowedNext() {
            return Set.of(SHIPPED);
        }
    },
    SHIPPED {
        @Override
        public Set<OrderStatus> allowedNext() {
            return Set.of(DELIVERED);
        }
    },
    DELIVERED {
        @Override
        public Set<OrderStatus> allowedNext() {
            return Set.of();
        }
    },
    CANCELLED {
        @Override
        public Set<OrderStatus> allowedNext() {
            return Set.of();
        }
    };

    /** Statuses this status may transition to (immutable). */
    public abstract Set<OrderStatus> allowedNext();

    public boolean canTransitionTo(OrderStatus target) {
        return allowedNext().contains(target);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /**
     * The single status an order must currently be in to move to {@code target}, derived from the
     * transition table. Empty for {@link #PENDING}, which is only ever an initial status.
     *
     * @throws IllegalStateException if the table ever gives a status more than one predecessor
     */
    public static Optional<OrderStatus> requiredPredecessorOf(OrderStatus target) {
        Objects.requireNonNull(target, "target");
        return Arrays.stream(values())
                .filter(status -> status.canTransitionTo(target))
                .reduce((a, b) -> {
                    throw new IllegalStateException(
                            "Status " + target + " has more than one predecessor: " + a + ", " + b);
                });
    }
}
