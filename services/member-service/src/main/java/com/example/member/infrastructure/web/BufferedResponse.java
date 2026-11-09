package com.example.member.infrastructure.web;

import com.example.member.infrastructure.web.error.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;

/** Only bounded authentication responses; no headers or bytes reach the network until publish. */
final class BufferedResponse extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final Map<String,List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private int status = 200;
    private PrintWriter writer;
    private final ServletOutputStream stream = new ServletOutputStream() {
        public boolean isReady() { return true; }
        public void setWriteListener(WriteListener listener) { throw new UnsupportedOperationException(); }
        public void write(int value) throws IOException {
            if (bytes.size() >= 65536) throw new IOException("Authentication response limit exceeded");
            bytes.write(value);
        }
    };
    BufferedResponse(HttpServletResponse original) { super(original); }
    @Override public void setStatus(int value) { status=value; }
    @Override public int getStatus() { return status; }
    @Override public void setHeader(String key,String value) { if(value==null)headers.remove(key); else headers.put(key,new ArrayList<>(List.of(value))); }
    @Override public void addHeader(String key,String value) { headers.computeIfAbsent(key,k->new ArrayList<>()).add(value); }
    @Override public String getHeader(String key) { return headers.containsKey(key) ? headers.get(key).getFirst() : null; }
    @Override public Collection<String> getHeaders(String key) { return headers.getOrDefault(key,List.of()); }
    @Override public Collection<String> getHeaderNames() { return headers.keySet(); }
    @Override public boolean containsHeader(String key) { return headers.containsKey(key); }
    @Override public void setContentType(String type) { setHeader("Content-Type",type); }
    @Override public String getContentType() { return getHeader("Content-Type"); }
    @Override public void setContentLength(int length) { setHeader("Content-Length",Integer.toString(length)); }
    @Override public void setContentLengthLong(long length) { setHeader("Content-Length",Long.toString(length)); }
    @Override public void setIntHeader(String key,int value) { setHeader(key,Integer.toString(value)); }
    @Override public void addIntHeader(String key,int value) { addHeader(key,Integer.toString(value)); }
    @Override public void setDateHeader(String key,long value) { setHeader(key,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneOffset.UTC))); }
    @Override public void addDateHeader(String key,long value) { addHeader(key,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneOffset.UTC))); }
    @Override public void addCookie(Cookie cookie) { addHeader("Set-Cookie",org.springframework.http.ResponseCookie.from(cookie.getName(),cookie.getValue()).path(cookie.getPath()).httpOnly(cookie.isHttpOnly()).secure(cookie.getSecure()).maxAge(cookie.getMaxAge()).build().toString()); }
    @Override public ServletOutputStream getOutputStream() { return stream; }
    @Override public PrintWriter getWriter() { if(writer==null) writer=new PrintWriter(new OutputStreamWriter(stream,java.nio.charset.StandardCharsets.UTF_8)); return writer; }
    @Override public boolean isCommitted() { return false; }
    @Override public void flushBuffer() { if(writer!=null) writer.flush(); }
    @Override public void resetBuffer() { bytes.reset(); }
    @Override public void reset() { bytes.reset(); headers.clear(); status=200; }
    @Override public void sendError(int value) { status=value; bytes.reset(); }
    @Override public void sendError(int value,String message) { sendError(value); }
    @Override public void sendRedirect(String location) { throw new IllegalStateException("Redirect is disabled"); }
    void publish(HttpServletResponse original) throws IOException {
        flushBuffer();
        original.setStatus(status);
        headers.forEach((k,values)-> { original.setHeader(k,values.getFirst()); for(int i=1;i<values.size();i++) original.addHeader(k,values.get(i)); });
        original.getOutputStream().write(bytes.toByteArray());
    }
}
