package com.peerislands.orders.mapper;

import com.peerislands.orders.exception.InvalidCursorException;
import com.peerislands.orders.repository.OrderCursor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Opaque pagination cursor: URL-safe Base64 (no padding) of the UTF-8 text
 * {@code "<createdAt epoch millis>:<24-hex ObjectId>"}.
 */
@Component
public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Pattern MILLIS = Pattern.compile("\\d{1,19}");
    private static final char SEPARATOR = ':';

    public String encode(OrderCursor cursor) {
        String text = cursor.createdAt().toEpochMilli() + String.valueOf(SEPARATOR) + cursor.id();
        return ENCODER.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public OrderCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidCursorException("empty");
        }
        String text;
        try {
            text = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("not URL-safe Base64");
        }

        int separator = text.indexOf(SEPARATOR);
        if (separator < 0 || separator != text.lastIndexOf(SEPARATOR)) {
            throw new InvalidCursorException("unexpected structure");
        }
        String millisPart = text.substring(0, separator);
        String idPart = text.substring(separator + 1);

        if (!MILLIS.matcher(millisPart).matches()) {
            throw new InvalidCursorException("invalid timestamp");
        }
        long millis;
        try {
            millis = Long.parseLong(millisPart);
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("invalid timestamp");
        }
        if (!ObjectId.isValid(idPart)) {
            throw new InvalidCursorException("invalid id");
        }
        return new OrderCursor(Instant.ofEpochMilli(millis), idPart);
    }
}
