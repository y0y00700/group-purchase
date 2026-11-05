package com.example.apigateway;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

@Component
@Order(Integer.MIN_VALUE)
public class RequestIdWebFilter implements WebFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdWebFilter.class.getName() + ".id";
    public static final String COMPLETION = RequestIdWebFilter.class.getName() + ".completion";
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdWebFilter.class);

    public static String requestId(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().computeIfAbsent(ATTRIBUTE,
                ignored -> UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = requestId(exchange);
        long started = System.nanoTime();
        var request = exchange.getRequest().mutate().headers(headers -> headers.set(HEADER, id)).build();
        var forwarded = exchange.mutate().request(request).build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, id);
            return Mono.empty();
        });
        // WebExceptionHandler runs outside the filter chain. Response commit is the shared completion boundary.
        var logged = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<String> completion = termination -> {
          if(!logged.compareAndSet(false,true)) return;
          Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
          var status = exchange.getResponse().getStatusCode();
          Integer statusValue = null;
          if(status != null) statusValue = status.value();
          else if (termination.equals("completed")) statusValue = 200;
          LOG.atInfo().addKeyValue("event","http_request_completed").addKeyValue("requestId",id)
              .addKeyValue("method", exchange.getRequest().getMethod().name())
              .addKeyValue("route",route == null ? "UNMATCHED":route.getId())
              .addKeyValue("status",statusValue)
              .addKeyValue("durationMs",(System.nanoTime() - started)/1_000_000.0)
              .addKeyValue("termination",termination).log("HTTP request completed");
        };
        exchange.getAttributes().put(COMPLETION, completion);
        return chain.filter(forwarded).doOnSuccess(ignored -> completion.accept("completed"))
            .doFinally(signal ->{
               if(signal == SignalType.CANCEL) completion.accept("cancelled");
            }).contextWrite(context -> context.put("requestId",id));

    }
}
