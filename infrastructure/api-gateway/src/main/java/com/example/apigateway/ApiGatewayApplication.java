package com.example.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(ApiGatewayApplication.class);
        application.addListeners(new EarlyFailureLogging("api-gateway"));
        application.run(args);
    }
}
