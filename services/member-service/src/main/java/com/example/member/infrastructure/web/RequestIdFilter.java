package com.example.member.infrastructure.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerMapping;

/** Request attributes survive ERROR/ASYNC dispatch; MDC belongs only to the current thread. */
public class RequestIdFilter implements Filter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final String STATE = RequestIdFilter.class.getName() + ".state";
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    public static String selectRequestId(List<String> values) {
        if(values.size() == 1 && values.getFirst().matches("[0-9a-f]{32}")){
            return values.getFirst();
        }
        return UUID.randomUUID().toString().replace("-","");
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if(id == null){
            id = selectRequestId(Collections.list(request.getHeaders(HEADER)));
            request.setAttribute(ATTRIBUTE, id);
            request.setAttribute(STATE, new Completion());
        }
        return id;
    }

    @Override
    public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {

        var request = (HttpServletRequest) input;
        var response = (HttpServletResponse) output;
        String id = resolveRequestId(request);
        response.setHeader(HEADER, id);
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
            var completion = (Completion) request.getAttribute(STATE);
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new AsyncListener() {
                    public void onComplete(AsyncEvent event) { completion.log(request, response); }
                    public void onError(AsyncEvent event) { }
                    public void onTimeout(AsyncEvent event) { }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                });
            } else {
                completion.log(request, response);
            }
        }

    }

    private static final class Completion {
        final long started = System.nanoTime();
        final AtomicBoolean logged = new AtomicBoolean();
        void log(HttpServletRequest request, HttpServletResponse response) {
            if (!(request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/internal/")) || !logged.compareAndSet(false, true)) return;
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LOG.atInfo().addKeyValue("event", "http_request_completed")
                    .addKeyValue("requestId", request.getAttribute(ATTRIBUTE))
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("route", route == null || "/**".equals(route) ? "UNMATCHED" : route)
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000.0)
                    .addKeyValue("termination", "completed").log("HTTP request completed");
        }
    }

}
