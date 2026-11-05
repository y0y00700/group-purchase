package com.example.configserver;

import java.util.Map;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Config-data can fail before Boot initializes logging from YAML. Runs after Boot's cleanup. */
final class EarlyFailureLogging implements ApplicationListener<ApplicationFailedEvent>, Ordered {
    private final String service;
    EarlyFailureLogging(String service) { this.service = service; }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void onApplicationEvent(ApplicationFailedEvent event) {
        if (event.getApplicationContext() != null) return;
        var environment = new StandardEnvironment();
        String profile = environment.getProperty("APP_ENVIRONMENT",
                environment.getProperty("SPRING_PROFILES_ACTIVE", "local"));
        environment.getPropertySources().addFirst(new MapPropertySource("early-failure-json", Map.of(
                "logging.structured.format.console", "logstash",
                "logging.structured.json.add.service", service,
                "logging.structured.json.add.environment", "docker".equals(profile) ? "docker" : "local")));
        var system = LoggingSystem.get(getClass().getClassLoader());
        system.initialize(new LoggingInitializationContext(environment), null, null);
    }
}
