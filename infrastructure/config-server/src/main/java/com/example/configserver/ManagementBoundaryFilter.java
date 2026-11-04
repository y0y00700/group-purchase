package com.example.configserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reserve the management namespace instead of falling through to /{application}/{profile}. */
@Component
public class ManagementBoundaryFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/") && !path.equals("/actuator/health")
                && !path.startsWith("/actuator/health/") && !path.equals("/actuator/metrics")
                && !path.startsWith("/actuator/metrics/")) {
            response.sendError(404);
            return;
        }
        chain.doFilter(request, response);
    }
}
