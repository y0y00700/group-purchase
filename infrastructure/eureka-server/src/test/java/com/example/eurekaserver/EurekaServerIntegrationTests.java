package com.example.eurekaserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.example.configserver.ConfigServerApplication;
import com.example.eurekaserver.config.ConfigurationIdentity;
import com.jayway.jsonpath.JsonPath;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.context.ConfigurableApplicationContext;

class EurekaServerIntegrationTests {

    private static final Path REPOSITORY = Path.of(System.getProperty("test.config-repo"));
    private static ConfigurableApplicationContext config;

    @BeforeAll
    static void startConfig() {
        config = startConfig(REPOSITORY);
    }

    @AfterAll
    static void stopConfig() {
        if (config != null) config.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker"})
    void receivesCentralConfigurationAndRunsStandalone(String profile) throws Exception {
        try (var app = startEureka(url(config), profile)) {
            var identity = app.getBean(ConfigurationIdentity.class);
            assertThat(identity.service()).isEqualTo("eureka-server");
            assertThat(identity.environment()).isEqualTo(profile);
            assertThat(identity.revision()).isEqualTo("003-v1");
            var clientConfig = app.getBean(EurekaClientConfigBean.class);
            assertThat(clientConfig.shouldRegisterWithEureka()).isFalse();
            assertThat(clientConfig.shouldFetchRegistry()).isFalse();
            assertThat(get(app, "/actuator/health").statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(get(app, "/actuator/info").body(),
                    "$.configuration.environment")).isEqualTo(profile);
            assertThat(get(app, "/eureka/apps").statusCode()).isEqualTo(200);
            assertThat(get(app, "/").statusCode()).isEqualTo(200);
            assertThat(get(app, "/actuator/env").statusCode()).isEqualTo(404);
            assertThat(get(app, "/actuator/configprops").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void rejectsUnknownProfile() {
        assertThatThrownBy(() -> startEureka(url(config), "dockre"))
                .hasStackTraceContaining("environment");
    }

    @Test
    void detectsMissingEurekaConfigurationBeforeStartingDependents(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        Files.delete(repository.resolve("eureka-server.yml"));
        try (var fixture = startConfig(repository)) {
            assertThat(get(fixture, "/actuator/health").statusCode()).isEqualTo(503);
            assertThatThrownBy(() -> startEureka(url(fixture), "local"))
                    .hasStackTraceContaining("Binding validation errors");
        }
    }

    @Test
    void rejectsWrongServiceAndEnvironment(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        Files.writeString(repository.resolve("eureka-server-local.yml"),
                "app:\n  configuration:\n    environment: docker\n");
        try (var fixture = startConfig(repository)) {
            assertThat(get(fixture, "/actuator/health").statusCode()).isEqualTo(503);
            assertThatThrownBy(() -> startEureka(url(fixture), "local"))
                    .hasStackTraceContaining("does not match application/profile");
        }
        Files.writeString(repository.resolve("eureka-server-local.yml"),
                "app:\n  configuration:\n    service: other-service\n    environment: local\n");
        try (var fixture = startConfig(repository)) {
            assertThat(get(fixture, "/actuator/health").statusCode()).isEqualTo(503);
            assertThatThrownBy(() -> startEureka(url(fixture), "local"))
                    .hasStackTraceContaining("does not match application/profile");
        }
    }

    @Test
    void rejectsMalformedConfiguration(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        Files.writeString(repository.resolve("eureka-server-local.yml"), "app: [\n");
        try (var fixture = startConfig(repository)) {
            assertThatThrownBy(() -> startEureka(url(fixture), "local"))
                    .hasStackTraceContaining("Could not locate PropertySource");
        }
    }

    @Test
    void boundsStartupWhenConfigDoesNotRespond() throws Exception {
        try (var socket = new ServerSocket(0)) {
            assertTimeout(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> startEureka("http://localhost:" + socket.getLocalPort(), "local",
                            "--spring.cloud.config.request-read-timeout=200"))
                            .hasStackTraceContaining("Could not locate PropertySource"));
        }
    }

    @Test
    void keepsLoadedConfigurationDuringConfigOutageAndRecovers() throws Exception {
        try (var fixture = startConfig(REPOSITORY);
                var app = startEureka(url(fixture), "local")) {
            String unavailableUrl = url(fixture);
            fixture.close();
            assertThat(get(app, "/actuator/health").statusCode()).isEqualTo(200);
            assertThat(get(app, "/eureka/apps").statusCode()).isEqualTo(200);
            assertThatThrownBy(() -> startEureka(unavailableUrl, "local"))
                    .hasStackTraceContaining("Could not locate PropertySource");
        }
        try (var app = startEureka(url(config), "local")) {
            assertThat(get(app, "/actuator/health").statusCode()).isEqualTo(200);
        }
    }

    private static ConfigurableApplicationContext startEureka(String configUrl, String profile, String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of(
                "--CONFIG_SERVER_URL=" + configUrl, "--spring.profiles.active=" + profile,
                "--spring.cloud.config.enabled=true", "--server.port=0",
                "--spring.main.banner-mode=off", "--logging.level.root=OFF"));
        args.addAll(java.util.List.of(extra));
        return new SpringApplicationBuilder(EurekaServerApplication.class).run(args.toArray(String[]::new));
    }

    private static ConfigurableApplicationContext startConfig(Path repository) {
        return new SpringApplicationBuilder(ConfigServerApplication.class).run(
                "--spring.config.name=config-fixture", "--spring.profiles.active=native", "--server.port=0",
                "--spring.cloud.config.enabled=false", "--eureka.client.enabled=false",
                "--spring.cloud.config.server.native.search-locations=" + repository.toUri(),
                "--spring.cloud.config.server.native.add-label-locations=false",
                "--spring.cloud.config.server.accept-empty=false",
                "--spring.cloud.config.server.health.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=OFF");
    }

    private static String url(ConfigurableApplicationContext app) {
        return "http://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext app, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url(app) + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void copyRepository(Path target) throws Exception {
        try (var files = Files.list(REPOSITORY)) {
            for (Path file : files.toList()) Files.copy(file, target.resolve(file.getFileName()));
        }
    }
}
