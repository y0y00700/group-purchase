package com.example.grouppurchase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.images.builder.Transferable;

/** Per-class disposable DB. Never reads local secrets or the Compose volume. */
final class PostgresFixture implements AutoCloseable {
    private final String password = UUID.randomUUID().toString();
    private PostgreSQLContainer container;
    final Path secretDirectory;

    PostgresFixture() throws Exception {
        secretDirectory = Files.createTempDirectory("group-purchase-db-secret-");
        try {
            Files.writeString(secretDirectory.resolve("spring.datasource.password"), password);
            container = new PostgreSQLContainer("postgres:18.6-bookworm") {
                @Override
                protected void configure() {
                    super.configure();
                    getEnvMap().remove("POSTGRES_PASSWORD");
                }
            }
                    .withDatabaseName("postgres").withUsername("postgres").withPassword(password)
                    .withEnv("POSTGRES_PASSWORD_FILE", "/run/secrets/postgres_password")
                    .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=scram-sha-256")
                    .withEnv("POSTGRES_HOST_AUTH_METHOD", "scram-sha-256")
                    .withCopyFileToContainer(MountableFile.forHostPath(System.getProperty("test.postgres-init"), 0644),
                            "/docker-entrypoint-initdb.d/010-service-databases.sh");
            for (String name : new String[]{"postgres", "member", "group_purchase", "payment", "notification"}) {
                String value = name.equals("postgres") || name.equals("group_purchase")
                        ? password : UUID.randomUUID().toString();
                container.withCopyToContainer(Transferable.of(value, 0644), "/run/secrets/" + name + "_password");
            }
            // The official entrypoint rejects simultaneous PASSWORD and PASSWORD_FILE.
            container.getEnvMap().remove("POSTGRES_PASSWORD");
            container.start();
        } catch (Throwable failure) {
            try {
                close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    String url() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432)
                + "/group_purchase?currentSchema=app&connectTimeout=3&socketTimeout=5";
    }

    String password() { return password; }

    SpringApplicationBuilder configure(SpringApplicationBuilder builder, String profile) {
        var properties = new HashMap<String, Object>();
        properties.put("spring.datasource.url", url());
        if ("docker".equals(profile)) {
            properties.put("DB_SECRET_DIR", secretDirectory.toString().replace('\\', '/') + "/");
        } else {
            properties.put("spring.datasource.password", password);
        }
        return properties(builder, properties);
    }

    static SpringApplicationBuilder properties(SpringApplicationBuilder builder, Map<String, Object> values) {
        var environment = isolatedEnvironment(Map.of());
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-postgres", values));
        return builder.environment(environment);
    }

    static StandardEnvironment isolatedEnvironment(Map<String, Object> variables) {
        var environment = new StandardEnvironment();
        // Test app contexts use only explicit inputs; never inherit a developer's password override.
        // This changes Spring's test property sources, not the JVM properties or production policy.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("systemEnvironment", variables));
        return environment;
    }

    Connection connection() throws Exception {
        return DriverManager.getConnection(url(), "group_purchase_app", password);
    }

    @Override
    public void close() throws Exception {
        try {
            if (container != null) container.stop();
        } finally {
            try (var paths = Files.walk(secretDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
