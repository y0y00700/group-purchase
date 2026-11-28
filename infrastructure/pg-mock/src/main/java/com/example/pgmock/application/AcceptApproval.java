package com.example.pgmock.application;

import com.example.pgmock.domain.*;

public class AcceptApproval {
    private final PgStore store;
    private final RetryPgOperation retry;
    public AcceptApproval(PgStore store,RetryPgOperation retry) { this.store=store; this.retry=retry; }
    public Accepted accept(PgCommand command) {
        return store.transaction(()-> {
            Scenario scenario=store.lockScenario(command.kind(),command.businessId());
            PgOperation keyed=store.byKey(command.kind(),command.key());
            if (keyed!=null) keyed.match(command);
            PgOperation existing=store.byBusiness(command.kind(),command.businessId(),true);
            if (existing!=null) return new Accepted(retry.retryOrReturn(existing,command),0,false);
            return new Accepted(store.insert(command,scenario,null),scenario.firstResponseDelayMs(),true);
        });
    }
}
