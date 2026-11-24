package com.example.grouppurchase.web;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import static com.example.grouppurchase.application.RequestFailure.*;

public final class ApiInput {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ApiInput() { }

    public static JsonNode object(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw INVALID_REQUEST.exception();
            return node;
        } catch (JacksonException invalid) {
            throw INVALID_REQUEST.exception();
        }
    }

    public static UUID id(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equals(value)) return id;
        } catch (IllegalArgumentException invalid) {
            throw NOT_FOUND.exception();
        }
        throw NOT_FOUND.exception();
    }

    public static String key(List<String> values) {
        if (values.size() != 1 || !KEY.matcher(values.getFirst()).matches()) throw INVALID_REQUEST.exception();
        return values.getFirst();
    }

    public static PageRequest page(MultiValueMap<String, String> parameters) {
        String requested = single(parameters, "limit");
        if (requested == null) return new PageRequest(20, single(parameters, "cursor"));
        if (!requested.matches("[0-9]{1,3}")) throw INVALID_REQUEST.exception();
        int limit = Integer.parseInt(requested);
        if (limit < 1 || limit > 100) throw INVALID_REQUEST.exception();
        return new PageRequest(limit, single(parameters, "cursor"));
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null) return null;
        if (values.size() != 1) throw INVALID_REQUEST.exception();
        return values.getFirst();
    }

    public record PageRequest(int limit, String cursor) { }
}
