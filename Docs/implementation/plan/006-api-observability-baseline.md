# 006. API·로그·헬스 기준

작성일: 2026-11-04 KST

상태: **완료 — 사용자 작성·로컬 최종 통합 검증 완료**. 세 묶음을 확정 계약으로 채택했다. 이번 사용자 요청에 따라 커밋·push·main 대상 PR까지 진행하며 병합은 포함하지 않는다. 실제 변경·50개 테스트·Compose/DB 복구·Windows 제품 JAR의 근거는 [006 최종 결과](../006-api-observability-baseline.md#final-results)에서 관리한다.

[구현 목록](../README.md) · [로드맵](roadmap.md) · [프로젝트 인계](../../handoff.md)

<a id="execution-summary"></a>

## 실행 요약

- **목표:** 이후 업무 API에 적용할 오류·요청 추적·기본 관측 계약을 현재 실행 모듈에서 검증한다.
- **현재:** 001~005 로컬 구현·검증 완료. Gateway는 WebFlux, 공동구매는 MVC이며 ping은 DB를 조회하지 않는다.
- **원격 기준:** 2026-11-04 PR 준비에서 최신 main `e5c87d8`과 기존 CI 두 검사 성공·열린 PR 없음을 확인했다. 006의 PR/CI 상태는 결과·인계에서 구분한다.
- **기존 합의:** 오류 코드·메시지·추적 정보, JSON 로그, Actuator·Micrometer와 제한된 메트릭 라벨을 사용한다.
- **묶음 1 채택:** 업무 API와 Gateway 자체 오류에 RFC 9457 형식 및 `code`·`requestId`를 적용한다.
- **묶음 2 채택:** Gateway에서 요청 ID를 새로 발급하고 공동구매까지 전달한다. 네 JVM 앱에 Boot 기본 JSON 로그를 적용한다.
- **묶음 3 채택:** 기존 종합 health를 유지하며 liveness/readiness와 내부 진단용 metrics를 추가한다.
- **오류 적용 범위:** Gateway 공개 포트의 자체 오류, 공동구매 `/api/**`. Config·Eureka 프로토콜·Actuator 본문은 기존 계약을 따른다.
- **전달 경계:** Gateway는 하위 서비스의 상태·본문을 그대로 전달한다. 오류 본문 통일을 위해 전체 응답을 버퍼링하지 않는다.
- **상태 유지:** Gateway 비허용 경로/메서드 404, 대상 없음 503, 연결 실패 502, 제한시간 초과 504를 유지한다.
- **요청 ID:** `X-Request-Id`, 소문자 32자리 16진수. 공개 Gateway 입력은 덮어쓰고 서비스는 검증한 값을 사용한다.
- **추적 경계:** 이 값은 로그 연결용이며 인증·권한·멱등성 키 또는 분산 추적 span이 아니다.
- **로그:** stdout의 한 이벤트 한 JSON. 요청 완료에 ID·메서드·정규화 경로·상태·소요 시간을 기록한다.
- **실행 방식:** MVC는 요청 속성/MDC 복원, WebFlux는 exchange 속성/Reactor Context와 명시적 구조화 필드를 사용한다.
- **노출 금지:** 비밀번호·쿠키·토큰·전체 헤더·본문·쿼리·예외 원문을 요청/오류 로그와 오류 응답에 넣지 않는다.
- **health:** liveness는 외부 의존성을 제외한다. 공동구매 readiness에는 DB를 포함하고 기존 종합 DB health를 보존한다.
- **노출 경계:** 기존 loopback 포트와 Gateway 관리 포트 9000을 유지한다. 공개 8000에서 Actuator는 계속 404다.
- **지표:** HTTP·JVM·프로세스·공동구매 연결 풀을 확인한다. 요청 ID·원본 URL·업무 ID를 라벨로 쓰지 않는다.
- **포함:** 두 웹 스택 오류/추적 처리, 네 앱 JSON·health/metrics 설정, 통합·회귀 검사와 CI 연결·직접 테스트 안내.
- **제외:** 회원/세션·인증/CSRF·업무 API·DB 변경·Kafka/Outbox·Prometheus/Grafana·추적 서버·성능 목표 판정.
- **버전:** Java 21·Boot 4.0.9·Cloud 2025.1.4·Gradle 9.8.2를 유지한다. 별도 로깅 제품·공통 starter를 도입하지 않는다.
- **변경 위치:** Gateway/공동구매 Java·테스트, 네 앱 최초 YAML, `config-repo/`, 검사 스크립트·CI·문서.
- **완료 기준:** 실제 HTTP 오류 계약·ID 전파/격리·JSON 파싱/민감정보 검사·health/metrics·기존 동작 회귀를 확인한다.
- **구현 후 기록:** 같은 번호 결과 문서, 사용자 직접 테스트, 관련 학습 문서와 짧은 인계를 갱신한다.
- **합의한 학습 방식:** 핵심 코드를 먼저 구현·검증한 뒤 같은 파일의 참고 주석과 직접 입력 구간으로 제공한다. 아래 [직접 작성 순서](#guided-coding-units)를 AI가 안내하고 사용자 재작성 후 리뷰·재검증한다.
- **확정/현재:** 세 묶음 전체 채택. 여섯 Step 작성·위임 수정과 현재 활성 소스의 최종 통합 검증을 마쳤다. 작성 대기 placeholder·중복 참고 주석은 정리했고 같은 구간을 다시 비우지 않는다.

## 목표·현재 상태·설계 근거

회원·공동구매 업무 기능을 추가하기 전에 요청 실패를 같은 규칙으로 표현하고, 응답의 요청 ID로 해당 앱과 Gateway 로그를 찾을 수 있게 한다. 지표는 실제 요청에 따라 변하는지 확인하고 앱의 생존·준비·외부 의존성 상태를 구분한다.

계획 작성 당시 `RoutingFailureHandler`는 일부 라우팅 예외를 빈 502/504 응답으로 처리했다. Gateway 통합 테스트는 대상 없음 503과 비허용 경로/메서드 404, 하위 서비스의 503 전달을 확인했다. 공동구매에는 정상 ping만 있고 공통 오류 처리·요청 ID·JSON 로그 설정은 없었다. Actuator 노출은 중앙 설정의 `health,info`와 Config 자체 `health`였고 Gateway의 Actuator는 9000 포트에 있었다. 현재 구현과 달라진 세부 선택은 결과 문서를 따른다.

설계 기준은 [외부 API와 요청 멱등성](../../design/architecture.md#api-contract), [관측 정책](../../design/operations.md#observability), [실행 구성 검증](../../design/operations.md#runtime-checks), [API 계약 검증](../../design/verification.md#contract-checks)이다. 정확한 공통 오류 필드 등은 기존 상위 설계에서 미정이므로 아래에서 제안한다. 업무별 HTTP 매핑·멱등성 저장·권한 판단은 해당 기능 계획에서 정한다.

<a id="design-choices"></a>

## 세 묶음의 선택·대안

| 묶음 | 결정할 문제 | 추천과 이유 | 대안과 비용 |
|---|---|---|---|
| 1. 오류 계약 | 어떤 오류를 어느 계층에서 같은 형식으로 만들 것인가 | RFC 9457 `ProblemDetail`에 `code`·`requestId`를 추가. MVC와 WebFlux의 처리 진입점을 분리하고 동일 계약을 검증 | 자체 `{code,message,requestId}`는 단순하지만 표준 HTTP 오류 필드도 별도로 정의해야 함. Gateway 전체 오류 재작성은 하위 응답/스트리밍을 바꾸고 중복 처리 비용 발생 |
| 2. 요청 추적·로그 | ID의 발급 주체와 동시 요청의 로그 연결 방법 | 공개 Gateway가 ID 발급, 서비스까지 전달. Boot 기본 Logstash JSON과 웹 스택별 요청 컨텍스트 사용 | 외부 ID 그대로 사용은 발급은 쉬우나 충돌·위조 입력 관리 필요. Tracing 도입은 span·전파 규칙·의존성을 늘리므로 관측 필요성이 커질 때 검토 |
| 3. health·지표 | 프로세스 상태와 의존성 상태, 진단 지표를 어디서 확인할 것인가 | 기존 health에 역할별 probes·진단 metrics 추가. 기존 내부 노출 경계와 Micrometer 자동 계측을 사용 | 종합 health만 두면 원인 구분이 어려움. Prometheus/Grafana까지 추가하면 수집·저장·대시보드 범위가 함께 커지므로 로드맵 19에서 연결 |

공통 계약은 이 계획에서 관리하고 두 웹 스택은 모듈 내부에 각각 구현한다. 현재 두 적용처의 작은 계약을 위해 신규 공통 모듈·starter·자동 설정을 먼저 만들지 않는다. 실제 중복과 후속 회원 서비스 적용 비용을 확인한 뒤 공유 범위를 재검토한다.

## 묶음 1. 공통 오류 응답

### 형식과 적용 범위

다음은 채택한 계약의 예시다. 실제 실행 근거는 결과 문서에서 관리한다.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json
X-Request-Id: 7fa6104d6a004f3baae4f4a3c04bd620
```

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "요청 형식이 올바르지 않습니다.",
  "instance": "urn:request:7fa6104d6a004f3baae4f4a3c04bd620",
  "code": "INVALID_REQUEST",
  "requestId": "7fa6104d6a004f3baae4f4a3c04bd620"
}
```

- 위 일곱 필드를 필수로 한다. `type`은 초기에는 `about:blank`, `title`은 해당 HTTP 상태의 표준 영문 구문, `detail`은 서버가 정한 안전한 한국어 안내다. `status`와 실제 HTTP 상태는 일치해야 한다.
- `instance`는 요청 ID 기반 URN으로 명시한다. Framework 기본값의 실제 URL 경로가 자동 삽입되지 않게 한다. `timestamp`·원본 URL·쿼리·예외명·스택·SQL·설정값은 응답에 넣지 않는다.
- 클라이언트 분기는 `code`를 사용한다. 메시지 문자열에 의존하지 않는다. 향후 도메인 코드는 해당 서비스가 정의하고 같은 코드를 다른 의미로 재사용하지 않는다.
- 필드 검증 실패에는 선택 필드 `violations: [{"field":"quantity","code":"INVALID"}]`를 허용한다. 서버가 아는 필드명과 제한된 검증 코드만 반환하고 입력값·바인딩 객체·기본 검증 메시지는 반환하지 않는다. 현재 제품 API에 DTO를 추가하지 않고 테스트 fixture로 검증한다.
- 성공 ping의 상태·본문은 유지한다. 오류 본문을 만들 때 `Allow` 등 필요한 HTTP 의미를 유지한다. HEAD는 대응 상태/헤더를 반환하되 본문을 쓰지 않는다.
- JSON 오류 응답을 기본으로 하며 브라우저 `Accept: text/html`에도 HTML 오류 페이지를 노출하지 않는다. JSON과 호환되지 않는 Accept 처리도 테스트해 재귀 오류 없이 406 공통 오류로 정리한다.

| 상황 | 상태 / code | 책임과 경계 |
|---|---|---|
| 잘못된 JSON·타입·필수 입력·검증 실패 | 400 / `INVALID_REQUEST` | 공동구매 MVC, test-only API로 검증 |
| 공동구매의 없는 API | 404 / `NOT_FOUND` | `/api/**` 범위의 MVC 기본 오류 경로 포함 |
| 공동구매에 직접 보낸 비허용 메서드 | 405 / `METHOD_NOT_ALLOWED` | 허용 메서드를 `Allow`로 유지 |
| 응답 표현 협상 실패 | 406 / `NOT_ACCEPTABLE` | 오류 작성 중 협상 실패가 반복되지 않도록 처리 |
| 지원하지 않는 요청 Content-Type | 415 / `UNSUPPORTED_MEDIA_TYPE` | test-only API로 검증 |
| 예상하지 않은 앱 예외 | 500 / `INTERNAL_ERROR` | 안전한 고정 메시지, 원인 정보는 정제한 로그에만 기록 |
| Gateway 비허용 경로·메서드 | 404 / `NOT_FOUND` | 기존 route predicate 계약 유지. POST ping을 405로 바꾸지 않음 |
| Gateway가 발견한 대상 인스턴스 없음 | 503 / `SERVICE_UNAVAILABLE` | Gateway 자체 실패로 식별한 경우 |
| Gateway 대상 연결 실패 | 502 / `BAD_GATEWAY` | 기존 연결 실패 분류 유지 |
| Gateway 연결/응답 제한시간 초과 | 504 / `GATEWAY_TIMEOUT` | 기존 1초 연결·5초 응답 설정 유지 |

401/403·업무 충돌·멱등성 거절 코드는 회원·인증·업무 기능에서 추가한다. 현재 단계에서 인증 기능이나 가짜 도메인 API를 제품에 넣지 않는다.

### 처리 위치와 실패 경계

- 공동구매: `/api/**`의 MVC 예외 해석과 기본 오류 경로를 함께 다룬다. 구현에서는 오류 작성 중 재귀 협상을 피하도록 우선순위 `HandlerExceptionResolver`와 `ApiErrorController`를 사용한다(결과 문서에 선택 근거 기록). 핸들러 실행 전의 바인딩 오류·없는 경로·`/error` 디스패치도 계약에 포함한다. 요청 ID 필터는 이를 감싸며 예외를 중복 변환하지 않는다.
- Gateway: 기존 `RoutingFailureHandler`의 분류를 공통 작성 경로로 합치고 WebFlux 전역 예외 처리에서 라우트 미일치·대상 없음·그 밖의 자체 오류를 다룬다. 컨트롤러 advice만으로 Netty 라우팅 오류까지 처리했다고 보지 않는다.
- 하위 서비스가 반환한 HTTP 오류는 정상 수신한 응답이다. Gateway는 상태·본문·Content-Type을 보존하고 `X-Request-Id` 응답 헤더만 현재 요청 값으로 보장한다. 계약을 따르는 하위 서비스는 같은 ID를 본문에도 사용한다. 임의 테스트 서버의 503 본문도 강제로 변환하지 않는다.
- 응답이 이미 커밋되었거나 클라이언트가 연결을 끊으면 새 오류 본문을 덧쓰지 않는다. 기록 가능한 요청 ID·종료 종류를 남기며 상태를 성공으로 꾸미지 않는다. HTTP 서버가 요청을 파싱하기 전에 거절한 입력에는 애플리케이션 오류 계약을 보장하지 않는다.
- Config 설정 조회·Eureka REST/대시보드·Actuator의 정상 및 실패 응답은 해당 프레임워크의 계약을 유지한다. 공개 Gateway 포트에서 이 경로가 라우팅되지 않아 발생한 자체 404만 공통 오류 형식으로 만든다.

## 묶음 2. 요청 식별자·JSON 로그

### 요청 ID 전파

| 진입점 | 추천 동작 |
|---|---|
| 공개 Gateway 8000 | 외부 `X-Request-Id` 값을 모두 제거하고 매 요청 새 ID 하나 발급. 성공·자체 오류·미일치 요청에 동일 규칙 적용 |
| Gateway → 공동구매 | 요청 헤더 하나로 전달하고 exchange 속성/Reactor Context에도 저장. 하위 서비스 응답 ID가 달라도 외부 응답 헤더는 Gateway가 발급한 값으로 유지 |
| 공동구매 직접 접근/전달 요청 | 헤더가 정확히 하나이며 `[0-9a-f]{32}` 전체 일치면 사용. 없거나 빈 값·중복·잘못된 형식이면 새 ID 발급. 오류 응답으로 바꿔 요청을 거절하지 않음 |
| 응답 | 성공·오류 모두 `X-Request-Id` 한 개. 공통 오류 본문의 `requestId` 및 `instance`와 일치 |

ID는 UUID v4의 하이픈을 제거한 소문자 문자열을 사용한다. 외부 ID 덮어쓰기는 공개 경계의 충돌을 줄이기 위한 선택이다. 공동구매 직접 접근자가 유효한 ID를 지정할 수 있으므로 서비스의 ID 값으로 Gateway 경유 여부·신뢰·권한을 판정하지 않는다. 인증·내부 호출 신뢰는 007~008의 책임이다.

MVC는 요청 속성에 ID를 보관하고 MDC를 설정한 뒤 기존 값을 `finally`에서 복원한다. 오류 재디스패치에서 새 ID를 만들지 않으며 비동기 재디스패치도 같은 값을 사용한다. 범용 executor·Kafka·예약 작업으로 ThreadLocal이 자동 전달된다고 가정하지 않는다.

WebFlux는 exchange 속성을 오류 처리의 기준으로 쓰고 Reactor Context로 작업 흐름에 전달한다. 초기 구현은 요청 완료/오류 로그에 ID를 구조화 필드로 명시해 기록하며 event-loop 스레드에 MDC를 장시간 설정하지 않는다. MVC와 같은 필드 계약을 유지하되 모든 라이브러리 로그에 ID가 자동 삽입된다고 보장하지 않는다. 향후 필요하면 Micrometer Context Propagation을 별도로 검토한다. [Reactor 컨텍스트·ThreadLocal 경계](https://projectreactor.io/docs/core/release/reference/advanced-contextPropagation.html)

### 로그 형식·필드·민감정보

네 JVM 앱은 Boot 기본 구조화 로깅의 `logstash` 형식을 사용한다. `logging.structured.format.console`을 각 앱의 최초 로컬 YAML에 두어 Config 수신 전 실패도 같은 포맷으로 남긴다. 별도 인코더·파일 appender·수집 서버는 추가하지 않는다. 배너는 끄고 Java 앱 로그 이벤트를 한 줄씩 JSON으로 파싱할 수 있게 한다. JVM 자체 출력·Gradle·Docker Compose 표시를 앱 JSON 로그와 혼동하지 않는다. [Boot 4.0 구조화 로깅](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html#features.logging.structured)

| 종류 | 필드·기록 기준 |
|---|---|
| 모든 앱 로그 이벤트 | 기본 `@timestamp`, `level`, `logger_name`, `thread_name`, `message`에 `service`, `environment` 추가. 실행 환경은 `local`/`docker`이며 Config 백엔드 프로필 `native`와 구분 |
| Gateway·공동구매 API 요청 완료 | `event=http_request_completed`, `requestId`, `method`, `route`, `status`, `durationMs`, `termination`. 라우트 미일치는 `UNMATCHED`, 취소 시 상태 미확정은 null과 `cancelled`로 구분 |
| 오류 | 안전한 `code`, `exceptionType`과 정제한 원인 분류. 예상 밖 5xx는 요청 ID와 정제한 스택 프레임으로 ERROR 기록. 하위 서비스 5xx 수신을 Gateway 자체 예외로 기록하지 않음 |

요청 완료 로그는 앱별 요청당 한 번 기록한다. 정상·4xx 완료는 INFO, 자체 예상 밖 5xx는 별도 오류 로그를 남기되 동일 예외를 필터·advice·전역 처리에서 반복 출력하지 않는다. `durationMs`는 단조 시계로 계산하고 실제 경과에 대해 유한한 0 이상의 수여야 한다. `route`는 MVC 매핑 템플릿 또는 Gateway의 제한된 route ID이며 원본 경로·쿼리를 사용하지 않는다. 현재 HTTP 추적 계약은 두 API 모듈에 적용하며 Config/Eureka의 프로토콜 요청까지 접근 로그를 새로 만들지 않는다.

애플리케이션의 요청·오류 로거는 필드 허용 목록으로 작성한다. Cookie/Set-Cookie·Authorization·CSRF·멱등성 키·비밀번호·본문·쿼리·전체 URL/헤더·예외 `message`/cause 원문은 기록하지 않는다. 정제하지 않은 Throwable을 그대로 로깅해 입력값이나 SQL이 다시 노출되지 않게 한다. 기동 실패 등 라이브러리 로그도 가짜 secret을 사용한 검증으로 확인하고 노출이 발견되면 발생 지점의 설정·기록 방식을 수정한다. 일반 문자열 마스킹만으로 미노출 검증을 대체하지 않는다.

로그 회전은 애플리케이션 파일 출력을 추가하는 대신 Compose 네 JVM 서비스의 `json-file` 드라이버에 `max-size=10m`, `max-file=3`을 제안한다. 이는 컨테이너별 로컬 디스크 상한이며 일수 기반 보관·중앙 보존을 의미하지 않는다. PostgreSQL 로그 형식은 변경하지 않는다. 추후 업무·메시지 ID는 해당 단계에서 안전한 개별 로그 필드로 추가하고 메트릭 라벨에는 넣지 않는다.

## 묶음 3. 헬스·기본 지표

### 헬스의 역할

종합 `/actuator/health`의 기존 구성·503 매핑과 Compose healthcheck·Eureka 등록 health URL은 유지한다. 추가 그룹을 정의해 상태를 구분한다. `probes.enabled=true`로 비 Kubernetes 환경에서도 그룹을 활성화하고 그룹 멤버 존재 검증은 유지한다. 실제 contributor ID는 구현 시 등록된 빈으로 확인한다. 아래 `configurationRepository`는 현재 커스텀 indicator를 가리킨다.

| 앱 | liveness 포함 | readiness 포함 | 기존 종합 health 경계 |
|---|---|---|---|
| Config Server | `livenessState` | `readinessState`, `configurationRepository` | 설정 저장소/필수 설정 검사를 유지 |
| Eureka Server | `livenessState` | `readinessState` | 시작 후 Config 장애를 지속 health 의존성으로 만들지 않음 |
| API Gateway | `livenessState` | `readinessState` | Eureka·하위 서비스 유무를 앱 생존 실패로 만들지 않음 |
| 공동구매 | `livenessState` | `readinessState`, `db` | 실제 DataSource DB health와 기존 Eureka 연계 유지 |

`/actuator/health/liveness`와 `/actuator/health/readiness`를 추가하며 모두 `show-details=never`, `show-components=never`로 내부 주소·DB·저장소 경로를 숨긴다. 정상은 UP/200이다. DB 장애 중 공동구매의 liveness는 UP/200, readiness·종합 health는 DOWN/503이고 같은 프로세스에서 DB 복구 후 UP/200으로 돌아와야 한다. 잘못된 DB 인증·Flyway 오류의 기동 실패를 readiness만 낮추는 방식으로 숨기지 않는다.

readiness의 의존성 포함은 이 프로젝트의 추천 선택이다. liveness에는 외부 시스템을 넣지 않는다는 Boot 지침과 구분한다. 그룹을 추가해도 Docker Compose가 자동 재시작하거나 Eureka가 readiness 그룹으로 전환되지 않는다. Gateway 9000 관리 포트의 UP만으로 8000의 실제 요청 처리가 정상이라고 판정하지 않고 공개 ping도 별도 검사한다. 이번에는 8000에 `/livez`·`/readyz`를 추가하지 않는다. [Boot 4.0 health 그룹·probe의 역할](https://docs.spring.io/spring-boot/4.0/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)

### 지표와 노출 경계

| 앱 | HTTP 노출 허용 | 접근 위치 |
|---|---|---|
| Config Server | `health,metrics` | 기존 8888, Compose 호스트는 loopback |
| Eureka Server | `health,info,metrics` | 기존 8761, Compose 호스트는 loopback |
| 공동구매 | `health,info,metrics` | 기존 8080, Compose 호스트는 loopback |
| API Gateway | `health,info,metrics` | 기존 별도 9000. Docker 호스트 포트는 추가하지 않고 컨테이너 내부에서 검사, local은 loopback |

공개 8000은 기존 GET ping 경로만 전달한다. `/actuator/**`, `/api/group-purchases/actuator/**`, `/livez`, `/readyz`가 열리지 않아야 한다. `env`, `configprops`, `beans`, `loggers`, `heapdump`, `threaddump` 등은 각 관리 포트에서도 비노출 상태를 유지한다. 위 관리 접근은 현재 로컬 개발 구성 기준이며 운영 배포의 인증·네트워크 격리 완료를 뜻하지 않는다.

- 기본 인메모리 MeterRegistry와 `/actuator/metrics`로 진단한다. Prometheus registry·scrape endpoint·수집 컨테이너는 이번 범위에 넣지 않는다.
- 네 앱의 HTTP `http.server.requests`, JVM 메모리·GC·스레드, 프로세스/시스템 기본 지표와 공동구매의 `jdbc.connections.*`·`hikaricp.*` 연결 풀 지표를 확인한다. 요청·풀 사용 후 생성되는 meter는 자극 후 조회한다. [Boot 4.0 자동 계측·metrics 조회](https://docs.spring.io/spring-boot/4.0/reference/actuator/metrics.html)
- 공통 `service`·`environment`와 기본 제한된 method/status/outcome/uri 등만 사용한다. URI는 템플릿/고정 미일치 값으로 제한하고 원본 URL·쿼리·requestId·사용자/업무 ID·오류 메시지를 태그에 추가하지 않는다. Gateway 경로별 세부 metrics의 원본 path 태그도 켜지 않는다.
- 같은 ping을 순차 3회 요청한 뒤 해당 method/status/uri의 COUNT가 증가하는지 확인한다. health·metrics 조회 자체의 요청과 구분하며 소요 시간은 유한한 값인지 검증한다. 앱별 카운터를 합쳐 한 요청의 중복 처리라고 해석하지 않는다.
- 5xx 요청을 자극해 상태 태그로 구분되는지 확인한다. 오류를 잡아 공통 응답으로 바꿔도 성공 200으로 계측하지 않아야 한다. 새로운 수동 HTTP 타이머를 추가해 자동 지표를 이중 기록하지 않는다.
- 인메모리 값은 프로세스 재시작 시 초기화된다. 현재 단계의 meter 노출로 p95/p99·오류율·성능 목표를 달성했다고 표시하지 않는다. 수집·집계·히스토그램·업무 지표는 후속 관측/부하 계획에서 정한다.

<a id="guided-coding-units"></a>

## 핵심 코드 직접 작성 순서

학습 방식은 사용자와 합의했다. 아래 대상은 현재 006 추천 설계를 기준으로 AI가 선정한 순서이며 기술 선택이 변경되면 같은 목적의 실제 구현 위치에 맞춘다. 2026-11-04 구현 요청으로 이 순서를 실행한다. 첫 인계는 ID 선택 메서드부터 시작했으며, 이후 사용자 변경 요청으로 남은 여섯 Step을 동시에 준비한다. 진행은 [공통 직접 작성 기준](../README.md#guided-coding)을 따른다.

| 순서 | 직접 작성할 핵심 구간 | 확인할 이유·동작 |
|---|---|---|
| LEARN-01 | 공동구매 MVC의 요청 ID 선택·필터 처리. ID 검증/발급과 MDC 설정/복원을 짧은 구간으로 나눔 | 헤더/요청 속성/응답의 연결, 오류·재디스패치에서도 같은 ID, 다음 요청에 값이 남지 않는 이유 |
| LEARN-02 | 공동구매 공통 오류의 상태/코드 매핑과 안전한 응답 생성 | 예외 원문 대신 고정 필드를 사용하는 이유, HTTP 상태·헤더·본문 ID의 일치 |
| LEARN-03 | Gateway WebFlux의 요청 ID 전달·컨텍스트와 요청 완료 처리 | 실행 시점·스레드가 달라지는 상황의 ID 유지, 한 요청에 한 완료 로그, 동시 요청 격리 |
| LEARN-04 | Gateway 자체 오류 분류·공통 응답 작성 | 대상 없음/연결 실패/timeout 구분, 하위 서비스의 HTTP 오류 전달 및 이미 커밋한 응답 경계 |

위 행은 학습 주제의 순서다. 2026-11-04 사용자 요청에 따라 기준 구현 검증 후 구현별 여섯 Step의 참고 주석과 입력 위치를 동시에 준비하고 사용자가 한 번에 작성한다. 이미 작성한 코드는 보존한다. STEP-01은 MVC 필터, 02~03은 MVC 오류 처리/본문, 04는 WebFlux 필터, 05~06은 Gateway 오류 분류/작성이다. 실제 위치와 진행 상태는 결과 문서에서 관리한다.

의존성·YAML·Compose·로그 포맷·health/metrics 설정·검사 스크립트·테스트 기반은 AI가 구현하고 주요 설정의 역할을 설명한다. 사용자가 작성한 핵심 로직의 수정은 리뷰와 수정 방향 안내를 기본으로 한다. 학습 구간이 비어 있는 동안은 **구현 중 / 사용자 작성 대기**이며, 주석 전환 전 기준 구현의 성공을 현재 소스의 완료로 기록하지 않는다.

## 구현 순서와 변경 예정 파일

아래는 향후 구현 요청 시 기준 구현을 준비하는 순서다. 이후 위 직접 작성 순서에 따라 사용자 재작성과 재검증을 마친다. 세부 클래스명은 구현 중 정리할 수 있으며 범위·계약·완료 기준의 변경은 같은 계획에 반영한다.

| 순서 | 작업·주요 파일 | 완료 경계 |
|---|---|---|
| 1 | Gateway/공동구매 요청 ID 필터·오류 작성/처리. 기존 `RoutingFailureHandler.java`와 `RoutingIntegrationTests.java`, 공동구매의 `api/`·`web/` 및 테스트 | 실제 포트에서 자체 오류·하위 응답 전달·ID 전파 검증. 실패 유도 API는 test source에만 등록 |
| 2 | 네 앱의 `src/main/resources/application.yml`, `config-repo/application.yml`·서비스별 YAML, `compose.yml`, 모듈 내부 요청 로거 | Config 수신 전부터 JSON, API 완료 로그·동시 요청 격리·민감정보 미노출. 필요한 의존성이 있다면 기존 BOM 관리 범위 사용 |
| 3 | health 그룹·metrics 태그 설정, 기존 health 및 Gateway 검사 보완, 새 `scripts/check-api-observability.py` | 다섯 서비스 healthy, 기존 포트/라우트 경계와 지표 실제 변화·DB 복구 확인 |
| 4 | `.github/workflows/ci.yml`, `README.md`, `Docs/README.md`, 구현 목록·인계·같은 번호 결과 문서 | 기존 두 CI job에 새 계약 검증 누적. 직접 재현·학습 문서 연결 |

제품 bootJar/Docker 이미지에는 테스트 fixture·강제 실패 컨트롤러·검사 전용 secret을 넣지 않는다. 공통 모듈·업무 테이블·Flyway migration·기존 런타임 관찰 API는 이번에 추가하지 않는다.

## 검증 계획·완료 기준

이 절은 완료 기준이다. 실제 실행 여부·근거는 결과 문서에서 구분한다. 구현에서는 영향이 있는 검증을 수행하고, 005의 이미 유효한 장시간 장애·전체 down/up 결과는 관련 변경·실패가 없다면 반복하지 않는다.

| 검증 | 방법과 기대 결과 |
|---|---|
| 오류 계약 | 실제 HTTP 통합 테스트로 MVC의 400/404/405/406/415/500, Gateway의 404/502/503/504·자체 500을 재현. Content-Type·필수 필드·상태·안전한 detail·헤더/본문 ID·HEAD 의미 확인 |
| 처리 경계 | handler 전/기본 오류 디스패치·Gateway 라우트 미일치·연결/응답 timeout·이미 커밋한 응답 확인. 하위 503 상태/본문은 기존처럼 전달 |
| 요청 ID | Gateway 외부 값 덮어쓰기, 하위 수신 값과 두 앱 로그·응답의 일치. 직접 공동구매의 유효/없음/잘못된/중복 헤더, 재디스패치·예외·취소 확인 |
| 동시성 | 겹치는 요청을 지연 fixture와 WebFlux 스케줄러 전환으로 섞어 요청별 ID/완료 로그가 교차 오염되지 않는지 확인. 다음 요청·요청 외 로그에 이전 MDC가 남지 않아야 함 |
| 로그·민감정보 | 앱의 원시 stdout을 줄별 JSON 파싱. 가짜 비밀번호·헤더/쿠키/토큰·본문·쿼리·예외 메시지에 서로 다른 sentinel을 넣고 응답·앱 로그에서 검출되지 않음을 확인. Config 수신/DB 기동 실패도 포함 |
| health | 실제 Compose와 격리 테스트에서 종합/probes 정상·세부정보 비노출. 공동구매 DB stop/start 전후 같은 PID의 liveness 200, readiness/root 503→200. 새 그룹의 DB 검사 실제 동작 확인 |
| 관측값 | API 요청 후 meter 목록·HTTP COUNT/시간·JVM·DB 풀 값 확인. 서로 다른 원본 미일치 경로·요청 ID를 보내도 태그 값이 요청 수만큼 늘지 않아야 함 |
| 노출/회귀 | 공개 8000의 비허용 경로·메서드 404, 관리 포트의 비허용 Actuator 404, Config 수신·Eureka 등록·ping·기존 DB/Flyway·secret/권한 경계 유지 |
| 기동/산출물/CI | 영향받은 자동 테스트와 전체 build, 네 앱 이미지·다섯 서비스 Compose, Windows 제품 JAR의 두 API 모듈 local 연결·동일 계약을 확인. CI job에는 계약 스크립트 연결, 원격 실행/PR은 별도 요청 범위 |

기존 일반 DB 볼륨·secret·부모 환경을 보존한다. 기동 실패·민감정보·강제 오류는 격리 fixture로 재현하고 실제 비밀번호를 출력·argv에 넣지 않는다. DB 복구 검사는 상태 전환을 실제 관찰하고 완료를 제한시간으로 판정한다. 005의 관측 상한을 재사용하되 새로 검증한 그룹/설정만 기록하며 timeout을 통과로 바꾸지 않는다. 종료 시 임시 프로세스·fixture·환경을 정리하고 기존 Compose 구성을 복구한다.

완료 표시는 사용자 직접 작성 구간을 모두 마치고 재작성한 실행 코드로 위 계약·회귀와 필요한 실행 검증을 충족한 뒤 한다. 기준 구현 검증과 사용자 작성 후 검증·미검증 사항을 구분한다. 결과에는 다음을 포함한다.

1. `Docs/implementation/006-api-observability-baseline.md`: 실제 변경·선택 차이·명령·검증 근거·미검증·제약.
2. 사용자 직접 테스트: 준비 사항·작업 디렉터리·복사 가능한 PowerShell 명령/요청, ping·404 오류·요청 ID와 로그 검색·관리 포트 지표·DB 장애/복구의 기대 결과·종료/복구. 제품에 없는 테스트 API를 사용자 경로로 안내하지 않는다.
3. `Docs/explanations/`의 관련 학습 문서: 오류 처리·요청 추적/JSON 로그·Actuator의 기본 개요, 현재 역할, 실제 적용 예시 및 코드/결과 연결. 기존 Git 제외 정책 유지.
4. 구현 목록·인계 갱신. 원격 작업 요청이 있으면 착수 기준의 최신 상태를 확인하고 PR·head·Checks와 조회 당시 상태를 기록한다. push 후 CI 완료 대기·반복 조회는 하지 않는다.

## 합의와 다음 단계

2026-11-04 사용자가 **① 오류 형식·처리 경계, ② ID 발급·로그 정책, ③ health·metrics 노출/완료 기준** 세 추천안을 모두 채택하고 기준 구현·검증 및 따라치기를 진행했다. 이후 여섯 Step의 사용자 작성·위임 수정과 최종 통합 검증을 마쳤다. 최초 요청에서 제외했던 커밋·push·PR은 이번 사용자 요청에 따라 진행한다. 로컬 완료와 원격 CI/병합 상태를 구분한다.

확정한 계획의 요청 범위까지 진행한다. 006 완료 다음은 로드맵의 007 회원·세션 인증이며, 이번 계획에서 후속 인증·CSRF·운영 관측 환경을 구현하지 않는다.

## 공식 자료 확인 범위

2026-11-04에 Spring Framework의 MVC/WebFlux 오류 처리, Boot **4.0 계열** 로깅·Actuator·메트릭과 [4.0.9 설정 속성 목록](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/index.html)을 확인했다. 버전 없는 Boot 문서는 4.1 계열로 연결되어 근거를 4.0 문서로 한정했다. 프로젝트 버전 업그레이드는 제안하지 않는다.

- MVC와 WebFlux 모두 RFC 9457과 확장 필드를 지원한다. 실제 오류 진입점·기본 instance 자동 설정·협상은 스택별 처리와 검증이 필요하다. [MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html), [WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux/ann-rest-exceptions.html)
- 이 문서의 코드/메시지·ID 신뢰 경계·로그 필드/회전 크기·readiness 구성·관리 노출은 공식 기본값과 구분되는 **프로젝트 추천안**이다. 문서 확인은 현재 프로젝트에서의 실행 호환성 검증을 대신하지 않는다.
