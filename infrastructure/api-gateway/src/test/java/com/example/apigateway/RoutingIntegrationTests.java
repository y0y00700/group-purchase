package com.example.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingIntegrationTests {

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
    void mapsConnectionTimeoutToGatewayTimeout() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/group-purchases/ping"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, new Object());
        new RoutingFailureHandler().handle(exchange, new ConnectTimeoutException()).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(504);
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
                        + "\",\"trace\":\"" + exchange.getRequestHeaders().getFirst("X-Test-Trace") + "\"}";
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
