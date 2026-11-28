package com.example.pgmock.infrastructure;

import com.example.pgmock.interfaces.PgJson;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class PgProblems {
    public static String code(int status) { return switch(status) {
        case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHENTICATED"; case 403 -> "FORBIDDEN";
        case 404 -> "NOT_FOUND"; case 405 -> "METHOD_NOT_ALLOWED"; case 413 -> "PAYLOAD_TOO_LARGE";
        case 415 -> "UNSUPPORTED_MEDIA_TYPE"; case 503 -> "PG_UNAVAILABLE"; default -> "INTERNAL_ERROR";
    }; }
    public static void write(HttpServletRequest request,HttpServletResponse response,int status,String code) throws IOException {
        String id=(String)request.getAttribute("pg.requestId");
        response.setStatus(status); response.setContentType("application/problem+json");
        response.setHeader("Cache-Control","no-store"); response.setHeader("X-Request-Id",id);
        if(status==503) response.setHeader("Retry-After","1");
        if(status==401) response.setHeader("WWW-Authenticate","Bearer");
        if(!request.getMethod().equals("HEAD")) response.getOutputStream().write(PgJson.JSON.writeValueAsBytes(Map.of(
            "type","about:blank","title",HttpStatus.valueOf(status).getReasonPhrase(),"status",status,
            "detail","The request could not be completed.","instance","urn:request:"+id,"code",code,"requestId",id)));
    }
}
