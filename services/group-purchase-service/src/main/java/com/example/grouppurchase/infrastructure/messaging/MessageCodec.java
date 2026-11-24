package com.example.grouppurchase.infrastructure.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.example.grouppurchase.infrastructure.messaging.ContractFailure.Code.*;

/** Strict common v1 envelope. Payload business rules belong to later handlers. */
public final class MessageCodec {
    public static final int MAX_BYTES = 65536;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    public record Decoded(MessageKind kind, JsonNode envelope, byte[] raw) {
        public Decoded { envelope = envelope.deepCopy(); raw = raw.clone(); }
        @Override public byte[] raw() { return raw.clone(); }
        @Override public JsonNode envelope() { return envelope.deepCopy(); }
    }

    public Decoded decode(byte[] raw) {
        if (raw == null) throw new ContractFailure(MALFORMED_JSON, "null bytes");
        if (raw.length > MAX_BYTES) throw new ContractFailure(TOO_LARGE, "64 KiB");
        JsonNode node;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw));
            node = JSON.readTree(raw);
        } catch (Exception e) { throw new ContractFailure(MALFORMED_JSON, e.getClass().getSimpleName()); }
        object(node, "envelope");
        JsonNode version = node.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) invalid("schemaVersion");
        if (version.intValue() != 1) throw new ContractFailure(UNSUPPORTED_VERSION, version.toString());
        MessageKind kind = MessageKind.registered(text(node, "messageType"));
        validate(node, kind);
        return new Decoded(kind, node, raw);
    }

    public void validate(JsonNode node, MessageKind kind) {
        uuid(node, "messageId");
        uuid(node, "correlationId");
        if (node.has("causationId")) uuid(node, "causationId");
        String time = text(node, "occurredAt");
        try {
            if (!time.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z")
                    || !TIME.format(Instant.parse(time)).equals(time)) invalid("occurredAt");
        } catch (java.time.DateTimeException e) { invalid("occurredAt"); }
        JsonNode context = node.get("context");
        object(context, "context");
        uuid(context, "groupPurchaseId");
        if (kind.payment) { uuid(context, "participationId"); uuid(context, "paymentAttemptId"); }
        if (kind.refund) uuid(context, "refundId");
        for (String optional : new String[]{"participationId", "paymentAttemptId", "refundId"}) {
            if (context.has(optional)) uuid(context, optional);
        }
        JsonNode sequence = node.get("sequence");
        if (kind.payment || node.has("sequence")) {
            if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()
                    || sequence.longValue() < 1) invalid("sequence");
            if (!kind.payment) invalid("notification sequence is not defined in v1");
        }
        object(node.get("payload"), "payload");
    }

    public Decoded receive(byte[] raw, String topic, String key) {
        Decoded decoded = decode(raw);
        decoded.kind().key(decoded.envelope(), topic, key);
        return decoded;
    }

    /** Decode first, then produce canonical UTC microseconds; unknown optional fields survive. */
    public byte[] encode(JsonNode envelope) {
        byte[] raw = JSON.writeValueAsBytes(envelope);
        decode(raw);
        return raw;
    }
    private static void object(JsonNode n, String field) { if (n == null || !n.isObject()) invalid(field); }
    private static String text(JsonNode n, String field) {
        JsonNode value = n.get(field);
        if (value == null || !value.isString()) invalid(field);
        return value.asString();
    }
    private static void uuid(JsonNode node, String field) {
        String value = text(node, field);
        try { if (UUID.fromString(value).toString().equals(value)) return; }
        catch (IllegalArgumentException ignored) { }
        invalid(field);
    }
    private static void invalid(String field) { throw new ContractFailure(INVALID_FIELD, field); }
}
