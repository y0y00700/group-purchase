package com.example.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.private-token=server-only-fixture")
class ConfigServerApplicationTests {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void repository(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.config.server.native.search-locations",
                () -> Path.of(System.getProperty("test.config-repo")).toUri().toString());
    }

    @Test
    void servesServiceAndProfileConfigurationWithoutServerSettings() throws Exception {
        var response = get("/group-purchase/docker");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(),
                "$.propertySources[0].source['app.configuration.environment']")).isEqualTo("docker");
        assertThat(response.body()).contains("002-v1", "app.configuration.service")
                .doesNotContain("server-only-fixture", "search-locations", "CONFIG_REPO_LOCATION");
    }

    @Test
    void reportsRepositoryHealthAndDoesNotExposeEnvironment() throws Exception {
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(health.body(), "$.status")).isEqualTo("UP");
        // Config Server also maps /{application}/{profile}; it must never expose Actuator env data.
        assertThat(get("/actuator/env").body()).doesNotContain("server-only-fixture");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
