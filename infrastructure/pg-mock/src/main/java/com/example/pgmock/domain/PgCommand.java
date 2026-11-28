package com.example.pgmock.domain;

import java.util.UUID;

public record PgCommand(String kind,UUID businessId,UUID paymentAttemptId,long amount,String currency,String key) {
    public PgCommand {
        if (!java.util.Set.of("APPROVAL","REFUND").contains(kind) || businessId==null || paymentAttemptId==null
                || amount<=0 || !"KRW".equals(currency) || key==null || !key.matches("[A-Za-z0-9_.:-]{16,128}"))
            throw new PgFailure(400,"INVALID_REQUEST");
        if (kind.equals("APPROVAL") && !businessId.equals(paymentAttemptId)) throw new PgFailure(400,"INVALID_REQUEST");
    }
}
