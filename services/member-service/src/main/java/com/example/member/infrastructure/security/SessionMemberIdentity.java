package com.example.member.infrastructure.security;
import java.util.UUID;
import com.example.member.Members;
import org.springframework.security.core.Authentication;

public final class SessionMemberIdentity {
    private SessionMemberIdentity() { }
    public static UUID memberId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Members.Identity identity ? identity.memberId() : null;
    }
}
