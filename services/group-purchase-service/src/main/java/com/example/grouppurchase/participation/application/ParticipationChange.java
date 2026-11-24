package com.example.grouppurchase.participation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public record ParticipationChange(Operation operation, UUID targetId, Integer quantity) {
    public enum Operation { APPLY, CANCEL }

    static final int VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    byte[] fingerprint(int version) {
        if (version != VERSION) throw new IllegalStateException("Unknown fingerprint version: " + version);
        var fields = switch (operation) {
            case APPLY -> List.of(targetId.toString(), quantity);
            case CANCEL -> List.of(targetId.toString());
        };
        try {
            return MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(fields));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Receipt(UUID id, int status, String body, String location) {
        static Receipt forChange(Operation operation, UUID id) {
            String path = "/api/participations/mine/" + id;
            boolean created = operation == Operation.APPLY;
            return new Receipt(id, created ? 201 : 200, JSON.writeValueAsString(new Body(id, path)),
                    created ? path : null);
        }

        public byte[] bodyBytes() {
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }

    private record Body(UUID id, String detailPath) { }
}
