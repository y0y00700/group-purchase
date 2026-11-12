package com.example.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    @Bean
    HttpClientCustomizer singleAttemptRouting() {
        return client -> client.disableRetry(true).followRedirect(false);
    }

    public static void main(String[] args) {
        var application = new SpringApplication(ApiGatewayApplication.class);
        application.addListeners(new EarlyFailureLogging("api-gateway"));
        application.run(args);
    }
}
