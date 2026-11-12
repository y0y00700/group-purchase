package com.example.grouppurchase.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** Writes JSON directly so incompatible Accept headers cannot cause recursive negotiation errors. */
@Component
public class ApiExceptionResolver implements HandlerExceptionResolver, Ordered {
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception failure) {

        if (!request.getRequestURI().startsWith("/api/") || response.isCommitted()) return null;
        int status = failure instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || (failure instanceof org.springframework.beans.TypeMismatchException
                    && !(failure instanceof org.springframework.beans.ConversionNotSupportedException))) {
            status = 400;
        }
        if (HttpStatus.resolve(status) == null) status = 500;
        if (failure instanceof ErrorResponse error) {
            error.getHeaders().forEach((key, values) -> values.forEach(value -> response.addHeader(key, value)));
        }
        String id = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (status == 500) ApiProblems.unexpected(failure, id);
        try {
            write(request, response, status, id);
            return new ModelAndView();
        } catch (IOException disconnected) {
            // Do not ask another resolver to append a second body after a failed write.
            return new ModelAndView();
        }

    }

    static void write(HttpServletRequest request, HttpServletResponse response, int status, String id)
            throws IOException {
        write(request, response, status, id, ApiProblems.code(status));
    }

    static void write(HttpServletRequest request, HttpServletResponse response, int status, String id, String code)
            throws IOException {
        response.setStatus(status);
        response.setHeader(RequestIdFilter.HEADER, id);
        response.setContentType("application/problem+json");
        if (!"HEAD".equals(request.getMethod())) response.getOutputStream().write(ApiProblems.bytes(status, id, code));
    }
}
