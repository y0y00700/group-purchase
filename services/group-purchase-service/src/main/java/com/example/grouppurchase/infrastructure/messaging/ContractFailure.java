package com.example.grouppurchase.infrastructure.messaging;

/** Transport contract errors; deliberately independent of HTTP problem types. */
public final class ContractFailure extends RuntimeException {
    public enum Code { MALFORMED_JSON, TOO_LARGE, INVALID_FIELD, UNSUPPORTED_VERSION,
        UNSUPPORTED_TYPE, TOPIC_MISMATCH, KEY_MISMATCH }
    private final Code code;
    public ContractFailure(Code code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }
    public Code code() { return code; }
}
