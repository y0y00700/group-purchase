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
        return bytes(status, id, code(status));
    }

    static byte[] bytes(int status, String id, String code) {
        var problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        problem.setDetail(switch (status){
            case 400 -> "요청 형식이 올바르지 않습니다.";
            case 403 -> "SELLER_ROLE_REQUIRED".equals(code)
                    ? "판매자 권한이 필요합니다." : "내부 호출자를 확인할 수 없습니다.";
            case 404 -> "요청한 API를 찾을 수 없습니다.";
            case 405 -> "허용하지 않는 요청 메서드입니다.";
            case 406 -> "지원하지 않는 응답 형식입니다.";
            case 409 -> switch (code) {
                case "GROUP_PURCHASE_DRAFT_VERSION_MISMATCH" -> "최신 초안을 조회한 뒤 다시 요청해 주세요.";
                case "GROUP_PURCHASE_NOT_DRAFT" -> "초안만 변경할 수 있습니다.";
                case "GROUP_PURCHASE_NOT_READY" -> "게시하려면 상품 정보와 판매 조건을 모두 입력해 주세요.";
                case "GROUP_PURCHASE_DEADLINE_PASSED" -> "최종 마감이 지났습니다.";
                case "IDEMPOTENCY_KEY_CONFLICT" -> "이 키로 성공한 요청의 내용이 다릅니다.";
                default -> "현재 상태에서 요청을 처리할 수 없습니다.";
            };
            case 415 -> "지원하지 않는 요청 형식입니다.";
            case 502 -> "대상 서비스에 연결할 수 없습니다.";
            case 503 -> "GROUP_PURCHASE_BUSY".equals(code)
                    ? "공동구매 변경이 진행 중입니다. 같은 요청으로 다시 시도해 주세요."
                    : "서비스를 일시적으로 사용할 수 없습니다.";
            case 504 -> "대상 서비스 응답 시간이 초과되었습니다.";
            default -> "서버에서 요청을 처리하지 못했습니다.";
        });
        problem.setInstance(URI.create("urn:request:" + id));
        problem.setProperty("code", code);
        problem.setProperty("requestId", id);
        return JSON.writeValueAsBytes(java.util.Map.of(
            "type", problem.getType().toString(), "title", problem.getTitle(),
            "status", status, "detail", problem.getDetail(), "instance", problem.getInstance().toString(),
            "code", code, "requestId", id));

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
