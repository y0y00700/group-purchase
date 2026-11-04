package com.example.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@EnableConfigServer
@SpringBootApplication
public class ConfigServerApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(ConfigServerApplication.class);
        application.addListeners(new EarlyFailureLogging("config-server"));
        application.run(args);
    }
}
