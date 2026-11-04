package com.example.grouppurchase.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** Keep Boot's non-API error contract while covering container ERROR dispatch for APIs. */
@Controller
public class ApiErrorController implements ErrorController {
    private final BasicErrorController fallback;
    public ApiErrorController(ErrorAttributes attributes) {
        fallback = new BasicErrorController(attributes, new ErrorProperties());
    }
    @RequestMapping("${server.error.path:/error}")
    public Object error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (original instanceof String path && path.startsWith("/api/")) {
            if (!response.isCommitted()) {
                Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
                int status = value instanceof Integer number ? number : 500;
                ApiExceptionResolver.write(request, response, status,
                        (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
            }
            return null;
        }
        return fallback.error(request);
    }
}
