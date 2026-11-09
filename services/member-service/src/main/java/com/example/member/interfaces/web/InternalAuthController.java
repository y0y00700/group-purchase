package com.example.member.interfaces.web;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.example.member.application.GetCurrentMemberService;
import com.example.member.infrastructure.security.*;
import com.example.member.infrastructure.web.error.AuthFailure;
import com.example.member.interfaces.web.dto.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalAuthController {
    private final GetCurrentMemberService current;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;
    private final HeaderCsrfHandler handler;
    public InternalAuthController(GetCurrentMemberService current,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository tokens,HeaderCsrfHandler handler) {
        this.current=current; this.contexts=contexts; this.tokens=tokens; this.handler=handler;
    }
    @PostMapping(value="/internal/auth/check",consumes="application/json")
    MemberResponse check(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response) {
        var input=InternalAuthRequest.parse(body);
        var context=contexts.loadDeferredContext(request).get();
        var member=current.get(SessionMemberIdentity.memberId(context.getAuthentication()));
        validateOriginalMethod(input.method(),request);
        return MemberResponse.from(member);
    }
    void validateOriginalMethod(String method,HttpServletRequest request) {
        if(Set.of("GET","HEAD","OPTIONS").contains(method)) return;
        CsrfToken expected=tokens.loadToken(request);
        if(expected==null) throw new AuthFailure(403,"CSRF_INVALID");
        String actual=handler.resolveCsrfTokenValue(request,expected);
        if(actual==null || !MessageDigest.isEqual(expected.getToken().getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8)))
            throw new AuthFailure(403,"CSRF_INVALID");
    }

}
