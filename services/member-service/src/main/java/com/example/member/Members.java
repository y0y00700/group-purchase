package com.example.member;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Serialization compatibility only: existing JDBC sessions retain this binary type name. */
public final class Members {
    private Members() { }
    public record Identity(UUID memberId) implements Serializable, Principal {
        private static final long serialVersionUID = 1L;
        public String getName() { return memberId.toString(); }
    }
}
