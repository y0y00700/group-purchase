package com.example.grouppurchase.application;

public enum RequestFailure {
    INVALID_REQUEST, NOT_FOUND, IDEMPOTENCY_KEY_CONFLICT;

    public Failure exception() {
        return new Failure(this);
    }

    public static final class Failure extends RuntimeException {
        private final RequestFailure reason;

        private Failure(RequestFailure reason) {
            super(reason.name());
            this.reason = reason;
        }

        public RequestFailure reason() {
            return reason;
        }
    }
}
