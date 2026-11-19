package com.peerislands.orders.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peerislands.orders.controller.dto.CreateOrderRequest;
import com.peerislands.orders.controller.dto.OrderItemRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 (hex) of a canonical form of a validated create request, used to detect an
 * Idempotency-Key being reused with a different payload.
 *
 * <p>Canonical form: JSON with sorted keys and no whitespace; items sorted by productId (unique per
 * order); prices as plain strings with trailing zeros stripped ({@code 10.5} == {@code 10.50}).
 */
@Component
public class RequestHasher {

    // Private plain mapper: canonicalization must not depend on the app's Jackson configuration.
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

    public String hash(CreateOrderRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("customerId", request.customerId());
        canonical.put("items", request.items().stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .map(RequestHasher::canonicalItem)
                .toList());
        return sha256Hex(toJson(canonical));
    }

    private static Map<String, Object> canonicalItem(OrderItemRequest item) {
        Map<String, Object> map = new TreeMap<>();
        map.put("productId", item.productId());
        map.put("productName", item.productName());
        map.put("quantity", item.quantity());
        map.put("unitPrice", item.unitPrice().stripTrailingZeros().toPlainString());
        return map;
    }

    private static String toJson(Map<String, Object> canonical) {
        try {
            return CANONICAL_JSON.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize canonical request", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
