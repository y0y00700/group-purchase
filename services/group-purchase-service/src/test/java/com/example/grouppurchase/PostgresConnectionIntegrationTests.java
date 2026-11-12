package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

@ExtendWith(OutputCaptureExtension.class)
class PostgresConnectionIntegrationTests {
    private static PostgresFixture database;
    private static ConfigClientIntegrationTests.ConfigServerFixture config;

    @BeforeAll
    static void start() throws Exception {
        database = new PostgresFixture();
        config = new ConfigClientIntegrationTests.ConfigServerFixture(Path.of(System.getProperty("test.config-repo")));
    }

    @AfterAll
    static void stop() throws Exception {
        try { if (config != null) config.close(); }
        finally { if (database != null) database.close(); }
    }

    private static ConfigurableApplicationContext app(PostgresFixture db, String profile,
            Map<String, Object> overrides) {
        var properties = new HashMap<String, Object>();
        properties.put("app.auth.internal-credential", PostgresFixture.INTERNAL_CREDENTIAL);
        properties.put("spring.datasource.url", db.url());
        if (profile.equals("docker")) {
            properties.put("DB_SECRET_DIR", db.secretDirectory.toString().replace('\\', '/') + "/");
        }
        properties.putAll(overrides);
        // Model local environment injection without inheriting a developer's DB credentials.
        var variables = new HashMap<String, Object>();
        if (profile.equals("local") && !properties.containsKey("spring.datasource.password")) {
            variables.put("SPRING_DATASOURCE_PASSWORD", db.password());
        }
        properties.values().removeIf(java.util.Objects::isNull);
        var environment = PostgresFixture.isolatedEnvironment(variables);
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-connection", properties));
        return new SpringApplicationBuilder(GroupPurchaseApplication.class).environment(environment)
                .run("--CONFIG_SERVER_URL=" + config.url(), "--spring.cloud.config.enabled=true",
                        "--spring.profiles.active=" + profile, "--eureka.client.enabled=false",
                        "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=INFO");
    }

    private static String history(PostgresFixture db) throws Exception {
        try (var connection = db.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT row_to_json(h)::text FROM app.flyway_schema_history h "
                        + "WHERE version='1'")) {
            assertThat(rows.next()).isTrue();
            var result = rows.getString(1);
            assertThat(result).contains("\"success\":true", "\"checksum\":")
                    .doesNotContain("\"checksum\":null");
            assertThat(rows.next()).isFalse();
            return result;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker"})
    void connectsWithServiceIdentityAndPreservesMigrationOnRestart(String profile, CapturedOutput output)
            throws Exception {
        String before;
        try (var context = app(database, profile, Map.of())) {
            try (var connection = context.getBean(DataSource.class).getConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT current_user, current_database(), current_schema()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("group_purchase_app");
                assertThat(rows.getString(2)).isEqualTo("group_purchase");
                assertThat(rows.getString(3)).isEqualTo("app");
            }
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                for (String path : new String[]{"/actuator/health", "/actuator/info", "/api/group-purchases/ping",
                        "/actuator/env", "/actuator/configprops"}) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(
                            path.equals("/actuator/env") || path.equals("/actuator/configprops") ? 404 : 200);
                    if (path.equals("/actuator/health")) {
                        assertThat(response.body()).contains("UP");
                    }
                    assertThat(response.body()).doesNotContain(database.password());
                }
                var remote = client.send(HttpRequest.newBuilder(URI.create(config.url() + "/group-purchase/" + profile))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(remote.statusCode()).isEqualTo(200);
                assertThat(remote.body()).doesNotContain(database.password(), "spring.datasource.password");
            }
            before = history(database);
        }
        try (var restarted = app(database, profile, Map.of())) {
            assertThat(history(database)).isEqualTo(before);
        }
        assertThat(output.getAll()).doesNotContain(database.password());
    }

    private static void fails(PostgresFixture db, String profile, Map<String, Object> properties,
            String cause, CapturedOutput output) {
        long start = System.nanoTime();
        Throwable failure = catchThrowable(() -> {
            try (var unexpected = app(db, profile, properties)) {
                throw new AssertionError("Application unexpectedly started");
            }
        });
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(millis).isLessThan(60_000);
        assertThat(failure).isInstanceOf(Exception.class).hasStackTraceContaining(cause);
        assertThat(output.getAll()).doesNotContain(db.password());
        System.out.println("Verified startup rejection: " + cause + " (" + millis + " ms)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker"})
    void rejectsWrongAndEmptyPasswords(String profile, CapturedOutput output) throws Exception {
        if (profile.equals("local")) {
            for (String password : new String[]{"", "invalid-password"}) {
                fails(database, profile, Map.of("spring.datasource.password", password),
                        password.isEmpty() ? "08004" : "28P01", output);
            }
        } else {
            String original = Files.readString(database.secretDirectory.resolve("spring.datasource.password"));
            try {
                for (String password : new String[]{"", "invalid-password"}) {
                    Files.writeString(database.secretDirectory.resolve("spring.datasource.password"), password);
                    fails(database, profile, Map.of(), password.isEmpty() ? "08004" : "28P01", output);
                }
            } finally {
                Files.writeString(database.secretDirectory.resolve("spring.datasource.password"), original);
            }
        }
    }

    @Test
    void rejectsMissingLocalPassword(CapturedOutput output) {
        var properties = new HashMap<String, Object>();
        properties.put("spring.datasource.url", database.url());
        properties.put("spring.datasource.password", null);
        fails(database, "local", properties, "08004", output);
    }

    @Test
    void rejectsMissingDockerSecret(@TempDir Path directory, CapturedOutput output) {
        fails(database, "docker", Map.of("DB_SECRET_DIR", directory.resolve("absent").toString() + "/"),
                "cannot be found", output);
        fails(database, "docker", Map.of("DB_SECRET_DIR", directory.toString() + "/"), "08004", output);
    }

    @Test
    void missingSecretCannotBeHiddenByHostJvmPassword(@TempDir Path directory, CapturedOutput output) {
        String previous = System.getProperty("spring.datasource.password");
        try {
            System.setProperty("spring.datasource.password", database.password());
            var properties = new HashMap<String, Object>();
            properties.put("spring.datasource.password", null);
            fails(database, "local", properties, "08004", output);
            fails(database, "docker", Map.of("DB_SECRET_DIR", directory.toString() + "/"), "08004", output);
        } finally {
            if (previous == null) System.clearProperty("spring.datasource.password");
            else System.setProperty("spring.datasource.password", previous);
        }
    }

    @Test
    void rejectsUnavailableDatabase(CapturedOutput output) throws Exception {
        // A TCP listener without PostgreSQL bounds the driver's handshake timeout deterministically.
        try (var socket = new ServerSocket()) {
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            fails(database, "local", Map.of("spring.datasource.url", "jdbc:postgresql://127.0.0.1:"
                    + socket.getLocalPort() + "/group_purchase?connectTimeout=3&socketTimeout=5"),
                    "08001", output);
        }
    }

    @Test
    void rejectsInvalidSqlAndChecksumWithoutRepair(@TempDir Path directory, CapturedOutput output) throws Exception {
        try (var isolated = new PostgresFixture()) {
            Files.writeString(directory.resolve("V2__broken.sql"), "THIS IS INVALID SQL;");
            fails(isolated, "local", Map.of("spring.flyway.locations", "classpath:db/migration,filesystem:"
                    + directory.toString().replace('\\', '/')), "syntax error", output);
            Files.delete(directory.resolve("V2__broken.sql"));
            try (var context = app(isolated, "local", Map.of())) { history(isolated); }
            String before = history(isolated);
            try (var stream = PostgresConnectionIntegrationTests.class.getResourceAsStream(
                    "/db/migration/V1__initialize_service_schema.sql")) {
                assertThat(stream).isNotNull();
                Files.write(directory.resolve("V1__initialize_service_schema.sql"), stream.readAllBytes());
            }
            Files.writeString(directory.resolve("V1__initialize_service_schema.sql"), "\n-- changed checksum\n",
                    java.nio.file.StandardOpenOption.APPEND);
            fails(isolated, "local", Map.of("spring.flyway.locations", "filesystem:"
                    + directory.toString().replace('\\', '/')), "checksum mismatch", output);
            assertThat(history(isolated)).isEqualTo(before);
        }
    }
}
