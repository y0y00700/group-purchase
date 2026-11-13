package com.example.apigateway;

enum AuthCheckFailure {
    INVALID_REQUEST(400),
    UNAUTHENTICATED(401),
    CSRF_INVALID(403),
    SELLER_ROLE_REQUIRED(403),
    BAD_GATEWAY(502),
    AUTHENTICATION_UNAVAILABLE(503),
    GATEWAY_TIMEOUT(504);

    final int status;

    AuthCheckFailure(int status) {
        this.status = status;
    }

    RuntimeException exception() {
        return new Rejected(this);
    }

    static final class Rejected extends RuntimeException {
        final AuthCheckFailure reason;

        private Rejected(AuthCheckFailure reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }
}
