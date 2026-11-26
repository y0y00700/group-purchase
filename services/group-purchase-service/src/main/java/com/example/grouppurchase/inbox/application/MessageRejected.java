package com.example.grouppurchase.inbox.application;

public final class MessageRejected extends RuntimeException {
    private final String code;
    public MessageRejected(String code) { super(code); this.code=code; }
    public String code() { return code; }
}
