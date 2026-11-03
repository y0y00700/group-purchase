package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.example.configserver.ConfigServerApplication;
import com.example.grouppurchase.config.ConfigurationIdentity;
import com.jayway.jsonpath.JsonPath;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class ConfigClientIntegrationTests {

    private static final Path REPOSITORY = Path.of(System.getProperty("test.config-repo"));
    private static ConfigServerFixture server;
    private static PostgresFixture database;

    @BeforeAll
    static void startServer() throws Exception {
        database = new PostgresFixture();
        server = new ConfigServerFixture(REPOSITORY);
    }

    @AfterAll
    static void stopServer() throws Exception {
        try {
            if (server != null) server.close();
        } finally {
            if (database != null) database.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker"})
    void appliesRemoteConfigurationAndExposesOnlySafeDiagnostics(String profile) throws Exception {
        try (var app = startClient(server.url(), profile)) {
            var identity = app.getBean(ConfigurationIdentity.class);
            assertThat(identity.service()).isEqualTo("group-purchase");
            assertThat(identity.environment()).isEqualTo(profile);
            assertThat(identity.revision()).isEqualTo("002-v1");
            var info = get(app, "/actuator/info");
            assertThat(info.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(info.body(), "$.configuration.environment")).isEqualTo(profile);
            assertThat(JsonPath.<String>read(get(app, "/actuator/health").body(), "$.status")).isEqualTo("UP");
            assertThat(get(app, "/actuator/env").statusCode()).isEqualTo(404);
            assertThat(get(app, "/actuator/configprops").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void loadsApplicationContextWithExistingNameAndLocalDefaultProfile() {
        try (var app = startClient(server.url(), null)) {
            assertThat(app.getEnvironment().getProperty("spring.application.name")).isEqualTo("group-purchase");
            assertThat(app.getBean(ConfigurationIdentity.class).environment()).isEqualTo("local");
        }
    }

    @Test
    void rejectsUnknownProfileInsteadOfUsingOnlyCommonConfiguration() {
        assertThatThrownBy(() -> startClient(server.url(), "dockre")).hasStackTraceContaining("environment");
    }

    @Test
    void rejectsMissingServiceConfigurationAndMarksRepositoryUnhealthy(@TempDir Path repository) throws Exception {
        Files.copy(REPOSITORY.resolve("application.yml"), repository.resolve("application.yml"));
        try (var fixture = new ConfigServerFixture(repository)) {
            assertThat(get(fixture.context, "/actuator/health").statusCode()).isEqualTo(503);
            assertThatThrownBy(() -> startClient(fixture.url(), "local"))
                    .hasStackTraceContaining("Binding validation errors");
        }
    }

    @Test
    void rejectsMalformedRemoteYaml(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        Files.writeString(repository.resolve("group-purchase-local.yml"), "app: [\n");
        try (var fixture = new ConfigServerFixture(repository)) {
            assertThatThrownBy(() -> startClient(fixture.url(), "local"))
                    .hasStackTraceContaining("Could not locate PropertySource");
        }
    }

    @Test
    void rejectsConfigurationForAnotherService(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        Path service = repository.resolve("group-purchase.yml");
        Files.writeString(service, Files.readString(service).replace("service: group-purchase", "service: other"));
        try (var fixture = new ConfigServerFixture(repository)) {
            assertThatThrownBy(() -> startClient(fixture.url(), "local"))
                    .hasStackTraceContaining("does not match application/profile");
        }
    }

    @Test
    void rejectsConfigurationForAnotherEnvironment(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        Files.writeString(repository.resolve("group-purchase-local.yml"),
                "app:\n  configuration:\n    environment: docker\n");
        try (var fixture = new ConfigServerFixture(repository)) {
            assertThatThrownBy(() -> startClient(fixture.url(), "local"))
                    .hasStackTraceContaining("does not match application/profile");
        }
    }

    @Test
    void failsWithinBoundedTimeWhenConfigEndpointDoesNotRespond() throws Exception {
        try (var socket = new ServerSocket(0)) {
            assertTimeout(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> startClient("http://localhost:" + socket.getLocalPort(), "local",
                            "--spring.cloud.config.request-read-timeout=200"))
                            .hasStackTraceContaining("Could not locate PropertySource"));
        }
    }

    @Test
    void keepsLoadedConfigurationDuringOutageButNewStartupFails(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        try (var fixture = new ConfigServerFixture(repository);
                var app = startClient(fixture.url(), "local")) {
            fixture.close();
            assertThat(get(app, "/actuator/health").statusCode()).isEqualTo(200);
            assertThat(app.getBean(ConfigurationIdentity.class).revision()).isEqualTo("002-v1");
            assertThatThrownBy(() -> startClient(fixture.url(), "local"))
                    .hasStackTraceContaining("Could not locate PropertySource");
        }
        try (var recovered = new ConfigServerFixture(repository);
                var app = startClient(recovered.url(), "local")) {
            assertThat(get(app, "/actuator/health").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void appliesFileChangesOnlyAfterClientRestart(@TempDir Path repository) throws Exception {
        copyRepository(repository);
        try (var fixture = new ConfigServerFixture(repository)) {
            try (var app = startClient(fixture.url(), "local")) {
                Path service = repository.resolve("group-purchase.yml");
                Files.writeString(service, Files.readString(service).replace("002-v1", "002-v2"));
                assertThat(app.getBean(ConfigurationIdentity.class).revision()).isEqualTo("002-v1");
            }
            try (var restarted = startClient(fixture.url(), "local")) {
                assertThat(restarted.getBean(ConfigurationIdentity.class).revision()).isEqualTo("002-v2");
            }
        }
    }

    private static ConfigurableApplicationContext startClient(String url, String profile, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--CONFIG_SERVER_URL=" + url,
                // The in-process server test dependency disables Config Client by default.
                "--spring.cloud.config.enabled=true",
                "--eureka.client.enabled=false",
                "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=OFF"));
        if (profile != null) {
            args.add("--spring.profiles.active=" + profile);
        }
        args.addAll(List.of(extra));
        return database.configure(new SpringApplicationBuilder(GroupPurchaseApplication.class), profile)
                .run(args.toArray(String[]::new));
    }

    private static void copyRepository(Path target) throws Exception {
        try (var files = Files.list(REPOSITORY)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext app, String path) throws Exception {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    static class ConfigServerFixture implements AutoCloseable {
        private final ConfigurableApplicationContext context;

        ConfigServerFixture(Path repository) {
            context = new SpringApplicationBuilder(ConfigServerApplication.class).run(
                    "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                    "--spring.config.name=config-fixture", "--spring.profiles.active=native", "--server.port=0",
                    "--spring.cloud.config.server.native.search-locations=" + repository.toUri(),
                    "--spring.cloud.config.server.native.add-label-locations=false",
                    "--spring.cloud.config.server.accept-empty=false",
                    "--spring.cloud.config.server.health.enabled=false",
                    "--eureka.client.enabled=false",
                    "--spring.main.banner-mode=off", "--logging.level.root=OFF");
        }

        String url() {
            return "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
