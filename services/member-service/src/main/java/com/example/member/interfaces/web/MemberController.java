package com.example.member.interfaces.web;
import com.example.member.application.GetCurrentMemberService;
import com.example.member.infrastructure.security.SessionMemberIdentity;
import com.example.member.interfaces.web.dto.MemberResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
@RestController
public class MemberController {
    private final GetCurrentMemberService current;
    public MemberController(GetCurrentMemberService current) { this.current=current; }
    @GetMapping("/api/members/me") MemberResponse me() {
        return MemberResponse.from(current.get(SessionMemberIdentity.memberId(SecurityContextHolder.getContext().getAuthentication())));
    }
}
