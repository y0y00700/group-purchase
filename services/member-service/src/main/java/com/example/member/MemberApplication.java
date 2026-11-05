package com.example.member;
import com.example.member.config.EarlyFailureLogging;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
@SpringBootApplication
@ConfigurationPropertiesScan
public class MemberApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(MemberApplication.class);
        app.addListeners(new EarlyFailureLogging("member"));
        app.run(args);
    }
}
