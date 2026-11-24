package com.example.grouppurchase.purchase.interfaces;

import com.example.grouppurchase.purchase.application.PurchaseChange;
import com.example.grouppurchase.purchase.domain.SellingTerms;
import com.example.grouppurchase.web.ApiInput;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

import static com.example.grouppurchase.application.RequestFailure.INVALID_REQUEST;
import static com.example.grouppurchase.web.ApiInput.id;

final class PurchaseInput {
    private static final String VERSION = "draftVersion";
    private static final Set<String> TERMS = Set.of(
            "productName", "productDescription", "unitPrice", "baseQuantity", "finalDeadline");
    private static final Pattern DEADLINE = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?(Z|[+-][0-9]{2}:[0-9]{2})");

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

    private static JsonNode object(byte[] body, boolean allowTerms, boolean allowVersion) {
        JsonNode node = ApiInput.object(body);
        for (String name : node.propertyNames()) {
            if (allowVersion && VERSION.equals(name)) continue;
            if (!allowTerms || !TERMS.contains(name)) throw INVALID_REQUEST.exception();
        }
        return node;
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
}
