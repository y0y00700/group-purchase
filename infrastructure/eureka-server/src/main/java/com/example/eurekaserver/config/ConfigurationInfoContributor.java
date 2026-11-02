package com.example.eurekaserver.config;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationInfoContributor implements InfoContributor {

    private final ConfigurationIdentity identity;

    public ConfigurationInfoContributor(ConfigurationIdentity identity, Environment environment) {
        String[] profiles = environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles();
        if (!identity.service().equals(environment.getProperty("spring.application.name"))
                || !Arrays.asList(profiles).contains(identity.environment())) {
            throw new IllegalStateException("Central configuration does not match application/profile");
        }
        this.identity = identity;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("configuration", Map.of(
                "service", identity.service(),
                "environment", identity.environment(),
                "revision", identity.revision()));
    }
}
