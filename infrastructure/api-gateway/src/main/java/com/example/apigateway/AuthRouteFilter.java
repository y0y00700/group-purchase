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
            if ("member".equals(policy) || "protected".equals(policy)) {
                headers.setCacheControl("no-store");
            }
            return Mono.empty();
        });
        if (!"protected".equals(policy)) {
            return chain.filter(exchange);
        }
        return forwardAuthenticated(exchange, chain);
    }

    private Mono<Void> forwardAuthenticated(ServerWebExchange exchange, GatewayFilterChain chain) {
        return member.check(exchange.getRequest(), RequestIdWebFilter.requestId(exchange)).flatMap(identity -> {
            var request = exchange.getRequest().mutate().headers(headers -> {
                headers.remove(HttpHeaders.COOKIE);
                headers.remove(HttpHeaders.AUTHORIZATION);
                headers.remove(MemberAuthClient.CSRF_HEADER);
                headers.set(MemberAuthClient.CALLER_HEADER, MemberAuthClient.CALLER);
                headers.set(MemberAuthClient.CREDENTIAL_HEADER, groupCredential);
                headers.set("X-Authenticated-Member-Id", identity.memberId().toString());
                headers.set("X-Authenticated-Roles", String.join(",", identity.roles()));
            }).build();
            return chain.filter(exchange.mutate().request(request).build());
        });
    }
}
