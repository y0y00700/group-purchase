package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.configserver.ConfigServerApplication;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.cloud.netflix.eureka.TimeoutProperties;
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs;
import org.springframework.context.ConfigurableApplicationContext;

class ServiceDiscoveryIntegrationTests {

    private static ConfigurableApplicationContext config;

    @TempDir
    Path diagnostics;

    @BeforeAll
    static void startConfig() {
        config = new SpringApplicationBuilder(ConfigServerApplication.class).run(
                "--spring.config.name=config-fixture", "--spring.profiles.active=native", "--server.port=0",
                "--spring.cloud.config.enabled=false", "--eureka.client.enabled=false",
                "--spring.cloud.config.server.native.search-locations="
                        + Path.of(System.getProperty("test.config-repo")).toUri(),
                "--spring.cloud.config.server.native.add-label-locations=false",
                "--spring.cloud.config.server.health.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=OFF");
    }

    @AfterAll
    static void stopConfig() {
        if (config != null) config.close();
    }

    @Test
    void registersDiscoversReachableAddressAndUnregistersOnNormalShutdown() throws Exception {
        int registryPort = freePort();
        int servicePort = freePort();
        try (var registry = startRegistry(registryPort);
                var observer = startObserver(registryPort)) {
            try (var app = startApp(registryPort, servicePort)) {
                var discovery = app.getBean(DiscoveryClient.class);
                await("application and observer discover the registered service", () ->
                        containsPort(discovery, servicePort)
                                && containsPort(observer.getBean(DiscoveryClient.class), servicePort));
                var instance = discovery.getInstances("group-purchase").getFirst();
                assertThat(instance.getHost()).isEqualTo("localhost");
                assertThat(get(instance.getUri().resolve("/actuator/health")).statusCode()).isEqualTo(200);
                assertThat(get(URI.create("http://localhost:" + registryPort + "/eureka/apps/GROUP-PURCHASE")).body())
                        .contains("UP", "localhost", "actuator/health", "actuator/info");
                var clientConfig = app.getBean(EurekaClientConfigBean.class);
                assertThat(clientConfig.shouldEnforceRegistrationAtInit()).isFalse();
                var timeouts = app.getBean(TimeoutProperties.class);
                assertThat(timeouts.getConnectTimeout()).isEqualTo(1000);
                assertThat(timeouts.getSocketTimeout()).isEqualTo(5000);
                assertThat(app.getBeansOfType(RestClientDiscoveryClientOptionalArgs.class)).hasSize(1);
            }
            await("normal shutdown removes the service from the observer cache", () ->
                    observer.getBean(DiscoveryClient.class).getInstances("group-purchase").isEmpty());
            try (var app = startApp(registryPort, servicePort)) {
                await("restarted service registers exactly once", () ->
                        observer.getBean(DiscoveryClient.class).getInstances("group-purchase").size() == 1);
            }
        }
    }

    @Test
    void startsWithoutEurekaAndRecoversAfterRegistryRestartWithoutRestartingApp() throws Exception {
        int registryPort = freePort();
        try (var app = startApp(registryPort, freePort())) {
            var discovery = app.getBean(DiscoveryClient.class);
            assertThat(get(URI.create(url(app) + "/actuator/health")).statusCode()).isEqualTo(200);
            assertThat(discovery.getInstances("group-purchase")).isEmpty();
            try (var registry = startRegistry(registryPort)) {
                await("app registers and discovers after the absent registry starts", () ->
                        !discovery.getInstances("group-purchase").isEmpty()
                                && registered(registryPort));
            }
            assertThat(get(URI.create(url(app) + "/actuator/health")).statusCode()).isEqualTo(200);
            try (var recovered = startRegistry(registryPort)) {
                await("running app registers in the new empty registry", () -> registered(registryPort));
                try (var observer = startObserver(registryPort)) {
                    await("a fresh client discovers the recovered registration", () ->
                            !observer.getBean(DiscoveryClient.class).getInstances("group-purchase").isEmpty());
                }
                assertThat(discovery.getInstances("group-purchase")).hasSize(1);
            }
        }
    }

    private RegistryProcess startRegistry(int port) throws Exception {
        Path log = Files.createTempFile(diagnostics, "eureka-", ".log");
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
                "-jar", System.getProperty("test.eureka-jar"),
                "--CONFIG_SERVER_URL=" + url(config), "--spring.profiles.active=local", "--server.port=" + port,
                "--eureka.server.use-read-only-response-cache=false",
                "--eureka.server.response-cache-auto-expiration-in-seconds=1",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var fixture = new RegistryProcess(process);
        try {
            await("isolated Eureka server readiness", () -> {
                if (!process.isAlive()) throw new AssertionError("Eureka exited; log: " + readLog(log));
                try {
                    return get(URI.create("http://localhost:" + port + "/actuator/health")).statusCode() == 200;
                } catch (Exception exception) { return false; }
            });
            return fixture;
        } catch (Throwable failure) {
            fixture.close();
            throw new AssertionError("Eureka startup failed: " + readLog(log), failure);
        }
    }

    private static ConfigurableApplicationContext startApp(int registryPort, int servicePort) {
        var args = new ArrayList<>(List.of(
                "--CONFIG_SERVER_URL=" + url(config), "--spring.cloud.config.enabled=true",
                "--spring.profiles.active=local", "--server.port=" + servicePort,
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        args.addAll(discoveryArguments(registryPort));
        return new SpringApplicationBuilder(GroupPurchaseApplication.class).run(args.toArray(String[]::new));
    }

    private static ConfigurableApplicationContext startObserver(int registryPort) {
        var args = new ArrayList<>(List.of(
                "--spring.config.name=observer-fixture", "--spring.application.name=discovery-observer",
                "--spring.cloud.config.enabled=false", "--eureka.client.register-with-eureka=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        args.addAll(discoveryArguments(registryPort));
        return new SpringApplicationBuilder(DiscoveryObserver.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private static List<String> discoveryArguments(int port) {
        // Only tests accelerate the normal 30-second heartbeat and registry/cache refresh intervals.
        return List.of("--eureka.client.service-url.defaultZone=http://localhost:" + port + "/eureka/",
                "--eureka.client.registry-fetch-interval-seconds=1",
                "--eureka.client.initial-instance-info-replication-interval-seconds=1",
                "--eureka.client.instance-info-replication-interval-seconds=1",
                "--eureka.instance.lease-renewal-interval-in-seconds=1");
    }

    private static boolean containsPort(DiscoveryClient discovery, int port) {
        return discovery.getInstances("group-purchase").stream().anyMatch(instance -> instance.getPort() == port);
    }

    private static boolean registered(int port) {
        try {
            var response = get(URI.create("http://localhost:" + port + "/eureka/apps/GROUP-PURCHASE"));
            return response.statusCode() == 200 && response.body().contains("\"UP\"");
        } catch (Exception exception) {
            return false;
        }
    }

    private static void await(String condition, BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        do {
            if (check.getAsBoolean()) return;
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for: " + condition);
    }

    private static HttpResponse<String> get(URI uri) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String url(ConfigurableApplicationContext app) {
        return "http://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (Exception exception) { return path.toString(); }
    }

    private record RegistryProcess(Process process) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Could not stop Eureka fixture");
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DiscoveryObserver {
    }
}
