package com.example.member.infrastructure.web.error;
public final class AuthFailure extends RuntimeException {
    public final int status;
    public final String code;
    public AuthFailure(int status, String code) { super(code); this.status = status; this.code = code; }
}
