package com.example.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class RoutingFailureClassificationTests {
    @Test void classifiesWrappedJavaTimeoutAsGatewayTimeout() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/group-purchases/ping"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, new Object());
        assertThat(RoutingFailureHandler.classify(exchange,
                new IllegalStateException(new TimeoutException()))).isEqualTo(504);
    }

    @Test void timeoutTakesPrecedenceOverOuterConnectionFailure() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/group-purchases/ping"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, new Object());
        assertThat(RoutingFailureHandler.classify(exchange,
                new IOException(new TimeoutException()))).isEqualTo(504);
    }
}
