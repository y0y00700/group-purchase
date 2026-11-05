package com.example.grouppurchase.web;

import java.net.URI;
import java.util.Arrays;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/** Only server-owned fields may enter the public problem or application error log. */
public final class ApiProblems {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ApiProblems() { }
    public static String code(int status) {
        return switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "GATEWAY_TIMEOUT";
            default -> "INTERNAL_ERROR";
        };
    }
    public static byte[] bytes(int status, String id) {

        var problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        problem.setDetail(switch (status){
            case 400 -> "요청 형식이 올바르지 않습니다.";
            case 404 -> "요청한 API를 찾을 수 없습니다.";
            case 405 -> "허용하지 않는 요청 메서드입니다.";
            case 406 -> "지원하지 않는 응답 형식입니다.";
            case 415 -> "지원하지 않는 요청 형식입니다.";
            case 502 -> "대상 서비스에 연결할 수 없습니다.";
            case 503 -> "서비스를 일시적으로 사용할 수 없습니다.";
            case 504 -> "대상 서비스 응답 시간이 초과되었습니다.";
            default -> "서버에서 요청을 처리하지 못했습니다.";
        });
        problem.setInstance(URI.create("urn:request:" + id));
        problem.setProperty("code", code(status));
        problem.setProperty("requestId", id);
        return JSON.writeValueAsBytes(java.util.Map.of(
            "type", problem.getType().toString(), "title", problem.getTitle(),
            "status", status, "detail", problem.getDetail(), "instance", problem.getInstance().toString(),
            "code", code(status), "requestId", id));

    }
    public static void unexpected(Throwable failure, String id) {
        LoggerFactory.getLogger(ApiProblems.class).atError()
                .addKeyValue("requestId", id).addKeyValue("code", "INTERNAL_ERROR")
                .addKeyValue("exceptionType", failure.getClass().getSimpleName())
                .addKeyValue("frames", Arrays.stream(failure.getStackTrace()).limit(8)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber()).toList())
                .log("Unexpected API failure");
    }
}
