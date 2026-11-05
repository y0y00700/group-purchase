package com.example.member.infrastructure.security;
import com.example.member.Members;
import com.example.member.application.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class MemberAuthenticationProvider implements AuthenticationProvider {
    private final AuthenticateMemberService authenticate;
    public MemberAuthenticationProvider(AuthenticateMemberService authenticate) { this.authenticate=authenticate; }
    @Override public Authentication authenticate(Authentication input) {
        try {
            var member=authenticate.authenticate(input.getName(),(String)input.getCredentials());
            return UsernamePasswordAuthenticationToken.authenticated(new Members.Identity(member.memberId()),null,
                member.roles().stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList());
        } catch(MemberAuthenticationException denied) { throw new BadCredentialsException("AUTHENTICATION_FAILED"); }
    }
    @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
