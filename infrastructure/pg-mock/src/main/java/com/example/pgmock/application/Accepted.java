package com.example.pgmock.application;

import com.example.pgmock.domain.PgOperation;

public record Accepted(PgOperation operation,long responseDelayMs,boolean firstAcceptance) { }
