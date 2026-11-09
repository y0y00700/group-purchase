package com.example.member.application;
public final class MemberAuthenticationException extends RuntimeException {
    public enum Reason { AUTHENTICATION_FAILED, UNAUTHENTICATED }
    private final Reason reason;
    public MemberAuthenticationException(Reason reason) { super(reason.name()); this.reason=reason; }
    public Reason reason() { return reason; }
}
