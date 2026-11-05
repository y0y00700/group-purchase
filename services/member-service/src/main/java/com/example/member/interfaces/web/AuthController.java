package com.example.member.interfaces.web;
import jakarta.servlet.http.HttpServletRequest;
import com.example.member.interfaces.web.dto.CsrfResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController
public class AuthController {
    @GetMapping("/api/auth/csrf") CsrfResponse csrf(HttpServletRequest request) {
        var token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        return new CsrfResponse(token.getToken(),token.getHeaderName());
    }
}
