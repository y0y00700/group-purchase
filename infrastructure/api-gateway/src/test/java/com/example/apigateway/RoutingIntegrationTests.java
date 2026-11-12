package com.example.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@org.springframework.context.annotation.Import(RoutingIntegrationTests.Failures.class)
class RoutingIntegrationTests {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Failures {
        @org.springframework.context.annotation.Bean
        @org.springframework.core.annotation.Order(-100)
        org.springframework.web.server.WebFilter failureFixture() {
            return (exchange, chain) -> exchange.getRequest().getPath().value().equals("/_fixture/crash")
                    ? reactor.core.publisher.Mono.error(new IllegalStateException("SENTINEL_GW_006_EXCEPTION"))
                    : chain.filter(exchange);
        }
    }

    private static final AtomicInteger RECEIVED = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer UPSTREAM = startUpstream();
    private static final int REFUSED_PORT = unusedPort();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.group-purchase[0].uri",
                () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.refused-service[0].uri",
                () -> "http://127.0.0.1:" + REFUSED_PORT);
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
        EXECUTOR.close();
    }

    @Test
    void forwardsPathQueryHeaderAndTargetResponse() throws Exception {
        var response = request("/api/group-purchases/ping?marker=ok", "GET", "trace-123");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/api/group-purchases/ping", "marker=ok", "trace-123");
        assertThat(request("/api/group-purchases/ping?status=503", "GET", null).statusCode())
                .isEqualTo(503);
    }

    @Test
    void doesNotForwardUnlistedPathsOrMethods() throws Exception {
        int before = RECEIVED.get();
        for (String path : new String[] {"/internal/probe", "/actuator/health",
                "/api/group-purchases/internal/probe", "/api/group-purchases/actuator/health",
                "/group-purchase/api/group-purchases/ping", "/api/group-purchases/ping/extra"}) {
            assertThat(request(path, "GET", null).statusCode()).as(path).isEqualTo(404);
        }
        assertThat(request("/api/group-purchases/ping", "POST", null).statusCode()).isEqualTo(404);
        assertThat(RECEIVED.get()).isEqualTo(before);
    }

    @Test
    void distinguishesNoInstanceConnectionFailureAndTimeout() throws Exception {
        assertThat(request("/_fixture/missing", "GET", null).statusCode()).isEqualTo(503);
        assertThat(request("/_fixture/refused", "GET", null).statusCode()).isEqualTo(502);
        assertThat(request("/api/group-purchases/ping?slow=true", "GET", null).statusCode())
                .isEqualTo(504);
    }

    @Test
    void rejectsAnEmptyMemberDiscoveryList() throws Exception {
        int before = RECEIVED.get();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/_fixture/auth-missing"))
                .header("Cookie", "GP_SESSION=valid").build();
        try (var client = HttpClient.newHttpClient()) {
            var result = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(503);
            assertThat(result.body()).contains("AUTHENTICATION_UNAVAILABLE");
            assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        }
        assertThat(RECEIVED.get()).isEqualTo(before);
    }

    @Test
    void classifiesARefusedConnectionToADiscoveredMember() {
        var discovery = mock(LoadBalancerClientFactory.class);
        var balancer = mock(ReactorServiceInstanceLoadBalancer.class);
        when(discovery.getInstance("member", ReactorServiceInstanceLoadBalancer.class)).thenReturn(balancer);
        var stale = new DefaultServiceInstance("stale-member", "member", "127.0.0.1", REFUSED_PORT, false);
        when(balancer.choose()).thenReturn(Mono.just(new DefaultResponse(stale)));
        var client = new MemberAuthClient(discovery, "isolated-member-auth-test-credential",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        var request = MockServerHttpRequest.get("/api/group-purchases/auth-probe")
                .header("Cookie", "GP_SESSION=valid").build();
        assertThatThrownBy(() -> client.check(request, "test-request").block())
                .isInstanceOf(AuthCheckFailure.Rejected.class).hasMessage("BAD_GATEWAY");
    }

    @Test
    void mapsConnectionTimeoutToGatewayTimeout() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/group-purchases/ping"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, new Object());
        new RoutingFailureHandler().handle(exchange, new ConnectTimeoutException()).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(504);
    }

    @Test
    void problemsAndForwardedIdsAreSafeAndLogsAreIsolated(CapturedOutput output) throws Exception {
        var json = JsonMapper.builder().build();
        var ids = new java.util.HashSet<String>();
        try (var client = HttpClient.newHttpClient()) {
            var futures = java.util.stream.IntStream.range(0, 12).mapToObj(i -> client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/group-purchases/ping"))
                            .header("X-Request-Id", "a".repeat(32)).header("Authorization", "SENTINEL_GW_006").build(),
                    HttpResponse.BodyHandlers.ofString())).toList();
            for (var future : futures) {
                var response = future.join();
                String id = response.headers().firstValue("X-Request-Id").orElseThrow();
                assertThat(id).matches("[0-9a-f]{32}").isNotEqualTo("a".repeat(32));
                assertThat(ids.add(id)).isTrue();
                assertThat(json.readTree(response.body()).get("requestId").asText()).isEqualTo(id);
            }
        }
        for (String path : new String[] {"/not-found?secret=SENTINEL_GW_006", "/_fixture/refused", "/_fixture/missing"}) {
            var response = request(path, "GET", null);
            var body = json.readTree(response.body());
            assertThat(response.statusCode()).isIn(404, 502, 503);
            assertThat(body.get("status").asInt()).isEqualTo(response.statusCode());
            assertThat(body.get("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
            assertThat(body.get("type").asText()).isEqualTo("about:blank");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        }
        var downstream = request("/api/group-purchases/ping?status=503", "GET", null);
        assertThat(downstream.statusCode()).isEqualTo(503);
        assertThat(json.readTree(downstream.body()).has("code")).isFalse();
        var crash = request("/_fixture/crash?secret=SENTINEL_GW_006_QUERY", "GET", null);
        assertThat(crash.statusCode()).isEqualTo(500);
        assertThat(json.readTree(crash.body()).get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(json.readTree(crash.body()).get("requestId").asText())
                .isEqualTo(crash.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(output.getAll()).doesNotContain("SENTINEL_GW_006");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && ids.stream().anyMatch(id -> !output.getAll().contains(id))) Thread.sleep(10);
        var events = output.getAll().lines().filter(line -> line.startsWith("{"))
                .map(json::readTree).filter(node -> node.has("event")).toList();
        for (String id : ids) assertThat(events.stream().filter(node -> node.path("requestId").asText().equals(id)).count()).isEqualTo(1);
    }

    @Test
    void unexpectedAndCommittedFailureBoundaries() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/absent"));
        new RoutingFailureHandler().handle(exchange, new IllegalStateException("SENTINEL_GW_006")).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(500);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("INTERNAL_ERROR").doesNotContain("SENTINEL_GW_006");
        var committed = MockServerWebExchange.from(MockServerHttpRequest.get("/committed"));
        committed.getResponse().setComplete().block();
        var failure = new IllegalStateException("safe");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new RoutingFailureHandler().handle(committed, failure).block()))
                .isSameAs(failure);
    }

    @Test
    void schedulerSwitchAndCancellationKeepContextWithoutMdc(CapturedOutput output) {
        var filter = new RequestIdWebFilter();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/scheduled")
                .header("X-Request-Id", "forged"));
        filter.filter(exchange, forwarded -> reactor.core.publisher.Mono.deferContextual(context -> {
            assertThat(context.<String>get("requestId")).isEqualTo(RequestIdWebFilter.requestId(exchange));
            assertThat(forwarded.getRequest().getHeaders().get("X-Request-Id"))
                    .containsExactly(RequestIdWebFilter.requestId(exchange));
            assertThat(org.slf4j.MDC.get("requestId")).isNull();
            return reactor.core.publisher.Mono.<Void>empty();
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())).block();
        var cancelled = MockServerWebExchange.from(MockServerHttpRequest.get("/cancelled"));
        var subscription = filter.filter(cancelled, forwarded -> reactor.core.publisher.Mono.never()).subscribe();
        subscription.dispose();
        var json = JsonMapper.builder().build();
        var events = output.getAll().lines().filter(line -> line.startsWith("{"))
                .map(json::readTree).filter(node -> node.path("requestId").asText().equals(RequestIdWebFilter.requestId(cancelled))).toList();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("termination").asText()).isEqualTo("cancelled");
        assertThat(events.getFirst().get("status").isNull()).isTrue();
    }

    private HttpResponse<String> request(String path, String method, String trace) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (trace != null) builder.header("X-Test-Trace", trace);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer startUpstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/_fixture/auth-missing", exchange -> {
                RECEIVED.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.createContext("/api/group-purchases/ping", exchange -> {
                RECEIVED.incrementAndGet();
                if (exchange.getRequestURI().getQuery() != null
                        && exchange.getRequestURI().getQuery().contains("slow=true")) {
                    try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                int status = exchange.getRequestURI().getQuery() != null
                        && exchange.getRequestURI().getQuery().contains("status=503") ? 503 : 200;
                String body = "{\"path\":\"" + exchange.getRequestURI().getPath()
                        + "\",\"query\":\"" + exchange.getRequestURI().getQuery()
                        + "\",\"trace\":\"" + exchange.getRequestHeaders().getFirst("X-Test-Trace")
                        + "\",\"requestId\":\"" + exchange.getRequestHeaders().getFirst("X-Request-Id") + "\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // Expected when the Gateway times out and closes the connection.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int unusedPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
