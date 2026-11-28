package com.example.pgmock.application;

import java.util.function.Consumer;

public class RecoverPendingOperations {
    private final PgStore store;
    private final CompletePgOperation complete;
    private final Consumer<RuntimeException> failures;
    public RecoverPendingOperations(PgStore store,CompletePgOperation complete,Consumer<RuntimeException> failures) {
        this.store=store; this.complete=complete; this.failures=failures;
    }
    public void recover() {
        try {
            for (var id:store.pending(20)) {
                try { complete.complete(id); }
                catch (RuntimeException error) { failures.accept(error); }
            }
        } catch (RuntimeException error) { failures.accept(error); }
    }
}
