package com.example.pgmock.domain;

public class PgFailure extends RuntimeException {
    public final int status;
    public final String code;
    public PgFailure(int status,String code) { super(code); this.status=status; this.code=code; }
    public static PgFailure conflict(String code) { return new PgFailure(409,code); }
}
