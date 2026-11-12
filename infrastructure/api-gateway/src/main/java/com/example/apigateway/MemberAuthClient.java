package com.example.apigateway;

import static com.example.apigateway.AuthCheckFailure.*;

import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;

import java.security.Identity;
import java.sql.Array;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
final class MemberAuthClient {
    static final String CALLER_HEADER = "X-Internal-Caller";
    static final String CREDENTIAL_HEADER = "X-Internal-Credential";
    static final String CALLER = "api-gateway";
    static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "GP_SESSION";
    private static final Pattern COOKIE_VALUE = Pattern.compile("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]+");
    private static final Set<String> ROLES = Set.of("BUYER", "SELLER", "OPERATOR");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final LoadBalancerClientFactory discovery;
    private final String credential;
    private final WebClient client;
    private final Duration timeout;

    MemberAuthClient(LoadBalancerClientFactory discovery,
            @Value("${app.auth.member-internal-credential}") String credential,
            @Value("${app.auth.connect-timeout:1s}") Duration connectTimeout,
            @Value("${app.auth.check-timeout:2s}") Duration timeout) {
        this.discovery = discovery;
        this.credential = requireCredential(credential);
        this.timeout = timeout;
        var transport = HttpClient.create().disableRetry(true).followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()));
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8192)).build();
    }

    static String requireCredential(String secret) {
        if (!secret.matches("[A-Za-z0-9._~-]{32,}")) {
            throw new IllegalArgumentException("Internal credential must contain at least 32 ASCII token characters");
        }
        return secret;
    }

    Mono<Identity> check(ServerHttpRequest original, String requestId) {
        return Mono.defer(() -> {
            String session = sessionCookie(original.getHeaders());
            var balancer = discovery.getInstance("member", ReactorServiceInstanceLoadBalancer.class);
            if (balancer == null) {
                return Mono.error(AUTHENTICATION_UNAVAILABLE.exception());
            }
            return balancer.choose().flatMap(selected -> {
                if (!selected.hasServer()) {
                    return Mono.error(AUTHENTICATION_UNAVAILABLE.exception());
                }
                return send(selected.getServer(), original, requestId, session);
            }).switchIfEmpty(Mono.error(AUTHENTICATION_UNAVAILABLE.exception()));
        }).timeout(timeout).onErrorMap(failure -> {
            if (failure instanceof AuthCheckFailure.Rejected) {
                return failure;
            }
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof TimeoutException || cause instanceof ConnectTimeoutException) {
                    return GATEWAY_TIMEOUT.exception();
                }
            }
            return BAD_GATEWAY.exception();
        });
    }

    private Mono<Identity> send(org.springframework.cloud.client.ServiceInstance instance,
            ServerHttpRequest original, String requestId, String session) {
        var target = LoadBalancerUriTools.reconstructURI(instance, java.net.URI.create("http://member/internal/auth/check"));
        HttpMethod method = original.getMethod();
        return client.post().uri(target).contentType(MediaType.APPLICATION_JSON)
            .headers(headers ->{
                headers.set(RequestIdWebFilter.HEADER, requestId);
                headers.set(CALLER_HEADER, CALLER);
                headers.set(CREDENTIAL_HEADER, credential);
                headers.set(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + session);
                if(method != HttpMethod.GET && method != HttpMethod.HEAD){
                    headers.addAll(CSRF_HEADER, original.getHeaders().getOrEmpty(CSRF_HEADER));
                }

            })
            .bodyValue(Map.of("method",method.name()))
            .exchangeToMono(this::readResponse);
    }

    private static String sessionCookie(HttpHeaders headers) {
        String session = null;
        for (String field : headers.getOrEmpty(HttpHeaders.COOKIE)) {
            for (String item : field.split(";")) {
                String cookie = item.strip();
                int equals = cookie.indexOf('=');
                String name = equals < 0 ? cookie : cookie.substring(0, equals).strip();
                if (!SESSION_COOKIE.equals(name)) {
                    continue;
                }
                if (session != null || !cookie.startsWith(SESSION_COOKIE + "=")) {
                    throw INVALID_REQUEST.exception();
                }
                session = cookie.substring(equals + 1);
                if (!COOKIE_VALUE.matcher(session).matches()) {
                    throw INVALID_REQUEST.exception();
                }
            }
        }
        if (session == null) {
            throw UNAUTHENTICATED.exception();
        }
        return session;
    }

    private Mono<Identity> readResponse(ClientResponse response) {
        int status = response.statusCode().value();
        MediaType type = response.headers().contentType().orElse(null);
        MediaType expected = status == 200 ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON;
        if (type == null || !expected.isCompatibleWith(type)) {
            return Mono.error(BAD_GATEWAY.exception());
        }
        return response.bodyToMono(byte[].class).switchIfEmpty(Mono.error(BAD_GATEWAY.exception()))
                .map(body -> classifyResponse(status, JSON.readTree(body)));
    }

    private static Identity classifyResponse(int status, JsonNode json) {
        if(json == null || !json.isObject()){
            throw  BAD_GATEWAY.exception();
        }
        if(status != 200){
            String code = json.path("code").asText("");
            if(status == 401 && code.equals(UNAUTHENTICATED.name())){
                throw UNAUTHENTICATED.exception();
            }
            if(status == 403 && code.equals(CSRF_INVALID.name())){
                throw CSRF_INVALID.exception();
            }
            if(status == 503 && code.equals(AUTHENTICATION_UNAVAILABLE.name())){
                throw AUTHENTICATION_UNAVAILABLE.exception();
            }
            throw BAD_GATEWAY.exception();
        }
        String member = json.path("memberId").asText("");
        UUID memberId = UUID.fromString(member);
        var roles = json.path("roles");
        if(!memberId.toString().equalsIgnoreCase(member) || !roles.isArray() || roles.isEmpty()){
            throw BAD_GATEWAY.exception();
        }
        var unique = new HashSet<String>();
        for(JsonNode role : roles){
            if(!role.isString() || !ROLES.contains(role.asText()) || !unique.add(role.asText())){
                throw BAD_GATEWAY.exception();
            }
        }
        var ordered = new ArrayList<>(unique);
        ordered.sort(String::compareTo);
        return new Identity(memberId, List.copyOf(ordered));
    }

    record Identity(UUID memberId, List<String> roles) { }
}
