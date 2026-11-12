package com.example.apigateway;

import java.util.List;
import java.util.function.Predicate;
import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
public class ExactPathRoutePredicateFactory extends AbstractRoutePredicateFactory<ExactPathRoutePredicateFactory.Config> {
    public ExactPathRoutePredicateFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("path");
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        return exchange -> config.getPath().equals(exchange.getRequest().getURI().getRawPath());
    }

    public static class Config {
        private String path;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
