package com.example.pgmock.infrastructure;

import com.example.pgmock.domain.PgFailure;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.*;

@Component
public class PgApiErrors implements HandlerExceptionResolver,Ordered {
    private final PgTelemetry telemetry;
    public PgApiErrors(PgTelemetry telemetry) { this.telemetry=telemetry; }
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    public ModelAndView resolveException(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
        int status=error instanceof PgFailure f?f.status:error instanceof ErrorResponse e?e.getStatusCode().value():500;
        for(Throwable cause=error;cause!=null;cause=cause.getCause())
            if(cause instanceof org.springframework.dao.DataAccessException || cause instanceof org.springframework.transaction.TransactionException) { status=503; break; }
        if(error instanceof org.springframework.http.converter.HttpMessageNotReadableException) status=400;
        String code=error instanceof PgFailure f?f.code:PgProblems.code(status);
        if(status==409 || status>=500) telemetry.failure(status==409?"conflict":"api_failed",error);
        if(status==409) telemetry.event("conflict",request.getRequestURI().contains("/refunds")?"REFUND":request.getRequestURI().contains("/approvals")?"APPROVAL":"CONTROL",code,null,0);
        try { PgProblems.write(request,response,status,code); } catch(java.io.IOException ignored) { }
        return new ModelAndView();
    }
}
