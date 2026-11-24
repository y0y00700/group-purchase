package com.example.apigateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
final class AuthRouteFilter implements GlobalFilter, Ordered {
    private final MemberAuthClient member;
    private final String groupCredential;

    AuthRouteFilter(MemberAuthClient member,
            @Value("${app.auth.member-internal-credential}") String memberCredential,
            @Value("${app.auth.group-purchase-internal-credential}") String groupCredential) {
        this.member = member;
        this.groupCredential = MemberAuthClient.requireCredential(groupCredential);
        if (groupCredential.equals(memberCredential)) {
            throw new IllegalArgumentException("Internal credentials must be distinct");
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Object policy = route.getMetadata().get("auth-policy");
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            if (!"member".equals(policy)) {
                headers.remove(HttpHeaders.SET_COOKIE);
                headers.remove("Set-Cookie2");
            }
            if ("member".equals(policy) || "protected".equals(policy) || "public-purchase".equals(policy)) {
                headers.setCacheControl("no-store");
            }
            return Mono.empty();
        });
        if ("protected".equals(policy)) {
            return forwardAuthenticated(exchange, chain, route.getMetadata().get("required-role"));
        }
        if ("public-purchase".equals(policy)) {
            var request = exchange.getRequest().mutate().headers(AuthRouteFilter::removeClientCredentials).build();
            return chain.filter(exchange.mutate().request(request).build());
        }
        return chain.filter(exchange);
    }

    private Mono<Void> forwardAuthenticated(ServerWebExchange exchange, GatewayFilterChain chain, Object role) {
        AuthCheckFailure denied = missingRoleFailure(role);
        if (denied == AuthCheckFailure.INVALID_ROUTE_POLICY) return Mono.error(denied.exception());
        return member.check(exchange.getRequest(), RequestIdWebFilter.requestId(exchange)).flatMap(identity -> {
            if (!identity.roles().contains(role)) return Mono.error(denied.exception());
            var request = exchange.getRequest().mutate().headers(headers -> {
                removeClientCredentials(headers);
                headers.set(MemberAuthClient.CALLER_HEADER, MemberAuthClient.CALLER);
                headers.set(MemberAuthClient.CREDENTIAL_HEADER, groupCredential);
                headers.set("X-Authenticated-Member-Id", identity.memberId().toString());
                headers.set("X-Authenticated-Roles", String.join(",", identity.roles()));
            }).build();
            return chain.filter(exchange.mutate().request(request).build());
        });
    }

    private static AuthCheckFailure missingRoleFailure(Object role) {
        if ("SELLER".equals(role)) return AuthCheckFailure.SELLER_ROLE_REQUIRED;
        if ("BUYER".equals(role)) return AuthCheckFailure.BUYER_ROLE_REQUIRED;
        return AuthCheckFailure.INVALID_ROUTE_POLICY;
    }

    private static void removeClientCredentials(HttpHeaders headers) {
        headers.remove(HttpHeaders.COOKIE);
        headers.remove(HttpHeaders.AUTHORIZATION);
        headers.remove(MemberAuthClient.CSRF_HEADER);
    }
}
