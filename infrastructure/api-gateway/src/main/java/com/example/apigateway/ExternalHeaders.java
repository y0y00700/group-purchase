package com.example.apigateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

final class ExternalHeaders {
    private static final Set<String> REMOVED = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade",
            "x-http-method", "x-http-method-override", "x-method-override");

    private ExternalHeaders() { }

    static void sanitize(HttpHeaders headers) {
        var removed = new HashSet<>(REMOVED);
        for(String field : headers.getOrEmpty(HttpHeaders.CONNECTION)) {
            for (String name : field.split(",")) {
                removed.add(name.strip().toLowerCase(Locale.ROOT));
            }
        }
        for(String name : new ArrayList<>(headers.headerNames())){
            String lower = name.toLowerCase(Locale.ROOT);
            if(removed.contains(lower) || lower.startsWith("x-authenticated-") || lower.startsWith("x-internal-")){
                headers.remove(name);
            }
        }
    }
}
