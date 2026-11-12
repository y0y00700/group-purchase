# 008. Gateway 인증·내부 호출 신뢰

작성일 2026-11-09 KST

상태 **로컬 구현·검증 완료**. 여섯 직접 작성 구간의 리뷰와 재검증 근거는 [008 결과](../008-gateway-authentication.md)에 있다.

[구현 목록](../README.md) · [로드맵](roadmap.md) · [인증·권한 설계](../../design/architecture.md#security) · [007 내부 확인 계약](007-member-session-authentication.md#내부-인증-확인과-008-인계-계약)

<a id="execution-summary"></a>

## 실행 요약

- 목표는 Gateway를 통해 로그인하고, 공동구매 서비스가 검증된 Gateway 호출에서만 회원 신원을 사용하게 하는 것이다.
- 기존 Gateway는 `GET /api/group-purchases/ping`만 전달한다. 공개 ping과 관리 포트 분리를 유지한다.
- 회원 공개 HTTP 경로 네 개를 정확한 메서드·경로로 `lb://member`에 연결한다. 인증과 CSRF는 기존 회원 서비스가 처리한다.
- 보호된 공동구매 요청은 Gateway가 `POST /internal/auth/check`로 세션·현재 역할·원본 변경 요청의 CSRF를 확인한다.
- Eureka에 등록된 `member`를 찾는 비동기 전용 클라이언트를 쓴다. 확인 결과는 요청마다 조회한다.
- 공동구매 보호 확인용 `GET/HEAD/POST /api/group-purchases/auth-probe`를 제안한다. 기존 `ping`은 계속 익명 접근을 허용한다.
- 외부 신원·내부 호출자 헤더는 먼저 제거한다. 확인에 성공한 경우에만 검증된 회원 ID·역할과 공동구매 전용 호출 자격 증명을 추가한다.
- 공동구매는 자기 전용 자격 증명을 검증한 뒤 신원을 받아들인다. loopback 직접 호출에도 같은 검증이 적용된다.
- `member_internal_credential`은 회원과 Gateway만 사용한다. 공동구매와 Gateway에는 별개의 `group_purchase_internal_credential`을 주입한다.
- 인증 확인의 초기 연결 제한은 1초, 응답 검증까지 포함한 전체 제한은 2초를 제안한다. 호출은 한 번만 시도한다.
- 사용자 인증 실패와 CSRF 실패는 각각 401·403이다. 인증 의존 장애는 502·503·504로 구분하고 공동구매 요청을 보내지 않는다.
- 회원 공개 응답의 `GP_SESSION` 발급·회전·만료는 원래 속성과 각 `Set-Cookie` 필드를 보존한다. 보호 요청에서 발생한 401에는 쿠키 변경을 붙이지 않는다.
- 보호 경로는 원본 요청 본문을 인증 서비스로 복사하지 않는다. 검증에 성공하면 원래 메서드·본문을 공동구매에 전달한다.
- 기존 개발용 8080·8081 loopback 진단을 유지한다. HTTPS·운영 내부 네트워크 구성과 상품·게시·소유권은 후속 범위다.
- 기준 구현을 검증한 뒤 6개 작성 Step을 동시에 제공한다. 007에서 완료한 사용자 작성 코드는 보존한다.
- 완료 기준은 실제 Gateway·회원·공동구매·PostgreSQL 왕복, 위조·장애 시 전달 0회, 회복 후 성공, Compose·Windows JAR·직접 테스트 기록이다.
- 구현 착수 시 최신 PR·main·head CI를 확인하고 007 변경이 포함된 기준에서 브랜치를 정한다.

## 합의한 설계 선택

| 항목 | 추천안과 이유 | 대안과 비용 |
|---|---|---|
| 보호 경로 검증 | 기존 공개 ping을 유지하고 부작용 없는 `auth-probe`를 추가한다. 실제 공동구매 서비스에서 읽기와 변경 메서드의 보안 경계를 모두 재현할 수 있다. | 테스트 fixture만 쓰면 제품의 수신부와 Compose secret을 함께 확인하기 어렵다. ping 자체를 보호하면 004·006 상태 확인과 회원 장애의 영향 범위가 달라진다. |
| 회원 인증의 위치 | 회원 공개 API는 회원에 바로 전달하고, 공동구매 보호 경로만 내부 확인을 먼저 호출한다. 회원 DB와 세션 정책의 소유권이 유지되고 `me`가 인증 조회를 두 번 하지 않는다. | Gateway의 로컬 세션 조회나 결과 캐시는 DB 경계를 넘거나 로그아웃·역할 변경 반영을 늦춘다. |
| 서비스 호출 신뢰 | 연결별 전용 secret 두 개와 공동구매 수신부 검증을 사용한다. 현재 Compose 주입 방식으로 직접 접근을 검증할 수 있다. | 공용 secret은 수신 서비스 간 자격 증명 재사용을 허용한다. mTLS는 워크로드 신원에 적합하지만 인증서 발급·회전과 TLS 구성이 별도로 필요하다. |
| 보호 요청의 만료 쿠키 | 401에는 쿠키를 건드리지 않고 CSRF 재발급과 로그인으로 복구한다. 이전 요청의 늦은 401이 방금 로그인해서 받은 쿠키를 지우는 경합을 피한다. | 401에서 자동 만료시키면 오래된 쿠키를 즉시 정리할 수 있으나 요청 간 쿠키 교체 순서를 서버에서 보장하기 어렵다. |

## 공개 라우트와 보호 확인 경로

| Gateway 8000의 요청 | 목적지와 책임 | 정상 응답 |
|---|---|---|
| `GET /api/group-purchases/ping` | `lb://group-purchase`. 기존 공개 동작 유지 | 200 `pong` |
| `GET/HEAD /api/auth/csrf` | `lb://member`. 익명 세션과 CSRF 발급 | GET 200 `{token,headerName}`, HEAD 본문 없음 |
| `POST /api/auth/login` | `lb://member`. JSON 로그인과 CSRF, 세션 ID 회전 | 200 `{memberId,roles}` |
| `POST /api/auth/logout` | `lb://member`. CSRF 검증, 현재 세션 삭제와 쿠키 만료 | 204 |
| `GET/HEAD /api/members/me` | `lb://member`. 회원 서비스가 직접 현재 세션과 역할 확인 | GET 200 `{memberId,roles}`, HEAD 본문 없음 |
| `GET/HEAD/POST /api/group-purchases/auth-probe` | 인증 확인이 성공한 후 `lb://group-purchase`. 공동구매 서비스가 Gateway 호출과 신원을 검증 | GET·POST 200 `{memberId,roles}`, HEAD 본문 없음 |

- 공개 목록은 경로와 메서드를 함께 제한한다. 요청의 원래 경로를 넘기며 경로 재작성이나 서비스 ID 자동 라우팅을 사용하지 않는다. Gateway의 미일치 응답은 404다. 007의 회원 서비스 직접 호출 계약은 유지한다.
- 끝 슬래시·대소문자·인코딩된 구분자·세미콜론 경로 매개변수·중복 슬래시·점 세그먼트가 허용 경로의 별칭이 되지 않게 원시 경로도 검사한다. 쿼리는 라우트 선택과 인증 입력에 쓰지 않는다. 구문 오류가 HTTP 파서에서 먼저 400이 되더라도 대상 서비스 호출은 0회여야 한다.
- `/internal/**`, `/actuator/**`, `/member/**`, `/group-purchase/**`와 미등록 경로는 공개 라우트에 없다. Gateway 관리 9000은 기존대로 분리한다. 기존 ping에 대한 미지원 메서드는 404다.
- `auth-probe`는 인증된 회원 본인의 신원 전달을 검사하는 개발용 API다. POST도 데이터를 변경하지 않으며 응답은 검증한 신원만 사용한다. 009의 실제 보호 업무 API가 읽기·변경 메서드 검사를 모두 대체할 수 있게 되면 이 경로를 제거한다. 역할이 비어 있으면 인증을 완료하지 않으며, 판매자·소유자 등 업무 권한은 실제 업무 API에서 정의한다.

## 요청별 신뢰 경계

1. **Gateway 입구**에서 006 요청 ID를 생성하고, 헤더 이름의 대소문자와 무관하게 `X-Authenticated-*`, `X-Internal-*`와 메서드 덮어쓰기 헤더를 제거한다. `Connection`이 지정한 hop-by-hop 필드를 해석해 제거한 뒤 Gateway 요청 ID와 필요한 내부 헤더를 설정한다. `Connection`으로 `Cookie` 또는 `X-CSRF-TOKEN`을 지정하면 그 값을 인증에 사용하지 않는다. 원본 메서드는 HTTP 요청의 메서드다. [Gateway 헤더 필터](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/httpheadersfilters.html) · [HTTP Connection 규칙](https://www.rfc-editor.org/rfc/rfc9110.html#section-7.6.1)
2. **회원 공개 라우트**는 원래 메서드·경로·본문과 세션/CSRF에 필요한 헤더를 전달한다. 회원이 이미 적용하는 CSRF·로그인·현재 역할·중복 입력 검증을 그대로 거친다. 이 라우트에는 내부 호출 자격 증명을 추가하지 않는다.
3. **공동구매 보호 라우트**는 원본 `GP_SESSION` 쿠키만 선택한다. 모든 Cookie 필드에서 중복을 확인하고, 중복 또는 문법상 잘못된 세션 값은 400, 쿠키 없음은 401로 처리한다. 정상 쿠키 값은 디코딩하거나 세션 ID로 변환하지 않는다. 내부 확인의 인증 대상이 있는 변경 요청은 CSRF 토큰이 빠지거나 중복돼도 007이 세션과 현재 회원을 먼저 판단한 뒤 403을 결정하도록 전달한다. GET/HEAD 확인에는 CSRF 헤더가 필요 없다.
4. **Gateway → 회원**은 Eureka에 등록된 `member` 인스턴스를 `ReactiveLoadBalancer`로 한 번 선택하고 그 주소의 `/internal/auth/check`에 `POST`한다. 인스턴스 부재는 HTTP 호출 전에 처리하므로 회원이 반환한 503과 구분할 수 있다. 새 요청에는 `Content-Type: application/json`, Gateway 요청 ID, `X-Internal-Caller: api-gateway`, 회원 전용 `X-Internal-Credential`, 선택한 `Cookie: GP_SESSION=…`, 필요한 `X-CSRF-TOKEN`을 설정한다. 본문은 실제 메서드의 `{ "method": "POST" }` 같은 JSON 한 필드다. 원본 URL·쿼리·본문·다른 쿠키·클라이언트 내부 헤더는 복사하지 않는다. [LoadBalancer 인스턴스 선택 코드](https://github.com/spring-cloud/spring-cloud-commons/blob/v5.0.4/spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/support/LoadBalancerClientFactory.java#L72-L75) · [기본 WebClient 필터의 빈 인스턴스 처리](https://github.com/spring-cloud/spring-cloud-commons/blob/v5.0.4/spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/reactive/ReactorLoadBalancerExchangeFilterFunction.java#L82-L94)
5. **회원 → Gateway**의 인증 성공은 200 JSON의 유효한 `memberId`와 비어 있지 않은 `roles`로 확인한다. UUID는 표준 문자열 형태, 역할은 `BUYER`, `SELLER`, `OPERATOR` 중 중복 없는 목록으로 검증한다. 성공 본문이 비었거나 타입·형식·역할이 틀리면 실패다. 내부 응답 본문 한도를 8 KiB로 두고 중복 JSON 필드와 추가 JSON 값도 거절한다. 확인 응답 본문이나 쿠키·redirect를 외부 응답에 직접 복사하지 않는다. [WebClient 응답 버퍼 한도](https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html)
6. **Gateway → 공동구매**에는 `X-Internal-Caller: api-gateway`, 공동구매용 `X-Internal-Credential`, `X-Authenticated-Member-Id` 한 개와 `X-Authenticated-Roles` 한 개를 새로 설정한다. 역할은 검증된 값을 쉼표로 연결한 정규 순서 문자열이다. 원래 메서드·경로·쿼리·본문은 유지하며, 공동구매 라우트에 브라우저 `Cookie`·`X-CSRF-TOKEN`·`Authorization`은 보내지 않는다. 회원용 secret은 회원 내부 확인에만 사용한다. 회원 확인 전에 공동구매 연결을 시작하거나 원본 본문을 읽어서 버퍼링하지 않는다.
7. **공동구매 수신부**는 매핑된 보호 핸들러가 실행되기 전에 호출자와 자격 증명 헤더가 각각 한 개인지 확인하고, 이름과 전용 secret을 검증한다. secret 비교에는 기존 회원 쪽과 같은 안전한 바이트 비교를 사용한다. 누락·오답·중복은 403 `INTERNAL_CALLER_DENIED`다. 성공한 호출자의 신원 헤더가 누락·중복·형식 오류면 400 `INVALID_REQUEST`다. 확인한 회원 ID·역할을 요청 범위 값으로 핸들러에 전달한다. 송신 IP·Host·Forwarded나 쿠키를 호출 자격 증명으로 쓰지 않는다. 수신부의 보호 지정은 Gateway 라우트 설정과 별도로 검증한다.

인증 확인 결과는 현재 요청에만 적용한다. 같은 세션의 다음 요청에는 회원 역할·상태 변경이 반영된다. 이미 인증을 통과해 처리 중인 요청의 강제 철회는 제공 범위 밖이다.

## 쿠키와 장애 응답

- `GP_SESSION`은 회원 서비스가 발급하고 만료한다. Gateway가 회원 공개 경로의 각 `Set-Cookie` 필드를 개별 전달하고 `Path=/`, `HttpOnly`, `SameSite=Lax`, `Domain` 미지정과 프로필별 `Secure`를 보존한다. 콤마로 합치거나 Domain·Path를 재작성하지 않는다. 인증 확인 응답이나 공동구매 응답은 회원 세션 쿠키를 발급·변경할 수 없다. 초기 브라우저 흐름은 Gateway와 같은 출처를 전제한다. [HTTP 쿠키 필드](https://www.rfc-editor.org/rfc/rfc6265.html#section-3)
- 보호 경로 401은 쿠키를 지우지 않는다. 사용자 흐름은 `GET /api/auth/csrf` → 로그인 → 새 세션에서 필요한 CSRF 재발급이다. Gateway에서 발급·로그인 회전·로그아웃 만료를 실제 쿠키 jar로 확인한다.
- 회원 공개 라우트의 실제 회원 응답은 007 상태·오류 코드·본문을 보존한다. 연결 자체가 실패하면 004의 Gateway 전송 계약을 적용한다. 회원 API와 보호 확인 경로의 응답에는 성공·실패 모두 `Cache-Control: no-store`를 적용한다.
- 보호 라우트 내부 확인은 WebFlux `WebClient`로 비동기 실행한다. 최초 연결 1초, 인스턴스 선택·연결·본문 검증을 합친 전체 2초를 초기값으로 둔다. 전체 제한에 도달하면 확인을 취소하고 504를 반환한다. Gateway 라우트·LoadBalancer·전용 HTTP 클라이언트에서 자동 재시도와 redirect를 적용하지 않도록 하고, 단일 시도를 수신 기록으로 확인한다. 내부 확인 성공 이후 공동구매 라우팅에는 기존 004 전송 제한이 별도로 적용된다. [WebClient 제한시간 설정](https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html)

| 보호 요청에서 관찰한 결과 | Gateway 외부 응답 |
|---|---|
| 중복·잘못된 `GP_SESSION` 입력 | 400 `INVALID_REQUEST` |
| 세션 쿠키 없음 또는 회원의 401 `UNAUTHENTICATED` | 401 `UNAUTHENTICATED` |
| 회원의 403 `CSRF_INVALID` | 403 `CSRF_INVALID` |
| 선택할 회원 인스턴스 없음 또는 회원의 503 `AUTHENTICATION_UNAVAILABLE` | 503 `AUTHENTICATION_UNAVAILABLE` |
| 캐시된 주소 연결 실패, 회원의 내부 호출 거절, 잘못된 성공/오류 응답, 그 밖의 예상하지 못한 업스트림 응답 | 502 `BAD_GATEWAY` |
| 연결 또는 전체 확인 제한시간 초과 | 504 `GATEWAY_TIMEOUT` |

실패 분류는 내부 HTTP 상태와 예상한 오류 코드를 함께 확인한다. 예를 들어 회원의 403 `INTERNAL_CALLER_DENIED`는 호출 자격 증명 설정 문제이므로 사용자 CSRF 오류와 구분한다. 응답은 Gateway가 006 `application/problem+json` 규격으로 작성하고 요청 ID를 유지한다. HEAD 실패에는 본문이 없다. 알려진 인증 실패·장애·응답 형식 오류에서는 공동구매 요청을 보내지 않는다. DB가 멈추면 회원의 명시적 503을 받거나 Gateway의 2초 제한이 먼저 도달할 수 있으므로 두 경로를 각각 재현한다.

로그는 기존 완료 이벤트와 요청 ID를 재사용하고 내부 확인 결과를 기록할 때는 제한된 결과 분류와 지연 시간만 사용한다. 요청 본문·로그인 ID·회원 신원 헤더·쿠키·CSRF·양쪽 secret·내부 응답 원문·예외 메시지는 외부 오류와 로그·지표 라벨에 넣지 않는다. 인증 의존 장애와 Gateway 자체 프로세스 헬스는 별도로 판정한다.

## 설정과 변경 예정 위치

| 위치 | 구현 범위 |
|---|---|
| `config-repo/api-gateway*.yml`, `infrastructure/api-gateway/src/main/` | 명시 라우트, 입구 헤더 정리, 보호 라우트 필터, 회원 내부 호출·응답 검증, 기존 오류/요청 ID 연동. 5.0.x의 라우트 필터 순서와 실제 해결 버전의 동작을 확인한다. |
| `services/group-purchase-service/src/main/` | 웹 경계에서 보호 핸들러의 호출자·신원 확인, 요청 범위 신원 값과 `auth-probe`. 기존 ping·Actuator·DB 연결을 유지한다. |
| `compose.yml`, 모듈별 `application-docker.yml`, `scripts/prepare-ci-secrets.py` | 기존 `member_internal_credential`은 회원·Gateway에만, 새 `group_purchase_internal_credential`은 공동구매·Gateway에만 마운트한다. 회원·공동구매의 대상 키는 각자 `app.auth.internal-credential`, Gateway의 대상 키는 각각 `app.auth.member-internal-credential`, `app.auth.group-purchase-internal-credential`이다. secret 준비는 기존 파일을 보존한다. |
| Gateway·공동구매 테스트, `scripts/check-member-auth.py`, `scripts/check-gateway.py`, 필요한 보호 확인 스크립트, `.github/workflows/ci.yml` | 제어 가능한 HTTP fixture의 수신 기록과 실제 Compose를 구분해 검증한다. 007 검사의 Gateway `me` 기대값을 익명 401·유효 세션 200으로 확장하고 내부 경로 404를 유지한다. |
| `Docs/implementation/008-gateway-authentication.md`, 실행 안내·학습·인계 | 실제 변경과 검증 근거, 직접 테스트, 교체·복구 절차를 구현 시 기록한다. |

- 일반 라우트·타임아웃은 중앙 YAML, secret은 모듈 로컬에서만 주입한다. Docker에서는 기존 `configtree` 방식으로 대상 파일만 읽는다. Windows JAR는 프로필별 환경변수나 보호된 파일을 자식 프로세스에 주입한다. 두 전용 값은 각각 무작위 32바이트 이상으로 생성하고 누락·빈 값·허용하지 않는 형식은 기동 실패로 처리한다. Gateway에서 두 자격 증명이 같은 값인 경우도 기동을 거절한다. 관련 테스트·기존 JAR 실행 도구에는 격리 값을 공급한다. [Spring Boot 외부 설정](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html)
- Compose의 8080·8081 loopback 진단 바인딩과 등록 주소는 유지한다. Gateway 8000의 내부/관리 경로 비노출과, 8080으로 직접 요청해도 보호 핸들러가 거절하는 경계를 각각 확인한다. Gateway는 회원/공동구매 기동에 새 `depends_on`을 추가하지 않고 발견과 장애 회복을 실제 요청으로 검증한다.
- 교체는 해당 연결의 호출 중단 → 보호된 파일 교체 → 두 프로세스 재생성 → 이전 값 거절·새 값 성공 확인 순서다. 일반 DB 볼륨과 기존 secret을 보존하고 교체 실험은 격리 값으로 진행한다. HTTPS 운영 프로필·내부 전송 보호·워크로드 격리와 무중단 회전은 별도 운영 설계에서 구체화한다. 공유 secret을 보유한 워크로드는 해당 연결에서 Gateway로 행동할 수 있으므로 마운트 범위를 제한한다.

## 핵심 코드 직접 작성

| Step | 예정 구간 | 학습과 검사 |
|---|---|---|
| 01 | Gateway의 외부 헤더 정리 | 대소문자·중복·hop-by-hop을 포함한 위조 헤더가 프록시 경계를 넘지 않음 |
| 02 | 회원 내부 확인 요청 조립 | 실제 메서드와 단일 세션 값·CSRF, 고정 서비스/경로, 원본 본문 미복사 |
| 03 | 내부 인증 응답 검증·실패 분류 | 유효한 200만 허용, 401/403과 502/503/504 분리 |
| 04 | 보호 라우트의 비동기 연결과 신원 주입 | 성공 후에만 전달, 검증된 헤더와 수신 서비스용 secret 사용 |
| 05 | 공동구매 수신부의 Gateway 호출자 검사 | 직접 접근·중복·오답·다른 서비스 secret 거절 |
| 06 | 검증한 신원을 요청 범위 값으로 구성 | UUID·역할 검증, 요청별 신원 격리, 핸들러의 헤더 직접 신뢰 방지 |

실제 메서드 위치는 기준 구현과 검증 후 정한다. 여섯 구간을 같은 파일의 참고 주석과 입력 위치로 한꺼번에 준비한다. 사용자가 작성하면 한 묶음으로 리뷰·재검증하고 임시 표식을 정리한다.

## 검증과 완료 기준

| 검증 | 확인할 결과 |
|---|---|
| 공개 경계·회귀 | 기존 ping 200, 허용 목록 외·유사 경로·미지원 메서드·내부/관리 경로 전달 0회. 회원의 공개 HTTP 응답과 HEAD 동작 유지. |
| 실제 로그인·세션 | Gateway의 단일 쿠키 jar로 CSRF 발급 → 로그인 회전 → `me` → 보호 GET/POST → 로그아웃 쿠키 만료 → 401. 회전 전/만료 쿠키·비활성 회원 거절, 같은 세션에서 역할 변경 반영. 늦은 보호 401에도 쿠키 변경 헤더 없음. |
| 원본 요청·CSRF | 보호 POST의 올바른 토큰만 전달. 누락·오답·중복·쿼리/본문 대체·메서드 덮어쓰기는 통과하지 않음. 실제 회원 확인에는 원본 본문·다른 쿠키가 없고, 승인 시 공동구매 fixture에서 원본 메서드·쿼리·본문 확인. |
| 위조·직접 접근 | 대소문자/중복/Connection 지정 헤더를 포함해 외부 입력이 신원으로 채택되지 않음. 직접 8080의 누락·오답·회원용 secret은 403, 올바른 공동구매용 호출자와 잘못된 신원은 400. 거절 시 보호 핸들러 미실행. |
| 장애·복구 | 빈 발견 목록, 오래된 주소, 내부 호출자 거절, 잘못된 성공/오류 응답, 명시적 DB 장애 503, 지연·끊긴 응답을 재현한다. 회원 확인은 최대 한 번 시도하고 실패 시 공동구매 호출 0회. 회원/DB 복구 및 발견 캐시 수렴 뒤 동일 Gateway의 다음 요청 성공. |
| 관측·실행 | 상태·요청 ID·공통 오류·no-store·민감값 비노출, 기존 헬스 분리와 001~007 회귀. 전체 build, 다섯 앱 이미지·여섯 서비스 Compose, Windows 제품 JAR 연동을 확인한다. CI는 실제로 확인한 head와 결과만 기록한다. |
| 직접 재현 | 결과 문서에 준비·작업 위치·복사 가능한 요청·정상/실패 기대값·정리/복구를 제공한다. 격리 회원/세션과 fixture만 정리하고 일반 데이터와 secret을 보존한다. |

실제 변경과 검증 근거는 [008 결과](../008-gateway-authentication.md)에서 관리한다.
