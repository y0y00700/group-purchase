package com.example.member.application;
import com.example.member.domain.member.MemberRepository;
import com.example.member.application.port.PasswordVerifier;
import org.springframework.transaction.annotation.Transactional;

public class AuthenticateMemberService {
    private final MemberRepository members;
    private final PasswordVerifier passwords;
    public AuthenticateMemberService(MemberRepository members, PasswordVerifier passwords) {
        this.members=members; this.passwords=passwords;
    }
    @Transactional(readOnly=true)
    public MemberResult authenticate(String loginId, String password) {
        var member = members.findByLoginId(loginId).orElse(null);
        boolean matched = passwords.matches(password, member == null ? null : member.passwordHash());
        if(!matched || member == null || !member.canAuthenticate())
            throw new MemberAuthenticationException(MemberAuthenticationException.Reason.AUTHENTICATION_FAILED);
        return MemberResult.from(member);
    }
}
