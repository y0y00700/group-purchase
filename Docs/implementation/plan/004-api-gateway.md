# 004. API Gateway 기본 라우팅

작성일: 2026-11-02

상태: 완료 — 사용자가 2026-11-02 계획대로 진행을 요청했다. 구현과 로컬 검증을 마쳤다. 실제 결과와 원격 CI 미실행 범위는 [004 결과](../004-api-gateway.md)를 따른다.

진행 합의 범위: 이 계획의 Gateway·공개 경로·Config/Eureka 연동·장애 응답·이미지·Compose·CI 정의·직접 테스트와 로컬 학습 노트. 커밋·push·PR·원격 CI 실행은 별도 요청 범위다.

## 목표와 현재 상태

외부 HTTP 요청을 받는 Gateway를 추가하고, Eureka에서 `group-purchase`의 주소를 찾아 공동구매 앱으로 전달한다. 허용한 공개 경로만 전달하며 내부·관리 경로가 Gateway를 통해 노출되지 않는지 확인한다. 대상이 없거나 응답하지 않을 때의 실패와 복구도 검증한다.

현재 실행 모듈은 Config Server·Eureka Server·공동구매 초기 웹 앱이다. 업무 API는 아직 없다. 2026-11-02 계획 작성 시 원격 `main`과 로컬 HEAD가 `a2a88dc`로 같고, [PR #3](https://github.com/y0y00700/group-purchase/pull/3)의 병합과 [main CI](https://github.com/y0y00700/group-purchase/actions/runs/41402512856)의 두 검사 성공을 다시 확인했다. `docker compose ps`에서도 기존 세 서비스가 모두 healthy였다. 기존 여섯 문서의 로컬 변경은 보존한다.

관련 기준: [로드맵](roadmap.md), [아키텍처·인증 경계](../../design/architecture.md#security), [실행 환경](../../design/operations.md#local-runtime), [HTTP 초기 제한시간](../../design/operations.md#operating-values), [단계별 진행·직접 테스트](../README.md#workflow).

## 먼저 검토할 세 가지

| 결정할 문제 | 추천안과 이유 | 대안과 비용 |
|---|---|---|
| Gateway 실행 방식 | 독립 `api-gateway` 모듈에 Spring Cloud Gateway Server WebFlux를 사용한다. Netty에서 라우팅하고 기존 공동구매 MVC 앱은 HTTP로 연결한다 | Server Web MVC도 가능하다. 익숙한 MVC 기반으로 구성할 수 있으나 라우팅·필터 API가 다르므로 선택한 방식으로 테스트와 후속 인증 구현을 일관되게 유지해야 한다 |
| 공개 경로와 전달 방식 | `GET /api/group-purchases/ping`을 명시하고 `lb://group-purchase`로 전달한다. 서비스 자동 경로 생성은 끄고 내부·관리 경로는 라우트에 넣지 않는다 | `/group-purchase/**` 자동 공개나 `/api/**` 전체 전달은 설정이 짧지만 서비스 추가·내부 API 추가 시 공개 범위가 의도치 않게 넓어진다 |
| 포트와 장애 계약 | Gateway는 8000, 자체 관리는 별도 9000으로 나눈다. 경로 없음 404·인스턴스 없음 503·연결 실패 502·대기 초과 504를 구분한다 | 기존 8080을 Gateway에 넘기면 주소가 익숙하지만 기존 직접 테스트·Eureka 등록 주소·스크립트를 함께 변경해야 한다. 프레임워크 기본 오류만 사용하면 구현은 줄지만 전송 실패의 응답 계약을 별도로 확인해야 한다 |

사용자가 이 계획대로 구현을 요청해 추천안을 적용했다. 세부 검증 결과와 계획에서 달라진 점은 결과 문서에 기록한다.

## 구성과 설정

```text
클라이언트 → Gateway :8000 → 공동구매 앱 :8080
                 │                │
                 └── 주소 조회 ───┴── 등록 → Eureka :8761

Gateway·Eureka·공동구매 앱 → 고정 Config 주소 :8888
Gateway 자체 health/info → 관리 포트 :9000
```

업무 요청은 Gateway에서 공동구매 앱으로 직접 전달한다. Eureka는 주소 조회에만 사용한다.

- 모듈 경로는 `infrastructure/api-gateway/`, Gradle 프로젝트와 앱 이름은 `api-gateway`로 제안한다.
- Java 21·Boot 4.0.9·Cloud BOM 2025.1.4·Gradle 9.8.2를 유지한다. 로컬에 캐시된 BOM POM의 Gateway 관리 버전은 5.0.4다. 실제 런타임 의존성 해석·기동 호환성은 구현 때 확인하고 기록한다.
- 의존성은 `spring-cloud-starter-gateway-server-webflux`, LoadBalancer, Eureka Client, Config Client, Actuator, 필수 설정 검증과 필요한 테스트 도구다. Gateway 실행·테스트 classpath에 다른 서버의 MVC starter를 섞지 않는다. [공식 starter·Netty 요구사항](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/starter.html)
- 앱 이름·프로필·고정 Config 주소는 최초 접속 전에 결정한다. 기존처럼 `spring.config.import`를 필수로 사용하고, Config를 Eureka에서 먼저 찾지 않는다.
- 일반 설정은 `config-repo/api-gateway.yml`, `api-gateway-local.yml`, `api-gateway-docker.yml`에 둔다. 필수 식별값은 `service=api-gateway`, 환경별 `environment`, `revision=004-v1`을 제안한다. 값 누락·프로필 오타·서비스/환경 불일치는 기동 실패로 처리한다.
- Gateway는 Eureka 조회만 수행한다. 이번에는 Gateway 주소를 발견할 내부 호출자가 없으므로 `register-with-eureka=false`, `fetch-registry=true`로 둔다. 등록 기능이 필요한 서비스가 생기면 재검토한다.
- Config 준비 상태 검사에 Gateway의 local/docker 설정을 추가한다. 검사 과정에서 Gateway HTTP를 호출하지 않아 시작 의존성이 역전되지 않게 한다. 설정 변경 반영은 기존처럼 클라이언트 재시작 방식이다.
- Compose의 Gateway는 Config·Eureka가 healthy가 된 뒤 시작한다. 공동구매 앱의 등록과 Gateway 발견 캐시의 준비 완료는 별도 검사로 기다린다. 실행 중 장애 복구를 `depends_on`이 수행한다고 가정하지 않는다.

| 구분 | local | docker |
|---|---|---|
| Gateway 요청 주소 | `http://localhost:8000` | 호스트의 `http://localhost:8000`, 내부의 `http://api-gateway:8000` |
| Gateway 관리 주소 | `http://127.0.0.1:9000/actuator/health` | 컨테이너 안의 `http://localhost:9000/actuator/health`; 호스트 포트 미게시 |
| Config 주소 | `http://localhost:8888` | `http://config-server:8888` |
| Eureka 주소 | `http://localhost:8761/eureka/` | `http://eureka-server:8761/eureka/` |
| 발견할 서비스 | `group-purchase`, 등록 주소 `localhost:8080` | `group-purchase`, 등록 주소 `group-purchase-service:8080` |

local 관리 서버는 loopback에 바인딩한다. Compose는 Gateway의 `127.0.0.1:8000:8000`만 추가 게시하고 관리 9000은 게시하지 않는다. 기존 세 서비스의 loopback 포트는 로컬 진단에 사용한다. 따라서 이번 검증은 Gateway의 전달 경계까지이며, 모든 직접 접근의 인증·차단을 완료했다는 의미는 아니다. 내부 호출자 자격 증명과 신원 헤더 신뢰는 기존 로드맵 8단계에서 구현한다.

## 공개·내부 경로 계약

아직 도메인 API가 없으므로 공동구매 앱에 상태를 변경하지 않는 확인용 `GET /api/group-purchases/ping`을 추가한다. 응답 제안은 `200`과 `{"service":"group-purchase","message":"pong"}`이다. DB·인증 상태·환경변수·내부 주소는 응답에 넣지 않는다.

| Gateway 8000으로 들어온 요청 | 처리와 기대 결과 |
|---|---|
| `GET /api/group-purchases/ping` | `lb://group-purchase`로 전달. 대상 앱에서도 같은 경로를 사용하고 경로 재작성은 하지 않음 |
| 같은 경로의 POST·PUT·DELETE 등 | 현재 허용한 GET 라우트와 불일치하여 404, 대상 호출 없음 |
| `/internal/**`, `/api/group-purchases/internal/**` | 404, 대상 호출 없음 |
| `/actuator/**`, `/api/group-purchases/actuator/**` | 404, 대상 호출 없음. Gateway 자체 관리는 9000에서만 확인 |
| `/eureka/**`, `/config-server/**`, `/group-purchase/**`, 미등록 API | 404, 대상 호출 없음 |

라우트는 `spring.cloud.gateway.server.webflux.routes`에 작성하고 `discovery.locator.enabled=false`를 같은 WebFlux 설정 아래 명시한다. 등록된 서비스마다 외부 경로를 자동 생성하지 않는다. 서비스 이름 기반 주소 선택에는 LoadBalancer를 사용한다. 실제 BOM 버전의 설정 메타데이터·바인딩과 동작을 함께 확인한다. [공식 설정 목록](https://docs.spring.io/spring-cloud-gateway/reference/configprops.html), [서비스 발견 경로와 LoadBalancer 설명](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/the-discoveryclient-route-definition-locator.html)

업무 API가 추가될 때 해당 기능의 공개 경로·메서드·인증을 함께 등록한다. 현재부터 업무 경로 전체를 와일드카드로 열지 않는다. 경로 전달과 쿼리·일반 헤더, 대상의 상태·JSON 본문 보존은 테스트용 HTTP 서버로 검증하고, 운영 코드에 echo·장애 유발 API를 추가하지 않는다.

## 장애·헬스·복구 정책

| 조건 | 제안하는 동작 |
|---|---|
| Config 접속 불가·필수 설정 오류 | 기존 클라이언트 정책대로 Gateway 기동 실패 |
| Config는 정상이고 Eureka만 없음 | Gateway 기동·자체 헬스는 유지. 조회 재시도 후 목록 수렴. 주소를 모르는 라우팅 요청은 503 |
| 요청에 맞는 공개 라우트 없음 | 404. 대상 서비스를 조회·호출하지 않음 |
| 라우트는 있지만 선택할 인스턴스 없음 | 503. 경로 없음과 구분 |
| 캐시에 남은 주소로 연결했으나 연결 거절·DNS 실패 | Gateway가 만든 전송 오류를 502로 변환 |
| 연결·응답 대기 제한 초과 | 504. 연결 1초·응답 대기 5초를 초기값으로 적용 |
| 대상이 정상 HTTP 응답으로 4xx·5xx 반환 | 해당 상태와 본문을 전달. 전송 실패와 구분 |
| 대상 중단·재기동 | Gateway를 재시작하지 않고 발견 목록 수렴 후 다시 200 |
| Eureka 중단·복구 | 기존 발견 캐시가 있으면 그 주소로 요청이 성공할 수도 있음. 복구 후 Gateway 자신의 발견 목록과 실제 전달을 확인 |

인스턴스가 없을 때 503을 사용하는 것은 Gateway의 기본 정책이다. 연결 실패의 502 등 추가 매핑은 이번에 구현할 계약이며 기본 동작으로 단정하지 않는다. 필요한 전송 예외만 최소 범위로 처리하고 내부 호스트·스택 추적을 응답에 노출하지 않는다. 전체 공통 오류 형식·요청 식별자·JSON 로그는 로드맵 6단계에서 정리한다. [공식 LoadBalancer 필터](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html)

제한시간은 `spring.cloud.gateway.server.webflux.httpclient` 아래 `connect-timeout=1000`, `response-timeout=5s`를 기준으로 실제 바인딩·지연 응답을 검사한다. 이 값만으로 전체 요청의 최대 소요 시간을 보장했다고 표시하지 않는다. Gateway의 Retry 필터·성공 대체 응답·Circuit Breaker는 이번 범위에 추가하지 않는다. [공식 제한시간·단위](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html)

자체 `/actuator/health`는 프로세스 확인용으로 사용한다. 발견 상태와 대상 응답 성공은 별도 요청으로 판정한다. 기존 Eureka 기본 갱신 주기·자기 보호를 유지하고, 강제 종료된 앱이 일정 시간 안에 무조건 등록에서 제거된다고 가정하지 않는다. 정상 종료 뒤에도 Gateway의 캐시가 수렴하기 전에는 502가 관측될 수 있으며, 인스턴스가 없어진 상태의 503과 구분한다. 수렴 검사는 최대 180초의 조건 확인을 초기 한도로 사용하고 운영 SLA로 해석하지 않는다.

## 변경 예정 파일과 구현 순서

| 순서 | 파일·경로 | 작업 |
|---|---|---|
| 1 | `settings.gradle`, `infrastructure/api-gateway/build.gradle`, `src/main/` | 독립 실행 모듈, Config 수신·설정 검증·WebFlux 라우팅·관리 포트·전송 오류 처리 |
| 2 | `config-repo/api-gateway*.yml`, Config Server의 준비 상태 검사·테스트 | 환경별 일반 설정·명시적 공개 라우트·새 설정 누락/오타 감지 |
| 3 | 공동구매 앱의 `src/main/`, `src/test/` | GET ping과 응답 검증. 기존 업무 기능 없는 상태를 유지 |
| 4 | Gateway `src/test/`, 필요한 테스트 fixture | 경로 경계·전달·실패 계약과 실제 Config/Eureka/공동구매 연동·복구 검증 |
| 5 | Gateway `Dockerfile`, 기존 세 모듈 `Dockerfile`, `compose.yml` | 새 모듈을 빌드 컨텍스트에 포함하고 네 서비스 일괄 빌드·기동·종료 |
| 6 | `.github/workflows/ci.yml`, `scripts/check-gateway.py` | 기존 등록 검사 유지, 네 서비스 설정·실제 전달·차단 경로 검사 추가 |
| 7 | 실행·문서 안내·인계·로드맵, `Docs/implementation/004-api-gateway.md` | 실제 변경·검증 근거·남은 한계·사용자 직접 테스트 기록 |

실제 구현 시작 시 최신 main과 기존 문서 변경을 다시 확인하고 새 작업 브랜치를 만든다. 구현 요청은 해당 단계의 코드·이미지·Compose·로컬 검증·CI 정의·결과 기록까지로 해석한다. 커밋·push·PR 등은 해당 요청 범위를 따른다.

## 검증 계획과 완료 기준

| 검증 | 완료 기준 |
|---|---|
| 전체·선택 빌드 | 기존 23개 테스트 회귀 없이 전체 빌드와 Gateway 선택 빌드 성공. 실제 Gateway·LoadBalancer·Netty 버전 기록 |
| 설정·기동 | local/docker 수신과 관리 포트 분리 확인. Config 장애·필수 값 누락·프로필 오타·환경 불일치 시 실패 |
| 공개 경로 | 실제 Gateway → Eureka 발견 → 실제 공동구매 ping의 200·기대 본문 확인 |
| 전달 보존 | 제어 가능한 HTTP fixture에서 실제 요청 경로·쿼리·일반 헤더와 대상 응답 상태·JSON 본문 확인 |
| 비공개 경로 | 내부·관리 경로와 서비스 ID 자동 경로·유사 접두사·메서드 불일치가 대상에 전달되지 않음. fixture 수신 기록으로 확인 |
| 실패 계약 | 빈 인스턴스 503, 연결 거절 502, 제어한 응답 지연 504, 대상 자체 4xx·5xx 보존 확인. 연결 대기 초과의 실제 재현 여부는 별도로 기록 |
| 중단·복구 | 정상 종료 후 Gateway 캐시 수렴과 503, 재기동 후 200. Eureka 없는 Gateway 기동·서버 복구 후 실제 라우팅 확인 |
| 헬스 분리 | 대상 장애 중에도 Gateway 자체 health와 라우팅 성공 여부를 따로 판정. 8000의 관리 경로 미노출 확인 |
| 이미지·Compose | 네 이미지 빌드·컨테이너 healthy, 기존 설정·등록 주소 검사와 새 Gateway 검사 성공 |
| Windows·CI·직접 테스트 | Windows JAR local 연동과 실제 Compose 절차 수행 결과 기록. CI는 실행한 경우 해당 커밋의 두 검사 결과를 기록하며 정의 수정만으로 성공 처리하지 않음 |

집중 라우팅 테스트는 제어 가능한 HTTP 서버와 테스트용 인스턴스 공급자로 오류를 재현한다. 실제 Config·Eureka·공동구매 연동은 Compose와 Windows JAR 수동 검증으로 확인했다. Gateway 자동 테스트에 다른 MVC 서버 프로젝트 의존성을 넣지 않았다. 이 분리는 계획의 외부 JAR 통합 테스트를 자동화하는 대신 로컬에서 직접 실행해 확인한 변경이다. 자세한 재현 명령과 미검증 범위는 [004 결과](../004-api-gateway.md)를 따른다.

## 사용자 직접 테스트에 포함할 절차

아래는 계획 당시의 직접 테스트 초안이다. 실제 실행·기대 결과·복구 순서와 미검증 항목은 [004 결과](../004-api-gateway.md#manual-tests)에 확정해 기록했다.

준비 사항은 Docker Desktop Linux 엔진, Python 3, 비어 있는 8000·8080·8761·8888 포트다. JAR 실행·Gradle 검증에는 JDK 21과 `JAVA_HOME`, local 프로필에는 관리 9000도 필요하다. 작업 디렉터리는 저장소 루트다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
docker compose up --build -d --wait --wait-timeout 180
python scripts/check-discovery.py
python scripts/check-gateway.py --timeout 180
curl.exe -i http://localhost:8000/api/group-purchases/ping
curl.exe -i http://localhost:8000/internal/probe
curl.exe -i http://localhost:8000/actuator/health
docker compose exec -T api-gateway curl --fail --silent http://localhost:9000/actuator/health
```

기대 결과는 네 서비스 healthy, 두 스크립트 PASS, ping 200, 내부·관리 경로 404, 컨테이너 안의 Gateway 자체 health UP이다. Compose healthy와 Gateway의 발견 완료 시점은 다를 수 있어 검사 스크립트가 조건을 기다린다.

```powershell
docker compose stop group-purchase-service
python scripts/check-discovery.py --absent --timeout 180
# Gateway 자체 캐시가 수렴할 때까지 조건 확인. 최종적으로 503 기대.
curl.exe -i http://localhost:8000/api/group-purchases/ping
docker compose start group-purchase-service
python scripts/check-gateway.py --timeout 180
docker compose down
```

최종 안내에는 502→503의 수렴 대기 명령, Eureka 중단·복구, Config 장애·설정 오타의 일회성 실행과 종료, 응답 지연 fixture의 실행·종료 방법도 포함한다. 오류별 자동 테스트 명령을 함께 제공하고, 실제 Compose에서 재현하지 않은 오류는 자동 fixture 검증과 구분한다. 임시 설정은 원본 복원까지 안내하며 실패한 경우에도 중단한 서비스를 다시 기동하고 마지막에 `docker compose down`으로 네 서비스를 종료할 수 있게 한다.

구현 결과와 직접 테스트는 [004 결과](../004-api-gateway.md)에 기록했다. 다음 순서는 로드맵의 PostgreSQL 실행 기반이다.

[계획 목록](../README.md) · [최신 인계](../../handoff.md)
