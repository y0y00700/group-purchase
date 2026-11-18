package com.example.grouppurchase.purchase.application;

import com.example.grouppurchase.purchase.domain.SellingTerms;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public record PurchaseChange(Operation operation, UUID targetId, Long draftVersion, SellingTerms terms) {

    public enum Operation { REGISTER, REVISE, PUBLISH }

    static final int FINGERPRINT_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static PurchaseChange register(SellingTerms terms) {
        return new PurchaseChange(Operation.REGISTER, null, null, terms);
    }

    byte[] fingerprint(int version) {
        if (version != 1) throw new IllegalStateException("Unknown fingerprint version: " + version);
        var fields = switch (operation) {
            case REGISTER -> Arrays.asList(terms.productName(), terms.productDescription(), terms.unitPrice(),
                    terms.baseQuantity(), deadlineMillis());
            case REVISE -> Arrays.asList(targetId.toString(), draftVersion, terms.productName(),
                    terms.productDescription(), terms.unitPrice(), terms.baseQuantity(), deadlineMillis());
            case PUBLISH -> Arrays.asList(targetId.toString(), draftVersion);
        };
        try {
            return MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(fields));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Long deadlineMillis() {
        return terms.finalDeadline() == null ? null : terms.finalDeadline().toEpochMilli();
    }

    public record Receipt(UUID id, int status, String body, String location) {
        static Receipt forChange(Operation operation, UUID id) {
            String path = "/api/group-purchases/mine/" + id;
            boolean created = operation == Operation.REGISTER;
            String body = JSON.writeValueAsString(new ResponseBody(id, path));
            return new Receipt(id, created ? 201 : 200, body, created ? path : null);
        }

        public byte[] bodyBytes() {
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }

    private record ResponseBody(UUID id, String detailPath) { }
}
