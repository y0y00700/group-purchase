package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.grouppurchase.web.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterDispatchTests {
    @Test void reusesTheRequestAttributeAcrossErrorAndAsyncRedispatch() throws Exception {
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest("GET", "/api/dispatch-fixture");
        request.addHeader(RequestIdFilter.HEADER, "a".repeat(32));
        var first = new MockHttpServletResponse();
        filter.doFilter(request, first, (input, output) -> { });
        String id = first.getHeader(RequestIdFilter.HEADER);
        assertThat(id).isEqualTo("a".repeat(32));
        // Redispatch must consult the stored request attribute instead of reselecting a header.
        request.removeHeader(RequestIdFilter.HEADER);
        request.addHeader(RequestIdFilter.HEADER, "b".repeat(32));
        for (var type : new DispatcherType[]{DispatcherType.ERROR, DispatcherType.ASYNC}) {
            request.setDispatcherType(type);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (input, output) -> {
                assertThat(input.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(id);
                assertThat(MDC.get("requestId")).isEqualTo(id);
            });
            assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
            assertThat(MDC.get("requestId")).isNull();
        }
    }

    @Test void separateRequestsDoNotReuseAttributesOrThreadMdc() throws Exception {
        var filter = new RequestIdFilter();
        var first = new MockHttpServletRequest("GET", "/api/first-fixture");
        var second = new MockHttpServletRequest("GET", "/api/second-fixture");
        filter.doFilter(first, new MockHttpServletResponse(), (input, output) -> { });
        filter.doFilter(second, new MockHttpServletResponse(), (input, output) -> {
            assertThat(MDC.get("requestId")).isEqualTo(input.getAttribute(RequestIdFilter.ATTRIBUTE));
        });
        assertThat(first.getAttribute(RequestIdFilter.ATTRIBUTE))
                .isNotEqualTo(second.getAttribute(RequestIdFilter.ATTRIBUTE));
        assertThat(MDC.get("requestId")).isNull();
    }
}
