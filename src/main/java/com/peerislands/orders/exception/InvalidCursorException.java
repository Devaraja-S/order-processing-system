package com.peerislands.orders.exception;

/** The pagination cursor is not one this API issued (400). */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String reason) {
        super("Malformed pagination cursor: " + reason);
    }
}
