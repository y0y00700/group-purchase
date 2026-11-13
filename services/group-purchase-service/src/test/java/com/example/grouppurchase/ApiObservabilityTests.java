package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.grouppurchase.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.*;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ApiObservabilityTests {
    static final String SECRET = "SENTINEL_API_PRIVATE_006";
    static ConfigurableApplicationContext app;
    static int port;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    @BeforeAll static void start() {
        app = new SpringApplicationBuilder(GroupPurchaseApplication.class, Fixtures.class).run(
                "--test.api-fixture=true", "--spring.profiles.active=local", "--server.port=0", "--spring.cloud.config.enabled=false", "--spring.config.location=classpath:/api-observability-test.yml",
                "--eureka.client.enabled=false", "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "--app.auth.internal-credential=" + PostgresFixture.INTERNAL_CREDENTIAL,
                "--app.configuration.service=group-purchase", "--app.configuration.environment=local",
                "--app.configuration.revision=test", "--logging.level.org.springframework.web=ERROR");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
    }
    @AfterAll static void stop() { if (app != null) app.close(); CLIENT.close(); }
    static HttpResponse<String> send(String path, String method, String accept, String type, String body,
            String... ids) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", accept).header("Authorization", SECRET + "_AUTH").header("Cookie", SECRET + "_COOKIE")
                .header("Content-Type", type).method(method, HttpRequest.BodyPublishers.ofString(body));
        for (String id : ids) builder.header("X-Request-Id", id);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    static void problem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = JSON.readTree(response.body());
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(id).matches("[0-9a-f]{32}");
        assertThat(body.get("requestId").asText()).isEqualTo(id);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("instance").asText()).isEqualTo("urn:request:" + id);
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        assertThat(body.size()).isEqualTo(7);
        assertThat(response.body()).doesNotContain(SECRET, "Exception", "SQL");
    }
    @Test void actualHttpErrorsAndNegotiation(CapturedOutput output) throws Exception {
        problem(send("/api/fixture?secret=" + SECRET + "_QUERY", "POST", "application/json", "application/json", "{"), 400, "INVALID_REQUEST");
        problem(send("/api/fixture", "POST", "application/json", "application/json", "{\"quantity\":0}"), 400, "INVALID_REQUEST");
        problem(send("/api/fixture?quantity=wrong", "GET", "application/json", "application/json", ""), 400, "INVALID_REQUEST");
        problem(send("/api/fixture", "POST", "application/json", "text/plain", SECRET + "_BODY"), 415, "UNSUPPORTED_MEDIA_TYPE");
        problem(send("/api/absent/" + SECRET, "GET", "text/html", "application/json", ""), 404, "NOT_FOUND");
        var method = send("/api/group-purchases/ping", "POST", "text/html", "application/json", "");
        problem(method, 405, "METHOD_NOT_ALLOWED");
        assertThat(method.headers().firstValue("Allow").orElseThrow()).contains("GET");
        problem(send("/api/group-purchases/ping", "GET", "image/png", "application/json", ""), 406, "NOT_ACCEPTABLE");
        problem(send("/api/crash", "GET", "application/json", "application/json", ""), 500, "INTERNAL_ERROR");
        problem(send("/api/dispatch-error", "GET", "text/html", "application/json", ""), 500, "INTERNAL_ERROR");
        var head = send("/api/absent", "HEAD", "application/json", "application/json", "");
        assertThat(head.statusCode()).isEqualTo(404);
        assertThat(head.body()).isEmpty();
        assertThat(output.getAll()).doesNotContain(SECRET);
    }
    @Test void selectsIdsAndRestoresMdc() throws Exception {
        String valid = "a".repeat(32);
        assertThat(RequestIdFilter.selectRequestId(List.of(valid))).isEqualTo(valid);
        for (var ids : List.of(List.<String>of(), List.of(""), List.of("A".repeat(32)), List.of(valid, valid), List.of(valid + "," + valid))) {
            assertThat(RequestIdFilter.selectRequestId(ids)).matches("[0-9a-f]{32}").isNotEqualTo(valid);
        }
        var request = new MockHttpServletRequest("GET", "/api/unit");
        request.addHeader("X-Request-Id", valid);
        MDC.put("requestId", "previous");
        try {
            new RequestIdFilter().doFilter(request, new MockHttpServletResponse(), (in, out) -> {
                assertThat(MDC.get("requestId")).isEqualTo(valid);
                throw new jakarta.servlet.ServletException("safe fixture");
            });
        } catch (jakarta.servlet.ServletException expected) {
            assertThat(MDC.get("requestId")).isEqualTo("previous");
        } finally { MDC.clear(); }
        assertThat(send("/api/group-purchases/ping", "GET", "application/json", "application/json", "", valid)
                .headers().allValues("X-Request-Id")).containsExactly(valid);
        assertThat(send("/api/group-purchases/ping", "GET", "application/json", "application/json", "", valid, valid)
                .headers().firstValue("X-Request-Id").orElseThrow()).isNotEqualTo(valid);
    }
    @Test void overlappingAsyncRequestsKeepTheirOwnIdAndLogOnce(CapturedOutput output) throws Exception {
        var ids = java.util.stream.IntStream.range(0, 12).mapToObj(i -> UUID.randomUUID().toString().replace("-", "")).toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = ids.stream().map(id -> executor.submit(() -> send("/api/async", "GET", "application/json", "application/json", "", id))).toList();
            for (int i = 0; i < futures.size(); i++) {
                var response = futures.get(i).get();
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().allValues("X-Request-Id")).containsExactly(ids.get(i));
            }
        }
        // Completion callbacks can finish immediately after the client receives the body.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && ids.stream().anyMatch(id -> !output.getAll().contains(id))) Thread.sleep(10);
        var events = output.getAll().lines().filter(line -> line.startsWith("{"))
                .map(JSON::readTree).filter(node -> node.has("event") && node.get("event").asText().equals("http_request_completed"))
                .toList();
        for (String id : ids) assertThat(events.stream().filter(node -> node.get("requestId").asText().equals(id)).count()).isEqualTo(1);
        assertThat(MDC.get("requestId")).isNull();
    }
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="test.api-fixture", havingValue="true")
    @Configuration(proxyBeanMethods = false) @Import(FixtureController.class) static class Fixtures {
        @Bean JdbcClient unusedPurchaseStorage() {
            return org.mockito.Mockito.mock(JdbcClient.class);
        }

        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> errorDispatchFixture() {
            var filter = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
                    (request, response, chain) -> ((jakarta.servlet.http.HttpServletResponse) response).sendError(500));
            filter.addUrlPatterns("/api/dispatch-error");
            filter.setOrder(0);
            return filter;
        }
    }
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="test.api-fixture", havingValue="true")
    @RestController static class FixtureController {
        record Input(@Min(1) int quantity) { }
        @PostMapping("/api/fixture") Map<String, Integer> input(@RequestBody @Valid Input input) { return Map.of("quantity", input.quantity()); }
        @GetMapping("/api/fixture") int parameter(@RequestParam int quantity) { return quantity; }
        @GetMapping("/api/crash") void crash() { throw new IllegalStateException(SECRET + "_EXCEPTION"); }
        @GetMapping("/api/async") Callable<Map<String, String>> async() {
            return () -> { Thread.sleep(60); return Map.of("result", "ok"); };
        }
    }
}
