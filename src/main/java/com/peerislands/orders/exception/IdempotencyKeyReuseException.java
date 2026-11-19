package com.peerislands.orders.exception;

/** The same Idempotency-Key was sent with a different request payload (422). */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException() {
        super("Idempotency-Key was already used with a different request payload");
    }
}
