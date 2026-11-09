package com.example.member.infrastructure.web.error;
import com.example.member.application.MemberAuthenticationException;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.*;
@Component
public class ApiErrors implements HandlerExceptionResolver, Ordered {
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    public ModelAndView resolveException(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
        int status = error instanceof AuthFailure f ? f.status : error instanceof ErrorResponse e ? e.getStatusCode().value() : 500;
        if(error instanceof MemberAuthenticationException) status=401;
        if (StorageFailures.unavailable(error)) status = 503;
        if (error instanceof org.springframework.http.converter.HttpMessageNotReadableException) status=400;
        if(status>=500) org.slf4j.LoggerFactory.getLogger(ApiErrors.class).atError()
            .addKeyValue("code",Problems.code(status)).addKeyValue("exceptionType",error.getClass().getSimpleName())
            .log("Authentication API failed");
        try { Problems.write(request,response,status,error instanceof MemberAuthenticationException f ? f.reason().name() : error instanceof AuthFailure f ? f.code : Problems.code(status)); }
        catch(java.io.IOException ignored) { }
        return new ModelAndView();
    }
}
