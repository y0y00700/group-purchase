package com.example.grouppurchase.purchase.domain;

import java.time.Instant;

public record SellingTerms(String productName, String productDescription, Long unitPrice,
        Integer baseQuantity, Instant finalDeadline) {

    public SellingTerms {
        productName = normalize(productName, 100);
        productDescription = normalize(productDescription, 2_000);
        if (unitPrice != null && (unitPrice < 1 || unitPrice > 1_000_000_000L)) {
            throw new IllegalArgumentException("Invalid unit price");
        }
        if (baseQuantity != null && (baseQuantity < 10 || baseQuantity > 1_000_000)) {
            throw new IllegalArgumentException("Invalid base quantity");
        }
        if (finalDeadline != null && finalDeadline.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("Invalid deadline precision");
        }
    }

    public boolean complete() {
        return productName != null && productDescription != null && unitPrice != null
                && baseQuantity != null && finalDeadline != null;
    }

    public Integer initialTarget() {
        return baseQuantity == null ? null : (baseQuantity * 6 + 4) / 5;
    }

    private static String normalize(String value, int maximum) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > maximum
                || trimmed.codePoints().anyMatch(point -> point == 0 || point >= 0xD800 && point <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid product text");
        }
        return trimmed;
    }
}
