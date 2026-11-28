package com.example.pgmock.domain;

import java.time.Instant;
import java.util.UUID;

public record PgOperation(UUID operationId,String kind,UUID businessId,UUID paymentAttemptId,UUID approvalOperationId,
        long amount,String currency,String key,String status,int attempt,int remainingFailures,
        long processingDelayMs,String approvalDecision,Instant acceptedAt,Instant readyAt,Instant completedAt,String failureCode) {
    public void match(PgCommand command) {
        if (!key.equals(command.key())) throw PgFailure.conflict("BUSINESS_KEY_CONFLICT");
        if (!kind.equals(command.kind()) || !businessId.equals(command.businessId())
                || !paymentAttemptId.equals(command.paymentAttemptId()) || amount!=command.amount() || !currency.equals(command.currency()))
            throw PgFailure.conflict("IDEMPOTENCY_CONFLICT");
    }
}
