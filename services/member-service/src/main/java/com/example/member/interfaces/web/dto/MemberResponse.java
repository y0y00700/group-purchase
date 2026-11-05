package com.example.member.interfaces.web.dto;
import java.util.*;
import com.example.member.application.MemberResult;
public record MemberResponse(UUID memberId, List<String> roles) {
    public static MemberResponse from(MemberResult result) { return new MemberResponse(result.memberId(),result.roles()); }
}
