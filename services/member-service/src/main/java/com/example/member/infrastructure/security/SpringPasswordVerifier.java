package com.example.member.infrastructure.security;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.example.member.application.port.PasswordVerifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SpringPasswordVerifier implements PasswordVerifier {
    private final PasswordEncoder encoder;
    private final String dummy;
    public SpringPasswordVerifier(PasswordEncoder encoder) { this.encoder=encoder; dummy=encoder.encode(UUID.randomUUID().toString()); }
    public boolean matches(String raw, String stored) { return encoder.matches(raw,stored == null ? dummy : stored); }
    public String hash(String raw) {
        if(raw == null || raw.isEmpty() || raw.getBytes(StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("Password must be 1 to 72 UTF-8 bytes");
        return encoder.encode(raw);
    }
}
