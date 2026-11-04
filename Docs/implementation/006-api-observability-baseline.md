# 006 API·로그·헬스 구현 기록

갱신일: 2026-11-04 KST. **006 사용자 작성·로컬 최종 검증 완료**. [확정 계획](plan/006-api-observability-baseline.md) · [진행 규칙](README.md#guided-coding).

## 범위와 기준

사용자가 세 추천안을 모두 채택하고 기준 구현·검증 및 따라치기를 진행했다. 여섯 Step 작성·위임 수정 후 이번 PR 요청에서 현재 활성 소스의 최종 통합 검증을 마쳤다. 최신 요청은 커밋·push·main 대상 PR 생성을 포함하며 병합은 포함하지 않는다.

- 착수 시 기존 미커밋 문서 7개와 006 계획을 보존했다. stash/reset 없이 `origin/main`에서 `feat/api-observability-baseline`을 생성했다.
- 2026-11-04 착수 조회: [PR #7](https://github.com/y0y00700/group-purchase/pull/7) MERGED, head `731b8620e0a9fbc5b94f692108eec26e012da379`, [head CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41753997921).
- fetch/main `e5c87d80ed5b1411c9f18766ef082320b33460c5`, [main CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/41754743919). GitHub 커넥터 404 이후 승인된 Git/gh 조회로 확인했다. 이번 코드의 원격 CI를 실행·확인한 것은 아니다. 종료 전 최근 PR 목록에서도 최신 PR이 #7임을 확인했다.

## 구현 내용과 세부 선택

| 위치 | 동작 |
|---|---|
| 공동구매 `web/RequestIdFilter` | 헤더가 정확히 하나이며 소문자 32자리 hex일 때 재사용, 나머지는 UUID v4로 발급. 요청 속성·응답 헤더 연결. REQUEST/ASYNC/ERROR 재디스패치에 같은 ID, MDC finally 복원. AtomicBoolean으로 완료 로그 1회 |
| 공동구매 `web/ApiExceptionResolver`, `ApiErrorController` | `/api/**`의 MVC 핸들러 전 바인딩/협상/리소스 오류·예상 밖 오류, 컨테이너 `/error` 처리. 비 API 오류는 Boot 기본 응답 유지 |
| 두 모듈 `ApiProblems` | RFC 9457 일곱 필드를 명시적으로 허용. 고정 한국어 detail·상태별 code·URN instance. 예외 원문 대신 타입과 최대 8개 정제한 프레임만 별도 ERROR 로그 |
| Gateway `RequestIdWebFilter` | 외부 ID 전체 교체, 전달 헤더 하나, exchange 속성·Reactor Context. MDC를 event-loop에 설정하지 않음. 완료·취소·이미 커밋된 실패를 요청당 1회 기록 |
| Gateway `RoutingFailureHandler` | 자체 404/502/503/504/500 작성. 하위 HTTP 응답 본문/상태/Content-Type 보존, 응답 ID 헤더만 보장. 커밋 뒤 새 본문을 쓰지 않음 |
| 네 앱 최초 YAML·`EarlyFailureLogging` | Boot Logstash JSON·배너 off·service/environment. Config 데이터 실패는 YAML 로딩 전이라 실패 이벤트의 Boot 로깅 정리 후 기본 JSON 로깅을 초기화 |
| 중앙 YAML·Config 자체 YAML | health/info/metrics 제한, probes 활성화, liveness 외부 의존성 제외, 공동구매 readiness에 db·Config readiness에 configurationRepository. service/environment 지표 태그 |
| Config `ManagementBoundaryFilter` | 비허용 `/actuator/**`가 Config의 `/{application}/{profile}`로 해석되는 것을 차단하여 404. 일반 서비스 설정 조회는 유지 |
| Compose | 네 JVM 서비스의 json-file 로그 회전 10m × 3. 포트·DB 볼륨·secret 경계 유지 |
| 자동 검사·CI | 실제 HTTP 오류/ID/비동기·취소/민감정보 검사, Compose 진단·DB probes 복구, Windows 제품 JAR·기동 실패 검사. 기존 build-test와 compose-smoke에 누적 |

계획의 ControllerAdvice/ResponseEntityExceptionHandler 대신 우선순위가 높은 `HandlerExceptionResolver`로 MVC 오류를 처리했다. `/api/**`에만 적용하고 오류 JSON을 직접 작성하여 `Accept: text/html`·호환되지 않는 Accept에서도 재귀 협상을 피한다. Spring `ErrorResponse`의 상태·헤더를 유지하고, 해당 인터페이스가 없는 JSON 읽기/타입 오류도 400으로 분류한다. `ProblemDetail`을 만들고 명시한 일곱 필드만 직렬화한다. 선택 필드 violations는 이번에 추가하지 않았다.

종합 health에는 Boot가 노출하는 `groups: [liveness, readiness]`가 추가될 수 있다. 그룹명은 상태 구분용이며 components/details는 숨긴다. 종합 health의 기존 의존성·HTTP 상태 매핑을 유지한다. Config의 예약 관리 경로 차단은 006 비노출 기준을 충족하기 위한 추가 경계다.

<a id="final-results"></a>

## 사용자 작성 후 최종 검증

2026-11-04 PR 준비에서 현재 활성 소스를 검증했다. 아래 결과가 현재 완료 판단의 근거이며, 뒤의 최초 기준 구현 및 Step별 검증 이력과 구분한다. 사용자 실행 코드의 추가 수정 없이 검증을 통과했다.

| 검사 | 실행·근거 | 최종 결과 |
|---|---|---|
| 전체 빌드·회귀 | JDK 21, `./gradlew.bat build --no-daemon`, `.tmp/006-pr-build.log` | BUILD SUCCESSFUL, 2분 10초. Gateway 9·공동구매 30개 재실행, Config 3·Eureka 8개는 Gradle UP-TO-DATE 결과 재사용. 총 50개, 실패/오류/skip 0 |
| 결과 XML·제품 JAR | `.tmp/006-pr-tests.json` | 네 모듈 XML 합계와 제품 JAR SHA-256 기록 |
| Compose 정의·네 이미지·다섯 서비스 | `docker compose config --quiet`, `docker compose up --build -d --wait --wait-timeout 240`, `.tmp/006-pr-compose.log` | 정의 유효, 현재 코드 이미지 빌드 성공·다섯 서비스 healthy |
| 발견·공개/비공개 경계 | `python scripts/check-discovery.py`, `python scripts/check-gateway.py`, `.tmp/006-pr-runtime.log` | 등록 주소 도달, 공개 ping 200·비허용 경로/메서드 404 PASS |
| 실제 오류/ID/로그/지표·DB 복구 | `python scripts/check-api-observability.py --db-recovery`, 같은 로그 | JSON·민감정보·노출·probes·HTTP COUNT·연결 풀 검사 PASS. DB 중단 중 liveness/ping 200·readiness/root 503, 복구 후 200. 같은 컨테이너 ID·PID·시작 시각·재시작 횟수 유지 |
| Windows 제품 JAR | `python scripts/check-api-observability-local.py`, `.tmp/006-pr-local.log` | 두 API 제품 JAR 계약·ID/로그 연결, Config 실패·잘못된 DB 인증의 JSON/민감정보 검사, fixture/runtime probe 제외 PASS. 검사 프로세스 자동 정리 |

일반 DB 볼륨·기존 secret을 보존했다. 부모 환경 변경 없이 자식 실행에 JDK/비밀번호를 제공했고, 실제 비밀번호를 argv나 보고서에 넣지 않았다. 005의 기존 권한·영속성·120초 장애/전체 down-up 검증은 관련 실행 구성이 바뀌지 않아 재사용하며, 이번 검사로 다시 수행했다고 표시하지 않는다. 개별 수동 curl 묶음은 아래 사용자용 절차로 제공한다.

<a id="pull-request"></a>

## PR 준비와 원격 상태

2026-11-04 사용자가 PR 생성을 요청했다. 준비 시점의 최신 main은 `e5c87d80ed5b1411c9f18766ef082320b33460c5`로 브랜치 기준과 같고, [기존 main CI 두 검사](https://github.com/y0y00700/group-purchase/actions/runs/41754743919)는 성공이며 열린 PR은 없었다. GitHub 커넥터는 저장소 접근 오류여서 Git/gh로 진행한다. 006 브랜치의 원격 CI는 PR 생성 후 상태와 구분하며, 생성·push 후 완료를 기다리거나 반복 조회하지 않는다. PR 병합은 요청 범위에 포함하지 않는다.

구현·테스트·문서 45개 파일을 `3965777cc51b6327dc342f6ae8fe32ec66803de4`로 커밋·push하고 main 대상 [PR #8](https://github.com/y0y00700/group-purchase/pull/8)을 생성했다. PR 링크를 반영하는 문서 커밋을 같은 브랜치에 추가하며, 정확한 최종 head와 원격 검사 상태는 [PR #8 Checks](https://github.com/y0y00700/group-purchase/pull/8/checks)를 기준으로 한다. 기존 main 성공·로컬 50개 결과를 이번 PR CI 성공으로 간주하지 않는다. 커밋 전 문서 링크 132개·Python 문법·변경 형식·실제 secret 값 제외를 확인했고, Git 제외 학습 노트·원시 로그·생성 산출물은 포함하지 않았다. PR은 병합하지 않았다.

## 기준 구현 검증

이 절은 **직접 작성 주석 전환 전의 활성 구현** 근거다. 최종 활성 기준 소스의 전체 46개 테스트가 실패/오류/skip 없이 통과했다. 주석 전환 전 XML 요약과 제품 JAR SHA-256은 `.tmp/006-baseline-tests.json`에 보관했다.

| 검사 | 실행 명령·근거 | 결과 |
|---|---|---|
| 전체 빌드·회귀 | `./gradlew.bat build --no-daemon`, `.tmp/006-build-final.log` | 성공: 46개 테스트, 0 실패/오류/skip |
| 네 이미지·다섯 서비스 | `docker compose up --build --wait --wait-timeout 240`, `.tmp/006-compose-final.log` + Config 경계 보완 `.tmp/006-config-image.log` | 네 이미지 성공·다섯 서비스 healthy |
| 실제 관측 계약·DB 복구 | `python scripts/check-api-observability.py --db-recovery`, `.tmp/006-runtime.log` | PASS: 오류/ID/JSON/미노출/probes/metrics/동일 프로세스 DB 복구 |
| Windows 제품 JAR | `python scripts/check-api-observability-local.py`, `.tmp/006-local.log` | PASS: 두 API 제품 JAR·Config 초기 실패·가짜 DB 인증 실패 JSON/미노출·fixture 제외 |

기존 `check-discovery.py`·`check-gateway.py`도 PASS다. DB 중단 중 공동구매 liveness/ping 200, root/readiness 503과 DB 복구 후 200을 실제 확인했고 container ID·시작 시각·PID·restart count가 같음을 검사했다. 종료 시 임시 Java 프로세스는 정리했고 일반 DB 볼륨·secret·부모 환경을 보존했다.

실패를 통과로 간주하거나 기대 상태를 낮추지 않았다. 검증 중 발견한 `ProblemDetail.type` null, MVC JSON/타입 오류의 ErrorResponse 외 분류, 취소 로그에서 null의 숫자 자동 변환, Config 로딩 전 일반 텍스트 로그, Config 관리 경로의 설정 조회 fallback을 수정했다. health의 공개 그룹명은 프레임워크의 정상 응답으로 검사하며 components/details 미노출을 별도로 확인한다.

원시 앱 stdout을 줄별 JSON으로 파싱하고 Authorization/Cookie/본문/쿼리/예외 메시지의 가짜 sentinel, Config 실패·잘못된 DB 인증의 가짜 비밀번호를 검사한다. 실제 secret은 child 환경/기존 secret 파일로만 주입한다. 제품 JAR에 테스트 fixture·runtime probe가 없는지도 확인한다. 기존 005의 120초 장애·전체 down/up을 반복한 것으로 기록하지 않는다.

## 현재 소스와 직접 작성 진행

**현재: 여섯 Step 실행 코드·최종 통합 검증 완료.** 이전 수정 요청에서 사용자가 지적된 세 곳의 수정을 AI에 맡겼다. STEP-01 `doFilter`와 STEP-02 `resolveException`의 검증된 본문을 복원하고 STEP-05의 import를 `java.util.concurrent.TimeoutException`으로 수정했다. 사용자가 작성한 ID 두 메서드·STEP-03/04/06과 STEP-05의 분류 로직은 유지했다. 이후 이번 PR 준비에서 [현재 소스의 최종 검증](#final-results)을 완료했다.

| 검증 시점 | 실제 결과·근거 |
|---|---|
| 최초 활성 기준 구현 | 전체 46개 테스트·이미지/Compose·Windows JAR 성공. 앞 절과 `.tmp/006-baseline-tests.json`. 현재 재작성 소스의 전체 검증과 구분 |
| 여섯 Step 주석 전환 전 | MVC 8개 + Gateway 7개 성공. `.tmp/006-six-steps-baseline.log` |
| 수정 전 사용자 코드 리뷰 | 컴파일/ID 선택 3개 통과. timeout 회귀 2개는 기대 504/실제 500·502로 실패. `.tmp/006-six-steps-user-review.log`, `.tmp/006-timeout-import-review.log` |
| 사용자 위임 수정 후 현재 실행 코드 | **MVC 8개 + Gateway 9개 = 17개 모두 통과**, 실패/오류/skip 0. `.tmp/006-six-steps-fixed.log`, XML 요약 `.tmp/006-six-steps-fixed-tests.json` |
| 참고 주석·표식 정리 | 검증 후 여섯 Step 중복 참고 주석·입력 표식·주석 처리된 임시 예외 제거. 실행 코드 행이 정리 전후 동일함을 검사. 활성 작성 대기 예외 없음 |
| 이전 Step 수정 인계 당시 미실행 | 당시 전체 build·제품 JAR/이미지·Compose/DB 복구·Windows JAR 재검증은 남아 있었다. 이번 PR 준비에서 위 최종 검증으로 완료했으며, 원격 CI는 별도 상태로 관리 |

## 학습 구간 반영 결과

| 구간 | 위치·역할 | 반영 |
|---|---|---|
| ID 선택·요청 속성 | [RequestIdFilter](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/web/RequestIdFilter.java)의 selectRequestId/resolveRequestId | 사용자 작성 코드 유지·검증 |
| STEP-01 | 같은 파일의 doFilter: 응답 ID·MDC finally 복원·비동기 완료 | 사용자 위임으로 AI가 본문 복원·검증 |
| STEP-02 | [ApiExceptionResolver](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/web/ApiExceptionResolver.java)의 resolveException: 상태/헤더·오류 처리 경계 | 사용자 위임으로 AI가 본문 복원·검증 |
| STEP-03 | [MVC ApiProblems](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/web/ApiProblems.java)의 bytes: 안전한 일곱 필드 | 사용자 작성 코드 유지·검증 |
| STEP-04 | [RequestIdWebFilter](../../infrastructure/api-gateway/src/main/java/com/example/apigateway/RequestIdWebFilter.java)의 filter: ID 전파·컨텍스트·완료/취소 | 사용자 작성 코드 유지·검증 |
| STEP-05 | [RoutingFailureHandler](../../infrastructure/api-gateway/src/main/java/com/example/apigateway/RoutingFailureHandler.java)의 classify: 원인별 상태 | 사용자 분류 코드 유지·AI가 timeout import 수정·회귀 2개 통과 |
| STEP-06 | 같은 파일의 handle: 커밋 보호·Problem/HEAD·종료 처리 | 사용자 작성 코드 유지·검증 |

한 번에 여섯 Step을 준비하는 학습 규칙은 AGENTS.md와 구현 안내에 유지한다. 이번 여섯 Step은 작성·검증을 마쳤으며 재차 비우지 않는다. 현재 활성 소스로 필수 로컬 완료 기준을 충족했고 사용자의 이번 요청에 따라 커밋·push·PR을 진행한다. 원격 CI의 성공 여부는 로컬 완료와 구분한다.

<a id="manual-tests"></a>

## 사용자 직접 테스트

현재 여섯 구간은 활성 실행 코드이며 관련 검사 17개와 [전체 빌드·실행 검증](#final-results)을 통과했다. 아래 전체 검증 명령은 이번 PR 준비에서 현재 소스·재생성한 이미지/JAR로 실행했다. 개별 curl 요청 묶음은 사용자가 응답을 살펴볼 수 있는 별도 절차다.

준비: PowerShell, JDK 21, Python 3, Docker Desktop Linux 엔진과 기존 secret 파일. 이 명령은 기존 일반 DB 볼륨을 보존한다. 별도 Config/로컬 앱 프로세스가 이미 있다면 해당 터미널에서 Ctrl+C로 종료해 Compose 호스트 포트와 충돌하지 않게 한다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.12.1"
# 실제 통과한 관련 검사: MVC 8개 + Gateway 9개.
.\gradlew.bat :group-purchase-service:test --tests '*RequestIdSelectionTests' --tests '*RequestIdFilterDispatchTests' --tests '*ApiObservabilityTests' :api-gateway:test --no-daemon
```

최종 통합 검증에 사용한 명령:

```powershell
.\gradlew.bat build --no-daemon
docker compose up --build --wait --wait-timeout 240
python scripts/check-discovery.py
python scripts/check-gateway.py
# DB가 잠시 중단된다. finally에서 재시작하고 같은 앱의 복구를 기다린다.
python scripts/check-api-observability.py --db-recovery
# Compose를 유지하고 local 제품 JAR을 18080/18000/19000에 띄워 자동 정리한다.
python scripts/check-api-observability-local.py
```

기대: 오류의 Content-Type `application/problem+json`, 정확한 상태/코드·안전한 일곱 필드, 헤더/본문 ID 일치. 공개 ping 200/pong, 비허용 경로/메서드 404, 직접 MVC POST ping 405/Allow 유지. Gateway는 외부 ID를 교체하고 두 앱 로그에서 같은 ID로 완료 이벤트를 각각 한 번 찾는다. 지표의 COUNT가 실제 요청 수만큼 증가하며 관리 포트의 비허용 엔드포인트는 404다.

직접 응답 확인:

```powershell
curl.exe -i -H 'X-Request-Id: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' http://localhost:8000/api/group-purchases/ping
curl.exe -i -H 'Accept: text/html' http://localhost:8000/missing
curl.exe -i -X POST http://localhost:8080/api/group-purchases/ping
curl.exe -s http://localhost:8080/actuator/health/liveness
curl.exe -s http://localhost:8080/actuator/health/readiness
curl.exe -s http://localhost:8080/actuator/metrics/http.server.requests
docker compose exec -T api-gateway curl --silent http://localhost:9000/actuator/health/readiness
# 응답에서 복사한 ID를 사용한다.
$requestId = '응답의 요청 ID'
docker compose logs --no-color api-gateway group-purchase-service | Select-String -SimpleMatch $requestId
```

DB 수동 장애·복구: 다른 요청/테스트가 DB를 사용하지 않을 때 실행한다. 정상은 root/readiness/liveness 200. DB 중단 중 liveness 200, readiness·root 503, DB 비사용 ping 200. 재시작 후 같은 앱의 readiness/root가 200으로 돌아와야 한다.

```powershell
try {
    docker compose stop postgres
    curl.exe -i http://localhost:8080/actuator/health/liveness
    curl.exe -i http://localhost:8080/actuator/health/readiness
    curl.exe -i http://localhost:8080/actuator/health
    curl.exe -i http://localhost:8080/api/group-purchases/ping
} finally {
    docker compose start postgres
}
# 커넥션 복구는 즉시 완료가 아닐 수 있다. 자동 검사는 제한시간 내 실제 상태 전환을 기다린다.
docker compose ps
```

종료·복구: 자동 local 스크립트는 자신이 시작한 Java 프로세스만 종료하고 부모 환경은 바꾸지 않는다. Compose는 그대로 유지한다. 사용자가 Ctrl+C 등으로 DB 검사 자체를 중단했다면 `docker compose start postgres` 후 `docker compose up --wait --wait-timeout 240`, health와 공개 ping으로 복구를 확인한다. 전체 종료가 필요하면 `docker compose stop`을 사용한다. `down -v`·볼륨 삭제·Flyway clean/repair는 사용하지 않는다. 자동 검증은 현재 소스로 실행했으며, 개별 수동 curl 명령 묶음을 별도로 전부 재실행한 것으로 표시하지 않는다.

## 학습 연결

[API 오류·요청 ID·JSON 로그](../explanations/api-observability.md) · [Actuator health와 metrics](../explanations/actuator-health-metrics.md). Git 제외 정책을 유지한다. 실제 역할·적용 예시와 사용자 작성 후 최종 검증 상태를 반영했다.
