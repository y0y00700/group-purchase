package com.example.grouppurchase.outbox.application;

import java.util.Map;
import java.util.UUID;

/** Must join the caller's business transaction; never commits independently. */
public interface StoreOutgoingMessage {
    enum Kind { PaymentApprovalRequested, PaymentApprovalStopRequested, PaymentRefundRequested, NotificationRequested }
    record Input(Kind kind, UUID groupPurchaseId, UUID participationId, UUID paymentAttemptId,
            UUID refundId, UUID correlationId, UUID causationId, Map<String, Object> payload) { }
    record Stored(UUID messageId, Long sequence) { }
    Stored store(Input input);
}
