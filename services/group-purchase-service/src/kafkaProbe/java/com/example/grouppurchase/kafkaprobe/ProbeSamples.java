package com.example.grouppurchase.kafkaprobe;

import com.example.grouppurchase.infrastructure.messaging.*;
import java.nio.charset.StandardCharsets;

/** Transport fixtures only. These payloads are NOT final payment business contracts. */
public final class ProbeSamples {
    public static final String PURCHASE = "11111111-1111-4111-8111-111111111111";
    public static final String ATTEMPT = "22222222-2222-4222-8222-222222222222";
    private ProbeSamples() { }
    public static byte[] sample(MessageKind kind, long sequence) {
        String payment = kind == MessageKind.NotificationRequested ? "" :
                ",\"participationId\":\"33333333-3333-4333-8333-333333333333\",\"paymentAttemptId\":\"" + ATTEMPT + "\"";
        String refund = kind.name().contains("Refund") ? ",\"refundId\":\"44444444-4444-4444-8444-444444444444\"" : "";
        String order = kind == MessageKind.NotificationRequested ? "" : ",\"sequence\":" + sequence;
        MessageKind causeKind = kind == MessageKind.PaymentRefundResultReported
                ? MessageKind.PaymentRefundRequested : MessageKind.PaymentApprovalRequested;
        String cause = kind.topic().contains("results") ? ",\"causationId\":\"" + id(causeKind,
                causeKind == MessageKind.PaymentRefundRequested ? 3 : 1) + "\"" : "";
        return ("{\"messageId\":\"" + id(kind, sequence) + "\",\"messageType\":\"" + kind.name()
                + "\",\"schemaVersion\":1,\"occurredAt\":\"2026-11-24T00:00:00.000000Z\","
                + "\"correlationId\":\"66666666-6666-4666-8666-666666666666\"" + cause
                + ",\"context\":{\"groupPurchaseId\":\"" + PURCHASE + "\"" + payment + refund + "}" + order
                + ",\"payload\":{\"transportFixture\":true,\"amount\":1000,\"currency\":\"KRW\","
                + "\"approvalDeadline\":\"2026-11-25T00:00:00.000000Z\",\"statusExample\":\"PENDING\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }
    private static String id(MessageKind kind, long sequence) {
        return java.util.UUID.nameUUIDFromBytes((kind.name() + ":" + sequence).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static byte[] forAttempt(String attempt, long sequence) {
        String value = new String(sample(MessageKind.PaymentApprovalRequested, sequence), StandardCharsets.UTF_8);
        String messageId = java.util.UUID.nameUUIDFromBytes((attempt + ":" + sequence).getBytes(StandardCharsets.UTF_8)).toString();
        return value.replace(ATTEMPT, attempt).replace(id(MessageKind.PaymentApprovalRequested, sequence), messageId).getBytes(StandardCharsets.UTF_8);
    }
    public static String key(MessageKind kind) { return kind == MessageKind.NotificationRequested ? PURCHASE : ATTEMPT; }
}
