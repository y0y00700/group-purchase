package com.example.grouppurchase.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

final class GatewayIdentityInterceptor implements HandlerInterceptor {
    static final String IDENTITY = "gatewayMemberIdentity";
    private static final Set<String> ROLES = Set.of("BUYER", "SELLER", "OPERATOR");
    private final byte[] credential;

    GatewayIdentityInterceptor(String secret) {
        if (!secret.matches("[A-Za-z0-9._~-]{32,}")) {
            throw new IllegalArgumentException("Internal credential must contain at least 32 ASCII token characters");
        }
        credential = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(RequiresGateway.class)) {
            return true;
        }
        response.setHeader("Cache-Control", "no-store");
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (!validCaller(request)) {
            ApiExceptionResolver.write(request, response, 403, requestId, "INTERNAL_CALLER_DENIED");
            return false;
        }
        try {
            request.setAttribute(IDENTITY, readIdentity(request));
            return true;
        } catch (IllegalArgumentException invalid) {
            ApiExceptionResolver.write(request, response, 400, requestId);
            return false;
        }
    }

    private boolean validCaller(HttpServletRequest request) {
        String caller = singleHeader(request, "X-Internal-Caller");
        String supplied = singleHeader(request, "X-Internal-Credential");
        return  "api-gateway".equals(caller) && supplied != null
                && MessageDigest.isEqual(credential, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static Identity readIdentity(HttpServletRequest request) {
        String member = singleHeader(request, "X-Authenticated-Member-Id");
        String roleHeader = singleHeader(request, "X-Authenticated-Roles");
        if(member == null || roleHeader == null){
            throw new IllegalArgumentException("Missing identity header");
        }
        UUID memberId = UUID.fromString(member);
        List<String> roles = List.of(roleHeader.split(",", -1));
        if(!memberId.toString().equalsIgnoreCase(member) || !ROLES.containsAll(roles)
                || new HashSet<>(roles).size() != roles.size()){
            throw  new IllegalArgumentException("Invalid identity header");
        }
        return new Identity(memberId, roles.stream().sorted().toList());
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }

    record Identity(UUID memberId, List<String> roles) { }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface RequiresGateway { }

    @Configuration(proxyBeanMethods = false)
    static class Registration {
        @Bean
        MappedInterceptor gatewayIdentity(@Value("${app.auth.internal-credential}") String credential) {
            return new MappedInterceptor(null, new GatewayIdentityInterceptor(credential));
        }
    }
}
