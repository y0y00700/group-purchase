package com.example.grouppurchase.purchase.application;

public enum PurchaseRequestFailure {
    INVALID_REQUEST, NOT_FOUND, IDEMPOTENCY_KEY_CONFLICT;

    public Failure exception() {
        return new Failure(this);
    }

    public static final class Failure extends RuntimeException {
        private final PurchaseRequestFailure reason;

        private Failure(PurchaseRequestFailure reason) {
            super(reason.name());
            this.reason = reason;
        }

        public PurchaseRequestFailure reason() {
            return reason;
        }
    }
}
