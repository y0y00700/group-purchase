package com.example.pgmock.application;

import com.example.pgmock.domain.PgOperation;
import java.time.Instant;
import java.util.UUID;

public record OperationView(UUID operationId,String kind,UUID paymentAttemptId,UUID refundId,UUID approvalOperationId,
        long amount,String currency,String status,Instant acceptedAt,Instant approvedAt,Instant refundedAt,String failureCode) {
    public static OperationView from(PgOperation op) {
        boolean approval=op.kind().equals("APPROVAL");
        return new OperationView(op.operationId(),op.kind(),op.paymentAttemptId(),approval?null:op.businessId(),op.approvalOperationId(),
            op.amount(),op.currency(),op.status(),op.acceptedAt(),approval?op.completedAt():null,approval?null:op.completedAt(),op.failureCode());
    }
}
