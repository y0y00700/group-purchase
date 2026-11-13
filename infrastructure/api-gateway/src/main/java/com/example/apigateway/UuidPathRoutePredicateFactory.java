package com.example.apigateway;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
public class UuidPathRoutePredicateFactory extends AbstractRoutePredicateFactory<UuidPathRoutePredicateFactory.Config> {
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public UuidPathRoutePredicateFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("template");
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        String[] parts = config.getTemplate().split("\\{id\\}", -1);
        if (parts.length != 2 || !parts[0].endsWith("/") || !(parts[1].isEmpty() || parts[1].startsWith("/"))) {
            throw new IllegalArgumentException("UuidPath requires one {id} path segment");
        }
        Pattern path = Pattern.compile(Pattern.quote(parts[0]) + UUID + Pattern.quote(parts[1]));
        return exchange -> path.matcher(exchange.getRequest().getURI().getRawPath()).matches();
    }

    public static class Config {
        private String template;

        public String getTemplate() {
            return template;
        }

        public void setTemplate(String template) {
            this.template = template;
        }
    }
}
