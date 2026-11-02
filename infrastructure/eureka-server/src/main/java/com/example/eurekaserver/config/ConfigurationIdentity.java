package com.example.eurekaserver.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.configuration")
public record ConfigurationIdentity(
        @NotBlank String service,
        @NotBlank String environment,
        @NotBlank String revision) {
}
