package com.example.pgmock.application;

import com.example.pgmock.domain.*;

public class AcceptRefund {
    private final PgStore store;
    private final RetryPgOperation retry;
    public AcceptRefund(PgStore store,RetryPgOperation retry) { this.store=store; this.retry=retry; }
    public Accepted accept(PgCommand command) {
        return store.transaction(()-> {
            Scenario scenario=store.lockScenario(command.kind(),command.businessId());
            PgOperation keyed=store.byKey(command.kind(),command.key());
            if (keyed!=null) keyed.match(command);
            PgOperation known=store.byBusiness("REFUND",command.businessId(),false);
            if (known!=null) known.match(command);
            PgOperation approval=store.byBusiness("APPROVAL",command.paymentAttemptId(),true);
            if (approval==null || !approval.status().equals("SUCCEEDED")) throw PgFailure.conflict("REFUND_NOT_ALLOWED");
            if (approval.amount()!=command.amount() || !approval.currency().equals(command.currency()))
                throw PgFailure.conflict("REFUND_AMOUNT_MISMATCH");
            PgOperation existing=store.byBusiness("REFUND",command.businessId(),true);
            if (existing!=null) return new Accepted(retry.retryOrReturn(existing,command),0,false);
            if (store.refundFor(approval.operationId())!=null) throw PgFailure.conflict("FULL_REFUND_ALREADY_REGISTERED");
            return new Accepted(store.insert(command,scenario,approval.operationId()),scenario.firstResponseDelayMs(),true);
        });
    }
}
