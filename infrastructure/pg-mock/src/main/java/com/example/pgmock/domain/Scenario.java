package com.example.pgmock.domain;

import java.util.UUID;

public record Scenario(long processingDelayMs,long firstResponseDelayMs,String approvalDecision,int transientFailures,UUID operationId) {
    public void validate(String kind) {
        if (!java.util.Set.of("APPROVAL","REFUND").contains(kind) || processingDelayMs<0 || processingDelayMs>30000
                || firstResponseDelayMs<0 || firstResponseDelayMs>10000 || transientFailures<0 || transientFailures>3
                || !java.util.Set.of("APPROVE","DECLINE").contains(approvalDecision)
                || (kind.equals("REFUND") && !approvalDecision.equals("APPROVE"))) throw new PgFailure(400,"INVALID_REQUEST");
    }
    public boolean same(Scenario other) {
        return processingDelayMs==other.processingDelayMs && firstResponseDelayMs==other.firstResponseDelayMs
                && approvalDecision.equals(other.approvalDecision) && transientFailures==other.transientFailures;
    }
}
