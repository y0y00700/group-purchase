package com.example.member.application;
import java.util.*;
import com.example.member.domain.member.Member;
public record MemberResult(UUID memberId, List<String> roles) {
    public MemberResult { roles=List.copyOf(roles); }
    static MemberResult from(Member member) {
        return new MemberResult(member.id().value(),member.roles().stream().map(Enum::name).sorted().toList());
    }
}
