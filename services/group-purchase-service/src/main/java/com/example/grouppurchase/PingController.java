package com.example.grouppurchase;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    @GetMapping("/api/group-purchases/ping")
    public Map<String, String> ping() {
        return Map.of("service", "group-purchase", "message", "pong");
    }
}
