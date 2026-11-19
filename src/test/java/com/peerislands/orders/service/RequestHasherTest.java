package com.peerislands.orders.service;

import org.junit.jupiter.api.Test;

import static com.peerislands.orders.TestOrders.item;
import static com.peerislands.orders.TestOrders.request;
import static com.peerislands.orders.TestOrders.validRequest;
import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher();

    @Test
    void hashIsDeterministic64CharLowercaseHex() {
        String hash = hasher.hash(validRequest());

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hasher.hash(validRequest())).isEqualTo(hash);
    }

    @Test
    void trailingZerosInPriceDoNotChangeHash() {
        assertThat(hasher.hash(request("c", item("p1", 1, "10.5"))))
                .isEqualTo(hasher.hash(request("c", item("p1", 1, "10.50"))));
    }

    @Test
    void itemOrderDoesNotChangeHash() {
        assertThat(hasher.hash(request("c", item("p1", 1, "1.00"), item("p2", 2, "2.00"))))
                .isEqualTo(hasher.hash(request("c", item("p2", 2, "2.00"), item("p1", 1, "1.00"))));
    }

    @Test
    void anyFieldChangeChangesHash() {
        String base = hasher.hash(request("c", item("p1", 1, "1.00")));

        assertThat(hasher.hash(request("other", item("p1", 1, "1.00")))).isNotEqualTo(base);
        assertThat(hasher.hash(request("c", item("p9", 1, "1.00")))).isNotEqualTo(base);
        assertThat(hasher.hash(request("c", item("p1", 2, "1.00")))).isNotEqualTo(base);
        assertThat(hasher.hash(request("c", item("p1", 1, "1.01")))).isNotEqualTo(base);
        assertThat(hasher.hash(request("c", item("p1", 1, "1.00"), item("p2", 1, "1.00")))).isNotEqualTo(base);
    }

    @Test
    void productNameChangeChangesHash() {
        var a = request("c", item("p1", 1, "1.00"));
        var b = request("c", item("p1", 1, "1.00").toBuilder().productName("Renamed").build());

        assertThat(hasher.hash(a)).isNotEqualTo(hasher.hash(b));
    }
}
