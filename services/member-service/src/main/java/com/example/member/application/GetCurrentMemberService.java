package com.example.member.application;
import java.util.UUID;
import com.example.member.domain.member.*;
import org.springframework.transaction.annotation.Transactional;

public class GetCurrentMemberService {
    private final MemberRepository members;
    public GetCurrentMemberService(MemberRepository members) { this.members=members; }
    @Transactional(readOnly=true)
    public MemberResult get(UUID memberId) {
        if(memberId == null)
            throw new MemberAuthenticationException(MemberAuthenticationException.Reason.UNAUTHENTICATED);
        var member = members.findById(new MemberId(memberId)).orElse(null);
        if(member == null || !member.canAuthenticate())
            throw new MemberAuthenticationException(MemberAuthenticationException.Reason.UNAUTHENTICATED);
        return MemberResult.from(member);
    }
}
