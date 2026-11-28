package com.example.pgmock.infrastructure;

import com.example.pgmock.domain.PgFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.*;

public class PgBoundaryFilter implements Filter {
    private final byte[] api;
    private final byte[] control;
    private final boolean enabled;
    public PgBoundaryFilter(String api,String control,boolean enabled) {
        if(!valid(api) || enabled && (!valid(control) || api.equals(control)))
            throw new IllegalArgumentException("Dedicated distinct PG credentials of 32-128 token characters are required");
        this.api=api.getBytes(StandardCharsets.US_ASCII); this.control=control.getBytes(StandardCharsets.US_ASCII); this.enabled=enabled;
    }
    private static boolean valid(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{32,128}"); }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var request=(HttpServletRequest)input; var response=(HttpServletResponse)output;
        var ids=Collections.list(request.getHeaders("X-Request-Id"));
        String id=ids.size()==1 && ids.getFirst().matches("[0-9a-f]{32}")?ids.getFirst():UUID.randomUUID().toString().replace("-","");
        request.setAttribute("pg.requestId",id); response.setHeader("X-Request-Id",id); response.setHeader("Cache-Control","no-store");
        long started=System.nanoTime(); String previous=MDC.get("requestId"); MDC.put("requestId",id);
        try {
            String path=request.getRequestURI(); String method=request.getMethod();
            boolean admin=path.startsWith("/__admin/");
            if(admin && !enabled) throw new PgFailure(404,"NOT_FOUND");
            boolean health=path.matches("/actuator/health(?:/(?:liveness|readiness))?");
            if(!health) authenticate(request,admin);
            Set<String> methods=methods(path);
            if(methods.isEmpty()) throw new PgFailure(404,"NOT_FOUND");
            if(!methods.contains(method)) { response.setHeader("Allow",String.join(", ",methods)); throw new PgFailure(405,"METHOD_NOT_ALLOWED"); }
            if(method.equals("POST") || method.equals("PUT")) {
                if(!org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(parseType(request.getContentType()))) throw new PgFailure(415,"UNSUPPORTED_MEDIA_TYPE");
                if(request.getContentLengthLong()>16384) throw new PgFailure(413,"PAYLOAD_TOO_LARGE");
                byte[] body=request.getInputStream().readNBytes(16385);
                if(body.length>16384) throw new PgFailure(413,"PAYLOAD_TOO_LARGE");
                if(method.equals("POST") && Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) throw new PgFailure(400,"INVALID_REQUEST");
                request=new BodyRequest(request,body);
            }
            chain.doFilter(request,response);
        } catch(PgFailure failure) { PgProblems.write(request,response,failure.status,failure.code); }
        finally {
            Object route=request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LoggerFactory.getLogger(PgBoundaryFilter.class).atInfo().addKeyValue("event","http_request_completed")
                .addKeyValue("route",route==null?"UNMATCHED":route.toString())
                .addKeyValue("method",request.getMethod()).addKeyValue("status",response.getStatus())
                .addKeyValue("durationMs",(System.nanoTime()-started)/1_000_000.0).log("PG HTTP completed");
            if(previous==null) MDC.remove("requestId"); else MDC.put("requestId",previous);
        }
    }
    private static org.springframework.http.MediaType parseType(String type) {
        try { return org.springframework.http.MediaType.parseMediaType(type==null?"application/octet-stream":type); }
        catch(IllegalArgumentException e) { throw new PgFailure(415,"UNSUPPORTED_MEDIA_TYPE"); }
    }
    private void authenticate(HttpServletRequest request,boolean admin) {
        var values=Collections.list(request.getHeaders("Authorization"));
        if(values.size()!=1 || !values.getFirst().startsWith("Bearer ")) throw new PgFailure(401,"UNAUTHENTICATED");
        byte[] supplied=values.getFirst().substring(7).getBytes(StandardCharsets.UTF_8);
        boolean apiMatch=MessageDigest.isEqual(api,supplied);
        if(admin) {
            if(apiMatch) throw new PgFailure(403,"FORBIDDEN");
            if(!MessageDigest.isEqual(control,supplied)) throw new PgFailure(401,"UNAUTHENTICATED");
        } else if(!apiMatch) throw new PgFailure(401,"UNAUTHENTICATED");
    }
    private static Set<String> methods(String path) {
        if(path.matches("/pg/v1/(approvals|refunds)")) return Set.of("POST");
        if(path.matches("/pg/v1/(approvals|refunds)/[^/]+")) return Set.of("GET");
        if(path.matches("/__admin/v1/scenarios/[^/]+/[^/]+")) return Set.of("PUT","GET");
        if(path.equals("/__admin/v1/transactions")) return Set.of("GET");
        if(path.matches("/actuator/(health(?:/(?:liveness|readiness))?|info|metrics(?:/[^/]+)?)")) return Set.of("GET","HEAD");
        return Set.of();
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        BodyRequest(HttpServletRequest request,byte[] bytes) { super(request); this.bytes=bytes; }
        public int getContentLength() { return bytes.length; }
        public long getContentLengthLong() { return bytes.length; }
        public ServletInputStream getInputStream() {
            var stream=new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                public int read() { return stream.read(); }
                public boolean isFinished() { return stream.available()==0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous MVC input"); }
            };
        }
    }
}
