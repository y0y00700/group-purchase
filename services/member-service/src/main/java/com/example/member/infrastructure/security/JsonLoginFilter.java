package com.example.member.infrastructure.security;
import com.example.member.infrastructure.web.error.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

public final class JsonLoginFilter extends AbstractAuthenticationProcessingFilter {
    public JsonLoginFilter(AuthenticationManager manager) {
        super(PathPatternRequestMatcher.withDefaults().matcher(org.springframework.http.HttpMethod.POST,"/api/auth/login"),manager);
    }
    @Override public Authentication attemptAuthentication(HttpServletRequest request,HttpServletResponse response) throws IOException {
        try {
            if(request.getContentType()==null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))
                throw new AuthFailure(415,"UNSUPPORTED_MEDIA_TYPE");
            byte[] bytes=request.getInputStream().readNBytes(4097);
            if(bytes.length>4096) throw new AuthFailure(400,"INVALID_REQUEST");
            var body=Problems.JSON.readTree(bytes);
            if(!body.isObject() || body.size()!=2 || !body.path("loginId").isString() || !body.path("password").isString())
                throw new AuthFailure(400,"INVALID_REQUEST");
            String rawId=body.get("loginId").asString();
            if(!rawId.matches("[A-Za-z0-9._-]{3,50}")) throw new AuthFailure(400,"INVALID_REQUEST");
            String loginId=rawId.toLowerCase(Locale.ROOT);
            String password=body.get("password").asString();
            if(!loginId.matches("[a-z0-9._-]{3,50}") || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length>72)
                throw new AuthFailure(400,"INVALID_REQUEST");
            return getAuthenticationManager().authenticate(UsernamePasswordAuthenticationToken.unauthenticated(loginId,password));
        } catch(tools.jackson.core.JacksonException | IllegalArgumentException error) {
            throw new AuthFailure(400,"INVALID_REQUEST");
        }
    }
}
