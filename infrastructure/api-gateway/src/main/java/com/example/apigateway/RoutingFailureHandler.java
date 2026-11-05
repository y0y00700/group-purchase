package com.example.apigateway;

import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import java.util.concurrent.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class RoutingFailureHandler implements WebExceptionHandler {
    static int classify(ServerWebExchange exchange, Throwable failure) {

        boolean routed = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) != null;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean connectionFailure = false;
        for(Throwable current = failure; current != null && visited.add(current); current = current.getCause()){
            if(routed && (current instanceof TimeoutException || current instanceof ConnectTimeoutException)) return 504;
            if(routed && current instanceof IOException) connectionFailure = true;
        }
        if(connectionFailure) return 502;
        if(failure instanceof ResponseStatusException status) return status.getStatusCode().value();
        return 500;

    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if(exchange.getResponse().isCommitted()){
            complete(exchange,"error");
            return Mono.error(failure);
        }
        int status = classify(exchange, failure);
        if(HttpStatus.resolve(status) == null) status = 500;
        String id = RequestIdWebFilter.requestId(exchange);
        if(status == 500) ApiProblems.unexpected(failure, id);
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(RequestIdWebFilter.HEADER, id);

        Mono<Void> write = exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.HEAD
            ? response.setComplete()
            : response.writeWith(Mono.just(response.bufferFactory().wrap(ApiProblems.bytes(status, id))));
            return write.doOnSuccess(ignored -> complete(exchange, "completed")).doFinally(signal -> {
                if (signal == reactor.core.publisher.SignalType.CANCEL) complete(exchange, "cancelled");
                if (signal == reactor.core.publisher.SignalType.ON_ERROR) complete(exchange,"error");
            });

    }

    private static void complete(ServerWebExchange exchange, String termination) {
        java.util.function.Consumer<String> completion = exchange.getAttribute(RequestIdWebFilter.COMPLETION);
        if (completion != null) completion.accept(termination);
    }
}
