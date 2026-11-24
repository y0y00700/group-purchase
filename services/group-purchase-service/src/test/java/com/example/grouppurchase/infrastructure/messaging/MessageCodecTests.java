package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.kafkaprobe.ProbeSamples;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.grouppurchase.infrastructure.messaging.ContractFailure.Code.*;

class MessageCodecTests {
    final MessageCodec codec = new MessageCodec();
    final JsonMapper json = new JsonMapper();
    byte[] valid() { return ProbeSamples.sample(MessageKind.PaymentApprovalRequested, 1); }
    ObjectNode node() { return (ObjectNode) json.readTree(valid()); }
    void fails(ContractFailure.Code code, byte[] bytes) {
        assertEquals(code, assertThrows(ContractFailure.class, () -> codec.decode(bytes)).code());
    }
    @ParameterizedTest @EnumSource(MessageKind.class)
    void allKindsRoundTripAndFixedCompatibilityFixtures(MessageKind kind) throws Exception {
        byte[] original;
        try (var input = getClass().getResourceAsStream("/messaging/" + kind.name() + ".json")) {
            assertNotNull(input); original = input.readAllBytes();
        }
        var decoded = codec.receive(original, kind.topic(), ProbeSamples.key(kind));
        assertEquals(kind, decoded.kind()); assertArrayEquals(original, decoded.raw());
        ObjectNode expanded = (ObjectNode) decoded.envelope();
        expanded.putObject("futureOptional").put("label", "추가");
        ((ObjectNode) expanded.get("context")).put("futureContext", true);
        ((ObjectNode) expanded.get("payload")).put("futurePayload", true);
        assertEquals(kind, codec.decode(codec.encode(expanded)).kind());
        byte[] copy = decoded.raw(); copy[0] = 0;
        assertArrayEquals(original, decoded.raw());
    }
    @Test void rejectsAllMissingNullAndWrongTypedCommonFields() {
        for (String field : List.of("messageId", "messageType", "schemaVersion", "occurredAt", "correlationId", "context", "sequence", "payload")) {
            ObjectNode missing = node(); missing.remove(field); fails(INVALID_FIELD, json.writeValueAsBytes(missing));
            ObjectNode nil = node(); nil.putNull(field); fails(INVALID_FIELD, json.writeValueAsBytes(nil));
            ObjectNode wrong = node(); wrong.putArray(field); fails(INVALID_FIELD, json.writeValueAsBytes(wrong));
        }
        for (String field : List.of("groupPurchaseId", "participationId", "paymentAttemptId")) {
            ObjectNode missing = node(); ((ObjectNode) missing.get("context")).remove(field); fails(INVALID_FIELD, json.writeValueAsBytes(missing));
            ObjectNode nil = node(); ((ObjectNode) nil.get("context")).putNull(field); fails(INVALID_FIELD, json.writeValueAsBytes(nil));
        }
    }
    @Test void rejectsMalformedDuplicateTrailingOversizeAndInvalidUtf8() {
        fails(MALFORMED_JSON, "{bad".getBytes(StandardCharsets.UTF_8));
        fails(MALFORMED_JSON, "{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8));
        fails(MALFORMED_JSON, (new String(valid(), StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8));
        fails(MALFORMED_JSON, new byte[]{(byte)0xc3, (byte)0x28});
        byte[] inside = valid().clone();
        // Malformed UTF-8 inside a JSON string must not be replaced silently.
        inside[15] = (byte) 0xff; fails(MALFORMED_JSON, inside);
        fails(TOO_LARGE, new byte[65537]);
        fails(INVALID_FIELD, "[]".getBytes(StandardCharsets.UTF_8));
    }
    @Test void rejectsVersionCoercionAndClassifiesUnsupportedVersionOrKind() {
        for (String version : List.of("\"1\"", "1.0", "null", "true", "9223372036854775808"))
            fails(INVALID_FIELD, new String(valid(), StandardCharsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":" + version).getBytes(StandardCharsets.UTF_8));
        ObjectNode n = node(); n.put("schemaVersion", 2); fails(UNSUPPORTED_VERSION, json.writeValueAsBytes(n));
        n = node(); n.put("messageType", "UnknownRequested"); fails(UNSUPPORTED_TYPE, json.writeValueAsBytes(n));
    }
    @Test void rejectsInvalidIdsTimesAndSequenceWithoutCoercion() {
        for (String id : List.of("ABCDEFAB-1111-4111-8111-111111111111", "1-1-1-1-1", "no-id")) {
            ObjectNode n = node(); n.put("messageId", id); fails(INVALID_FIELD, json.writeValueAsBytes(n));
        }
        for (String time : List.of("2026-11-24T00:00:00Z", "2026-11-24T09:00:00.000000+09:00", "2026-02-30T00:00:00.000000Z")) {
            ObjectNode n = node(); n.put("occurredAt", time); fails(INVALID_FIELD, json.writeValueAsBytes(n));
        }
        for (String sequence : List.of("0", "-1", "1.0", "\"1\"", "9223372036854775808"))
            fails(INVALID_FIELD, new String(valid(), StandardCharsets.UTF_8).replace("\"sequence\":1", "\"sequence\":" + sequence).getBytes(StandardCharsets.UTF_8));
        assertEquals(Long.MAX_VALUE, codec.decode(ProbeSamples.sample(MessageKind.PaymentApprovalRequested, Long.MAX_VALUE)).envelope().get("sequence").longValue());
        ObjectNode n = node(); n.putNull("causationId"); fails(INVALID_FIELD, json.writeValueAsBytes(n));
    }
    @Test void requiresRefundIdAndUsesOriginalApprovalAttemptAsKey() {
        for (MessageKind kind : List.of(MessageKind.PaymentRefundRequested, MessageKind.PaymentRefundResultReported)) {
            ObjectNode n = (ObjectNode) json.readTree(ProbeSamples.sample(kind, 1));
            codec.receive(codec.encode(n), kind.topic(), ProbeSamples.ATTEMPT);
            assertEquals(KEY_MISMATCH, assertThrows(ContractFailure.class, () -> codec.receive(codec.encode(n), kind.topic(), "44444444-4444-4444-8444-444444444444")).code());
            ((ObjectNode) n.get("context")).remove("refundId"); fails(INVALID_FIELD, json.writeValueAsBytes(n));
        }
        assertEquals(TOPIC_MISMATCH, assertThrows(ContractFailure.class, () -> codec.receive(valid(), MessageKind.NotificationRequested.topic(), ProbeSamples.ATTEMPT)).code());
        assertEquals(KEY_MISMATCH, assertThrows(ContractFailure.class, () -> codec.receive(valid(), MessageKind.PaymentApprovalRequested.topic(), null)).code());
        ObjectNode notification = (ObjectNode) json.readTree(ProbeSamples.sample(MessageKind.NotificationRequested, 1));
        notification.putNull("sequence"); fails(INVALID_FIELD, json.writeValueAsBytes(notification));
    }
}
