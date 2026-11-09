package com.example.member.infrastructure.web;

import com.example.member.infrastructure.web.error.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Before Spring Session: route/caller/cookie validation, then commit only after JDBC completion. */
public class BoundaryFilter implements Filter {
    static final Map<String,Set<String>> ROUTES = Map.of(
        "/api/auth/csrf",Set.of("GET","HEAD"), "/api/auth/login",Set.of("POST"),
        "/api/auth/logout",Set.of("POST"), "/api/members/me",Set.of("GET","HEAD"),
        "/internal/auth/check",Set.of("POST"));
    private final byte[] credential;
    public BoundaryFilter(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("A dedicated internal credential of at least 32 bytes is required");
        credential=secret.getBytes(StandardCharsets.UTF_8);
    }
    void caller(HttpServletRequest request) {
        var callers=Collections.list(request.getHeaders("X-Internal-Caller"));
        var credentials=Collections.list(request.getHeaders("X-Internal-Credential"));
        if(callers.size()!=1 || !"api-gateway".equals(callers.getFirst()) || credentials.size()!=1
                || !MessageDigest.isEqual(credential,credentials.getFirst().getBytes(StandardCharsets.UTF_8)))
            throw  new AuthFailure(403,"INTERNAL_CALLER_DENIED");
    }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var request=(HttpServletRequest)input;
        var response=(HttpServletResponse)output;
        String path=request.getRequestURI();
        if(path.matches("/actuator/(health(?:/(?:liveness|readiness))?|info|metrics(?:/[^/]+)?)")
                && Set.of("GET","HEAD").contains(request.getMethod())) { chain.doFilter(input,output); return; }
        try {
            if(!ROUTES.containsKey(path)) throw new AuthFailure(404,"NOT_FOUND");
            if(!ROUTES.get(path).contains(request.getMethod())) {
                response.setHeader("Allow",String.join(", ",ROUTES.get(path)));
                throw new AuthFailure(405,"METHOD_NOT_ALLOWED");
            }
            if(path.equals("/internal/auth/check")) caller(request);
            long cookies=Collections.list(request.getHeaders("Cookie")).stream().flatMap(h->Arrays.stream(h.split(";")))
                    .map(String::trim).filter(c->c.matches("GP_SESSION\\s*=.*")).count();
            if(cookies>1) throw new AuthFailure(400,"INVALID_REQUEST");
            request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,path);
            finish(request,response,chain);
        } catch(AuthFailure failure) { Problems.write(request,response,failure.status,failure.code); }
    }
    void finish(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        var pending=new BufferedResponse(response);
        pending.setHeader("Cache-Control","no-store");
        try{
            chain.doFilter(request,pending);
        } catch (Exception error){
            if(error instanceof AuthFailure failure){
                Problems.write(request,response,failure.status,failure.code);
                return;
            }
            int status = StorageFailures.unavailable(error) ? 503 : 500;
            org.slf4j.LoggerFactory.getLogger(BoundaryFilter.class).atError()
                .addKeyValue("code",Problems.code(status))
                .addKeyValue("exceptionType",error.getClass().getSimpleName())
                .addKeyValue("causeType",error.getCause()==null ? "none" : error.getCause().getClass().getSimpleName())
                .addKeyValue("causeFrames",error.getCause()==null ? List.of() : Arrays.stream(error.getCause().getStackTrace()).limit(8).map(f->f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber()).toList())
                .addKeyValue("frames",Arrays.stream(error.getStackTrace()).limit(8)
                    .map(f->f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber()).toList()).log("Authentication request failed");
            Problems.write(request,response,status,Problems.code(status));
            return;
        }
        pending.publish(response);
    }
}
