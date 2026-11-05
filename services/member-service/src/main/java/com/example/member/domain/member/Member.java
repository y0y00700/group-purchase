package com.example.member.domain.member;
import java.util.*;

public record Member(MemberId id, String loginId, String passwordHash, MemberStatus status, Set<Role> roles) {
    public Member {
        Objects.requireNonNull(id);
        Objects.requireNonNull(loginId);
        Objects.requireNonNull(passwordHash);
        Objects.requireNonNull(status);
        roles = Set.copyOf(roles);
    }
    public boolean canAuthenticate() { return status == MemberStatus.ACTIVE && !roles.isEmpty(); }
    public static Member managed(MemberId id, String loginId, String hash, MemberStatus status, Set<Role> roles) {
        if (!loginId.matches("[a-z0-9._-]{3,50}") || (status == MemberStatus.ACTIVE && roles.isEmpty()))
            throw new IllegalArgumentException("Invalid managed member");
        return new Member(id,loginId,hash,status,roles);
    }
    @Override public String toString() { return "Member[id=" + id + ", status=" + status + "]"; }
}
