package com.example.pgmock.application;

import com.example.pgmock.domain.*;

public class RetryPgOperation {
    private final PgStore store;
    public RetryPgOperation(PgStore store) { this.store=store; }
    public PgOperation retryOrReturn(PgOperation operation,PgCommand command) {
        operation.match(command);
        if (operation.status().equals("RETRYABLE_FAILURE")) return store.retry(operation);
        return operation;
    }
}
