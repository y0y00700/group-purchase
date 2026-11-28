package com.example.pgmock.interfaces;

import com.example.pgmock.domain.*;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;

public final class PgJson {
    public static final JsonMapper JSON=JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static JsonNode object(byte[] bytes,Set<String> fields) {
        try {
            JsonNode node=JSON.readTree(bytes);
            if(node==null || !node.isObject() || node.size()!=fields.size()) throw new PgFailure(400,"INVALID_REQUEST");
            for(String field:fields) if(!node.has(field) || node.get(field).isNull()) throw new PgFailure(400,"INVALID_REQUEST");
            return node;
        } catch(PgFailure e) { throw e; } catch(RuntimeException e) { throw new PgFailure(400,"INVALID_REQUEST"); }
    }
    public static String string(JsonNode node,String field) {
        if(!node.get(field).isString()) throw new PgFailure(400,"INVALID_REQUEST"); return node.get(field).asString();
    }
    public static long integer(JsonNode node,String field) {
        JsonNode value=node.get(field);
        if(!value.isIntegralNumber() || !value.canConvertToLong()) throw new PgFailure(400,"INVALID_REQUEST"); return value.longValue();
    }
    public static UUID uuid(String value) {
        try {
            if(value==null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException();
            return UUID.fromString(value);
        } catch(IllegalArgumentException e) { throw new PgFailure(400,"INVALID_REQUEST"); }
    }
}
