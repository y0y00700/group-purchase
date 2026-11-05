package com.example.member.application.port;
public interface PasswordVerifier {
    /** A null hash performs the same password work against a private dummy hash. */
    boolean matches(String rawPassword, String storedHash);
    String hash(String rawPassword);
}
