package com.example.member.infrastructure.web.error;
import com.example.member.infrastructure.web.RequestIdFilter;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
public final class Problems {
    public static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static String code(int status) {
        return switch(status) {
            case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHENTICATED";
            case 403 -> "CSRF_INVALID"; case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED"; case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "AUTHENTICATION_UNAVAILABLE"; default -> "INTERNAL_ERROR";
        };
    }
    public static void write(HttpServletRequest request,HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control","no-store");
        String id = (String)request.getAttribute(RequestIdFilter.ATTRIBUTE);
        response.setHeader("X-Request-Id",id);
        if (!"HEAD".equals(request.getMethod())) response.getOutputStream().write(JSON.writeValueAsBytes(Map.of(
            "type","about:blank","title",HttpStatus.valueOf(status).getReasonPhrase(),"status",status,
            "detail","The request could not be completed.","instance","urn:request:"+id,"code",code,"requestId",id)));
    }
    public static void json(HttpServletResponse response,Object body) throws IOException {
        response.setContentType("application/json"); response.setHeader("Cache-Control","no-store");
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }
}
