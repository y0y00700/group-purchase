package com.example.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:${test.config-repo}/api-gateway.yml",
        "management.server.port=0"
})
class GatewayAuthIntegrationTests {
    private static final String PURCHASES = "/api/group-purchases";
    private static final String MINE = PURCHASES + "/mine";
    private static final String ID = "126260b2-de13-43b6-8503-a76ad6974d5a";
    private static final String SUCCESS = "{\"memberId\":\"" + ID + "\",\"roles\":[\"SELLER\",\"BUYER\"]}";
    private static final String GROUP_SECRET = "isolated-group-purchase-test-credential";
    private static final String MEMBER_SECRET = "isolated-member-auth-test-credential";
    private static final ConcurrentLinkedQueue<Received> MEMBERS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Received> GROUPS = new ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer MEMBER = fixture(true);
    private static final HttpServer GROUP = fixture(false);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.member[0].uri",
                () -> "http://127.0.0.1:" + MEMBER.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.group-purchase[0].uri",
                () -> "http://127.0.0.1:" + GROUP.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        MEMBER.stop(0);
        GROUP.stop(0);
        EXECUTOR.close();
    }

    @Test
    void verifiesBeforeForwardingAndOnlySendsAllowedHeaders() throws Exception {
        int memberBefore = MEMBERS.size();
        int groupBefore = GROUPS.size();
        var response = request(PURCHASES + "?source=client", "POST", "original body", Map.of(
                "Cookie", "other=secret; GP_SESSION=valid==", "X-CSRF-TOKEN", "token",
                "Authorization", "Bearer client", "x-iNtErNaL-cReDeNtIaL", "forged",
                "X-Authenticated-Member-Id", "forged", "X-Authenticated-Custom", "forged",
                "X-HTTP-Method-Override", "GET"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(MEMBERS).hasSize(memberBefore + 1);
        assertThat(GROUPS).hasSize(groupBefore + 1);
        var internal = MEMBERS.stream().skip(memberBefore).findFirst().orElseThrow();
        assertThat(internal.path()).isEqualTo("/internal/auth/check");
        assertThat(internal.method()).isEqualTo("POST");
        assertThat(JSON.readTree(internal.body()).path("method").asText()).isEqualTo("POST");
        assertThat(internal.headers().getFirst("Cookie")).isEqualTo("GP_SESSION=valid==");
        assertThat(internal.headers().getFirst("X-CSRF-TOKEN")).isEqualTo("token");
        assertThat(internal.headers().getFirst("X-Internal-Caller")).isEqualTo("api-gateway");
        assertThat(internal.headers().getFirst("X-Internal-Credential")).isEqualTo(MEMBER_SECRET);
        assertThat(internal.headers().getFirst("Authorization")).isNull();
        var downstream = GROUPS.stream().skip(groupBefore).findFirst().orElseThrow();
        assertThat(downstream.path()).isEqualTo(PURCHASES + "?source=client");
        assertThat(downstream.method()).isEqualTo("POST");
        assertThat(downstream.body()).isEqualTo("original body");
        assertThat(downstream.headers().getFirst("X-Request-Id"))
                .isEqualTo(internal.headers().getFirst("X-Request-Id"))
                .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(downstream.headers().getFirst("X-Internal-Credential")).isEqualTo(GROUP_SECRET);
        assertThat(downstream.headers().getFirst("X-Authenticated-Member-Id")).isEqualTo(ID);
        assertThat(downstream.headers().getFirst("X-Authenticated-Roles")).isEqualTo("BUYER,SELLER");
        assertThat(downstream.headers().keySet()).noneMatch(name -> java.util.Set.of(
                "cookie", "authorization", "x-csrf-token", "x-http-method-override", "x-authenticated-custom")
                .contains(name.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    void rejectsInvalidOrUnavailableAuthenticationWithoutForwardingOrRetrying() throws Exception {
        int groupBefore = GROUPS.size();
        int memberBefore = MEMBERS.size();
        assertProblem(request(MINE, "GET", "", Map.of()), 401, "UNAUTHENTICATED");
        assertProblem(request(MINE, "GET", "", Map.of("Cookie", "GP_SESSION=one; GP_SESSION=two")),
                400, "INVALID_REQUEST");
        assertProblem(request(MINE, "GET", "", Map.of("Cookie", "GP_SESSION=\"invalid\"")),
                400, "INVALID_REQUEST");
        assertThat(MEMBERS).hasSize(memberBefore);
        for (var scenario : Map.ofEntries(
                Map.entry("unknown", "401:UNAUTHENTICATED"), Map.entry("csrf", "403:CSRF_INVALID"),
                Map.entry("unavailable", "503:AUTHENTICATION_UNAVAILABLE"), Map.entry("slow", "504:GATEWAY_TIMEOUT"),
                Map.entry("denied", "502:BAD_GATEWAY"), Map.entry("redirect", "502:BAD_GATEWAY"),
                Map.entry("duplicate", "502:BAD_GATEWAY"), Map.entry("trailing", "502:BAD_GATEWAY"),
                Map.entry("large", "502:BAD_GATEWAY"), Map.entry("empty", "502:BAD_GATEWAY"),
                Map.entry("role", "502:BAD_GATEWAY"), Map.entry("buyer", "403:SELLER_ROLE_REQUIRED"),
                Map.entry("broken", "502:BAD_GATEWAY")).entrySet()) {
            String[] expected = scenario.getValue().split(":");
            assertProblem(request(MINE, "GET", "", Map.of("Cookie", "GP_SESSION=" + scenario.getKey())),
                    Integer.parseInt(expected[0]), expected[1]);
            assertThat(MEMBERS).as(scenario.getKey()).hasSize(++memberBefore);
            assertThat(GROUPS).as(scenario.getKey()).hasSize(groupBefore);
        }
        var head = request(MINE, "HEAD", "", Map.of());
        assertThat(head.statusCode()).isEqualTo(401);
        assertThat(head.body()).isEmpty();
        assertThat(request(MINE, "GET", "", Map.of("Cookie", "GP_SESSION=valid")).statusCode()).isEqualTo(200);
    }

    @Test
    void forwardsPublicPurchasePathsWithoutAuthenticationOrCredentials() throws Exception {
        int memberBefore = MEMBERS.size();
        int groupBefore = GROUPS.size();
        var detail = PURCHASES + "/" + ID;
        var supplied = Map.of("Cookie", "GP_SESSION=unavailable", "Authorization", "Bearer client",
                "X-CSRF-TOKEN", "forged", "X-Authenticated-Roles", "SELLER", "X-Internal-Caller", "api-gateway");
        for (String path : new String[]{PURCHASES, detail}) {
            for (String method : new String[]{"GET", "HEAD"}) {
                var response = request(path, method, "", supplied);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                if (method.equals("HEAD")) assertThat(response.body()).isEmpty();
            }
        }
        assertThat(MEMBERS).hasSize(memberBefore);
        assertThat(GROUPS.stream().skip(groupBefore).toList()).hasSize(4).allSatisfy(received ->
                supplied.keySet().forEach(header -> assertThat(received.headers().getFirst(header)).isNull()));
    }

    @Test
    void restrictsUuidRoutesToCanonicalPathsAndMethods() throws Exception {
        int memberBefore = MEMBERS.size();
        int groupBefore = GROUPS.size();
        String detail = PURCHASES + "/" + ID;
        for (String path : new String[]{detail + "/", detail + ";x", detail + "/extra",
                PURCHASES + "//" + ID, PURCHASES + "%2f" + ID, PURCHASES + "/%31" + ID.substring(1),
                PURCHASES + "/" + ID.toUpperCase(java.util.Locale.ROOT), MINE + "/" + ID + ";x",
                PURCHASES + "/auth-probe"}) {
            assertThat(request(path, "GET", "", Map.of()).statusCode()).as(path).isEqualTo(404);
        }
        assertThat(request(detail, "POST", "", Map.of()).statusCode()).isEqualTo(404);
        assertThat(request(detail + "/publish", "GET", "", Map.of()).statusCode()).isEqualTo(404);
        assertThat(request(detail + "%2Fpublish", "POST", "", Map.of()).statusCode()).isEqualTo(404);
        assertThat(MEMBERS).hasSize(memberBefore);
        assertThat(GROUPS).hasSize(groupBefore);
        var headers = Map.of("Cookie", "GP_SESSION=valid", "X-CSRF-TOKEN", "token");
        assertThat(request(detail, "PUT", "{}", headers).statusCode()).isEqualTo(200);
        assertThat(request(detail + "/publish", "POST", "{}", headers).statusCode()).isEqualTo(200);
        assertThat(request(MINE + "/" + ID, "HEAD", "", headers).statusCode()).isEqualTo(200);
        assertThat(GROUPS.stream().skip(groupBefore).map(Received::path).toList())
                .containsExactly(detail, detail + "/publish", MINE + "/" + ID);
    }

    @Test
    void keepsPublicCookieFieldsAndRejectsAliasesAndConnectionNamedHeaders() throws Exception {
        int memberBefore = MEMBERS.size();
        int groupBefore = GROUPS.size();
        for (String path : new String[] {"/api/auth/csrf/", "/api/Auth/csrf", "/api/auth%2fcsrf",
                "/api/auth/csrf;param", "/api//auth/csrf", "/api/auth/../auth/csrf",
                "/internal/auth/check", "/actuator/health", "/member/api/auth/csrf", MINE + "/"}) {
            assertThat(request(path, "GET", "", Map.of()).statusCode()).as(path).isEqualTo(404);
        }
        assertThat(request("/api/auth/csrf", "POST", "", Map.of()).statusCode()).isEqualTo(404);
        assertThat(request(MINE, "DELETE", "", Map.of()).statusCode()).isEqualTo(404);
        assertThat(rawRequest("GET", "Connection: close, cOoKiE\r\nCookie: GP_SESSION=valid\r\n"))
                .startsWith("HTTP/1.1 401");
        assertThat(rawRequest("GET", "Connection: close\r\nCookie: GP_SESSION=one\r\nCookie: GP_SESSION=two\r\n"))
                .startsWith("HTTP/1.1 400");
        assertThat(MEMBERS).hasSize(memberBefore);
        assertThat(rawRequest("POST", "Connection: close, x-csrf-token\r\nCookie: GP_SESSION=valid\r\n"
                + "X-CSRF-TOKEN: token\r\nContent-Length: 0\r\n")).startsWith("HTTP/1.1 403");
        assertThat(GROUPS).hasSize(groupBefore);
        var member = request("/api/auth/csrf", "GET", "", Map.of("X-Internal-Caller", "forged"));
        assertThat(member.statusCode()).isEqualTo(200);
        assertThat(member.headers().allValues("Set-Cookie")).containsExactly(
                "GP_SESSION=seed; Path=/; HttpOnly; SameSite=Lax", "another=two; Path=/; Secure");
        assertThat(member.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(MEMBERS.stream().skip(memberBefore + 1).findFirst().orElseThrow()
                .headers().getFirst("X-Internal-Caller")).isNull();
        assertThat(request("/api/auth/csrf", "HEAD", "", Map.of()).body()).isEmpty();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(code).isEqualTo(status);
        var json = JSON.readTree(response.body());
        assertThat(json.path("code").asText()).isEqualTo(code);
        assertThat(json.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private HttpResponse<String> request(String path, String method, String body, Map<String, String> headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String rawRequest(String method, String headers) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(8000);
            String path = method.equals("POST") ? PURCHASES : MINE;
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static HttpServer fixture(boolean member) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var received = new Received(exchange.getRequestURI().toString(), exchange.getRequestMethod(),
                            exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    (member ? MEMBERS : GROUPS).add(received);
                    if (member && received.path().equals("/internal/auth/check")) {
                        internalResponse(exchange, received);
                    } else if (member) {
                        exchange.getResponseHeaders().add("Set-Cookie", "GP_SESSION=seed; Path=/; HttpOnly; SameSite=Lax");
                        exchange.getResponseHeaders().add("Set-Cookie", "another=two; Path=/; Secure");
                        respond(exchange, 200, "{}");
                    } else {
                        exchange.getResponseHeaders().add("Set-Cookie", "GP_SESSION=forged");
                        respond(exchange, 200, "{}");
                    }
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void internalResponse(HttpExchange exchange, Received received) throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", "GP_SESSION=ignored");
        String session = received.headers().getFirst("Cookie").substring("GP_SESSION=".length());
        switch (session) {
            case "unknown" -> respond(exchange, 401, "{\"code\":\"UNAUTHENTICATED\"}");
            case "csrf" -> respond(exchange, 403, "{\"code\":\"CSRF_INVALID\"}");
            case "denied" -> respond(exchange, 403, "{\"code\":\"INTERNAL_CALLER_DENIED\"}");
            case "unavailable" -> respond(exchange, 503, "{\"code\":\"AUTHENTICATION_UNAVAILABLE\"}");
            case "duplicate" -> respond(exchange, 200, SUCCESS.replace("\"roles\"", "\"roles\":[],\"roles\""));
            case "trailing" -> respond(exchange, 200, SUCCESS + " {}");
            case "large" -> respond(exchange, 200, SUCCESS + " ".repeat(8192));
            case "empty" -> respond(exchange, 200, "");
            case "role" -> respond(exchange, 200, SUCCESS.replace("SELLER", "BUYER"));
            case "buyer" -> respond(exchange, 200, SUCCESS.replace("\"SELLER\",", ""));
            case "redirect" -> {
                exchange.getResponseHeaders().set("Location", "/internal/auth/check");
                respond(exchange, 302, "{}");
            }
            case "broken" -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write(1);
            }
            case "slow" -> {
                try {
                    Thread.sleep(3000);
                    respond(exchange, 200, SUCCESS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            default -> {
                if (received.body().contains("POST") && !"token".equals(received.headers().getFirst("X-CSRF-TOKEN"))) {
                    respond(exchange, 403, "{\"code\":\"CSRF_INVALID\"}");
                } else {
                    respond(exchange, 200, SUCCESS);
                }
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", status < 300 ? "application/json" : "application/problem+json");
        if (exchange.getRequestMethod().equals("HEAD") || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private record Received(String path, String method, Headers headers, String body) { }
}
