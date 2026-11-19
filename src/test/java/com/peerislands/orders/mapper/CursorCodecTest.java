package com.peerislands.orders.mapper;

import com.peerislands.orders.exception.InvalidCursorException;
import com.peerislands.orders.repository.OrderCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private static final String ID = "674c1f2e9a1b2c3d4e5f6a7b";
    private static final Instant CREATED_AT = Instant.parse("2026-11-19T10:15:30.123Z");

    private final CursorCodec codec = new CursorCodec();

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTrip() {
        OrderCursor cursor = new OrderCursor(CREATED_AT, ID);

        assertThat(codec.decode(codec.encode(cursor))).isEqualTo(cursor);
    }

    @Test
    void encodedCursorIsUrlSafeBase64WithoutPadding() {
        String encoded = codec.encode(new OrderCursor(CREATED_AT, ID));

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void payloadIsEpochMillisColonObjectIdHex() {
        String encoded = codec.encode(new OrderCursor(CREATED_AT, ID));

        String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertThat(payload).isEqualTo(CREATED_AT.toEpochMilli() + ":" + ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "!!!", "abc$def", "a+b/c"})
    void rejectsNonBase64OrEmpty(String cursor) {
        assertThatThrownBy(() -> codec.decode(cursor)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsWrongStructure() {
        assertThatThrownBy(() -> codec.decode(b64("garbage"))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(b64(":" + ID))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(b64("123:"))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(b64("123:" + ID + ":x"))).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsInvalidTimestamp() {
        assertThatThrownBy(() -> codec.decode(b64("-5:" + ID))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(b64("12a:" + ID))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(b64("99999999999999999999:" + ID)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsInvalidObjectId() {
        assertThatThrownBy(() -> codec.decode(b64("123:" + "zz".repeat(12)))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(b64("123:abc"))).isInstanceOf(InvalidCursorException.class);
    }
}
