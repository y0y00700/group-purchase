package com.example.apigateway;

import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class RoutingFailureHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) == null
                || exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean connectionFailure = false;
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof ConnectTimeoutException) {
                exchange.getResponse().setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
                return exchange.getResponse().setComplete();
            }
            if (current instanceof IOException) {
                connectionFailure = true;
            }
        }
        if (connectionFailure) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
            return exchange.getResponse().setComplete();
        }
        return Mono.error(failure);
    }
}
