package com.example.pgmock.application;

import com.example.pgmock.domain.*;
import java.util.UUID;

public class CompletePgOperation {
    private final PgStore store;
    public CompletePgOperation(PgStore store) { this.store=store; }
    public PgOperation complete(UUID operationId) {
        return store.transaction(()-> {
            PgOperation candidate=store.byId(operationId,false);
            if (candidate==null) throw new PgFailure(404,"NOT_FOUND");
            if (candidate.approvalOperationId()!=null) store.byId(candidate.approvalOperationId(),true);
            PgOperation locked=store.byId(operationId,true);
            if (!locked.status().equals("PENDING")) return locked;
            return store.finish(locked);
        });
    }
}
