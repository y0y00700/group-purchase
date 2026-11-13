package com.example.grouppurchase.purchase.domain;

public final class DraftConflict extends RuntimeException {
    public enum Reason { NOT_DRAFT, DRAFT_VERSION_MISMATCH, NOT_READY, DEADLINE_PASSED }

    private final Reason reason;

    public DraftConflict(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
