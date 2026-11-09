package com.example.member.interfaces.web.dto;
import java.util.*;
import com.example.member.infrastructure.web.error.AuthFailure;
public record InternalAuthRequest(String method) {
    public static InternalAuthRequest parse(Map<String,Object> body) {
        if(body.size()!=1 || !(body.get("method") instanceof String method)
            || !Set.of("GET","HEAD","OPTIONS","POST","PUT","PATCH","DELETE").contains(method))
            throw new AuthFailure(400,"INVALID_REQUEST");
        return new InternalAuthRequest(method);
    }
}
