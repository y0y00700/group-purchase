package com.example.grouppurchase.infrastructure.messaging;

import static com.example.grouppurchase.infrastructure.messaging.ContractFailure.Code.*;
import tools.jackson.databind.JsonNode;

public enum MessageKind {
    PaymentApprovalRequested("gp.payment.commands.v1", true, false),
    PaymentApprovalStopRequested("gp.payment.commands.v1", true, false),
    PaymentRefundRequested("gp.payment.commands.v1", true, true),
    PaymentApprovalResultReported("gp.payment.results.v1", true, false),
    PaymentRefundResultReported("gp.payment.results.v1", true, true),
    NotificationRequested("gp.notification.requests.v1", false, false);

    private final String topic;
    final boolean payment;
    final boolean refund;
    MessageKind(String topic, boolean payment, boolean refund) {
        this.topic = topic; this.payment = payment; this.refund = refund;
    }
    public String topic() { return topic; }
    public static MessageKind registered(String name) {
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { throw new ContractFailure(UNSUPPORTED_TYPE, name); }
    }

    public String key(JsonNode envelope, String actualTopic, String actualKey) {
        String expected = envelope.get("context").get(payment ? "paymentAttemptId" : "groupPurchaseId").asString();
        if (!topic.equals(actualTopic)) throw new ContractFailure(TOPIC_MISMATCH, actualTopic);
        if (!expected.equals(actualKey)) throw new ContractFailure(KEY_MISMATCH, String.valueOf(actualKey));
        return expected;
    }

}
