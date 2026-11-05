package com.example.member.infrastructure.security;
import jakarta.servlet.http.*;
import java.util.Collections;
import org.springframework.security.web.csrf.*;
/** Only a single masked header; never fall back to query/form parameters. */
public class HeaderCsrfHandler implements CsrfTokenRequestHandler {
    private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
    @Override public void handle(HttpServletRequest request,HttpServletResponse response,java.util.function.Supplier<CsrfToken> token) {
        delegate.handle(request,response,token);
    }
    @Override public String resolveCsrfTokenValue(HttpServletRequest request,CsrfToken token) {
        var headers=Collections.list(request.getHeaders("X-CSRF-TOKEN"));
        return headers.size()==1 ? delegate.resolveCsrfTokenValue(request,token) : null;
    }
}
