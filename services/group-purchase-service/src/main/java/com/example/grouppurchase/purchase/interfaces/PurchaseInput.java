package com.example.grouppurchase.purchase.interfaces;

import com.example.grouppurchase.purchase.application.PurchaseChange;
import com.example.grouppurchase.purchase.domain.SellingTerms;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.example.grouppurchase.purchase.application.PurchaseRequestFailure.*;

final class PurchaseInput {
    private static final String VERSION = "draftVersion";
    private static final Set<String> TERMS = Set.of(
            "productName", "productDescription", "unitPrice", "baseQuantity", "finalDeadline");
    private static final Pattern DEADLINE = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?(Z|[+-][0-9]{2}:[0-9]{2})");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private PurchaseInput() { }

    static SellingTerms register(byte[] body) {
        return terms(object(body, true, false));
    }

    static PurchaseChange revise(String id, byte[] body) {
        var fields = object(body, true, true);
        if (!TERMS.stream().allMatch(fields::has)) throw INVALID_REQUEST.exception();
        return new PurchaseChange(PurchaseChange.Operation.REVISE, id(id), version(fields), terms(fields));
    }

    static PurchaseChange publish(String id, byte[] body) {
        return new PurchaseChange(PurchaseChange.Operation.PUBLISH, id(id),
                version(object(body, false, true)), null);
    }

    static UUID id(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equals(value)) return id;
        } catch (IllegalArgumentException invalid) {
            throw NOT_FOUND.exception();
        }
        throw NOT_FOUND.exception();
    }

    static String key(List<String> values) {
        if (values.size() != 1 || !KEY.matcher(values.getFirst()).matches()) throw INVALID_REQUEST.exception();
        return values.getFirst();
    }

    static PageRequest page(MultiValueMap<String, String> parameters) {
        String requested = single(parameters, "limit");
        if (requested == null) return new PageRequest(20, single(parameters, "cursor"));
        if (!requested.matches("[0-9]{1,3}")) throw INVALID_REQUEST.exception();
        int limit = Integer.parseInt(requested);
        if (limit < 1 || limit > 100) throw INVALID_REQUEST.exception();
        return new PageRequest(limit, single(parameters, "cursor"));
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null) return null;
        if (values.size() != 1) throw INVALID_REQUEST.exception();
        return values.getFirst();
    }

    private static JsonNode object(byte[] body, boolean allowTerms, boolean allowVersion) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw INVALID_REQUEST.exception();
            for (String name : node.propertyNames()) {
                if (allowVersion && VERSION.equals(name)) continue;
                if (!allowTerms || !TERMS.contains(name)) throw INVALID_REQUEST.exception();
            }
            return node;
        } catch (JacksonException invalid) {
            throw INVALID_REQUEST.exception();
        }
    }

    private static SellingTerms terms(JsonNode node) {
        try {
            Long baseQuantity = integer(node.get("baseQuantity"));
            return new SellingTerms(text(node.get("productName")), text(node.get("productDescription")),
                    integer(node.get("unitPrice")), baseQuantity == null ? null : Math.toIntExact(baseQuantity),
                    deadline(node.get("finalDeadline")));
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException invalid) {
            throw INVALID_REQUEST.exception();
        }
    }

    private static Instant deadline(JsonNode node) {
        String value = text(node);
        if (value == null) return null;
        if (!DEADLINE.matcher(value).matches()) throw INVALID_REQUEST.exception();
        return OffsetDateTime.parse(value).toInstant();
    }

    private static long version(JsonNode node) {
        Long value = integer(node.get(VERSION));
        if (value == null || value < 1) throw INVALID_REQUEST.exception();
        return value;
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw INVALID_REQUEST.exception();
        return value.stringValue();
    }

    private static Long integer(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw INVALID_REQUEST.exception();
        return value.longValue();
    }

    record PageRequest(int limit, String cursor) { }
}
