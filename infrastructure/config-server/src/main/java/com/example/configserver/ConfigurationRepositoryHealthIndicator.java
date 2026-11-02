package com.example.configserver;

import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationRepositoryHealthIndicator implements HealthIndicator {

    private final EnvironmentRepository repository;

    public ConfigurationRepositoryHealthIndicator(EnvironmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        try {
            for (String service : List.of("group-purchase", "eureka-server", "api-gateway")) {
                for (String profile : List.of("local", "docker")) {
                    Environment configuration = repository.findOne(service, profile, null);
                    if (!service.equals(value(configuration, "app.configuration.service"))
                            || !profile.equals(value(configuration, "app.configuration.environment"))
                            || !(value(configuration, "app.configuration.revision") instanceof String revision)
                            || revision.isBlank()) {
                        return Health.down().build();
                    }
                }
            }
            return Health.up().build();
        } catch (Exception exception) {
            return Health.down().build();
        }
    }

    private Object value(Environment configuration, String key) {
        return configuration.getPropertySources().stream()
                .filter(source -> source.getSource().containsKey(key))
                .map(source -> source.getSource().get(key))
                .findFirst().orElse(null);
    }
}
