# 003. Eureka Server와 첫 서비스 등록·발견

작성일: 2026-11-02

상태: 완료 — 2026-11-02 사용자가 검토 완료·추천안에 따른 구현을 요청했다. 구현·로컬 검증을 마쳤으며 [실제 결과와 직접 테스트](../003-eureka-server.md)를 따른다. 원격 CI는 미실행이다.

진행 합의 범위: 이 계획의 서버·첫 클라이언트 등록/조회·이미지·Compose·CI·직접 테스트와 결과 기록. 외부 이미지 게시·배포·PR 생성/병합은 포함하지 않는다.

## 목표와 현재 상태

Eureka를 서비스 이름과 실행 중인 인스턴스의 주소를 연결하는 등록소로 추가한다. 기존 공동구매 앱을 첫 등록 대상으로 삼아 등록·조회·종료·재등록과 서버 장애 후 복구를 검증한다. 이후 Gateway가 `group-purchase`라는 이름으로 대상을 찾을 기반을 마련한다.

현재 Java 21·Boot 4.0.9·Cloud BOM 2025.1.4·Gradle Wrapper 9.8.2를 사용한다. 실행 모듈은 Config Server와 공동구매 초기 웹 앱이다. [002 결과](../002-config-server.md)의 자동 테스트 13개·로컬 Compose 검증을 완료했고, [PR #2](https://github.com/y0y00700/group-purchase/pull/2)는 2026-11-02 병합되었다. PR 최종 head `b20925e`의 빌드·테스트와 이미지·Compose CI도 성공했다.

관련 기준은 [로드맵](roadmap.md), [서비스 경계](../../design/architecture.md#service-boundaries), [실행 환경](../../design/operations.md#local-runtime), [직접 테스트 작성 기준](../README.md#사용자-직접-테스트-방법)을 따른다.

## 먼저 검토할 세 가지

| 결정할 문제 | 추천안과 이유 | 대안과 비용 |
|---|---|---|
| Eureka의 설정과 최초 접속 | Eureka도 고정 Config 주소에서 일반 설정을 받는다. Config → Eureka → 공동구매 순서로 기동하고 Config 자체는 Eureka에 등록하지 않는다. 기존 설정 관리 방식을 유지하며 순환 의존을 피한다 | Eureka의 모든 설정을 앱에 두면 Config 없이 실행할 수 있지만 환경별 일반 설정이 분산된다. Config를 Eureka로 먼저 찾으면 시작 의존성이 순환할 수 있다 |
| 첫 등록 범위와 주소 | 단일 Eureka와 기존 공동구매 앱 하나로 시작한다. 서비스 이름은 `group-purchase`를 유지하며 local은 `localhost`, docker는 같은 Compose 네트워크에서 접근 가능한 주소를 등록한다 | 처음부터 여러 Eureka·다중 인스턴스를 구성하면 복제·주소·실행권과 검증 비용이 늘어난다. 고가용성은 별도 검토한다 |
| 장애·회복의 판정 | Config 수신 실패는 기존처럼 기동 실패. Eureka 접속 실패는 앱 실행을 유지하며 백그라운드 등록·조회 재시도를 확인한다. 앱 헬스와 등록·발견 성공은 각각 검사한다 | 최초 등록 성공을 기동 필수 조건으로 삼으면 발견 전에 실패를 알기 쉽지만 등록소의 일시 장애가 앱 기동·재시작까지 막는다 |

추천안의 완료 범위는 단일 호스트의 로컬 개발·학습 환경이다. Gateway 라우팅·부하 분산·회원 인증·DB·Kafka·업무 API·Eureka 다중 서버·외부 배포는 후속 단계로 둔다.

## 구성과 기동 순서

```text
Config Server :8888       ← 각 앱이 고정 주소로 일반 설정 요청
    ↓ 설정 수신
Eureka Server :8761       ← 공동구매 앱이 등록·하트비트·등록 목록 조회
    ↓ 등록·발견
공동구매 앱 :8080
```

화살표는 시작 의존 순서다. HTTP 업무 요청을 Eureka가 중계하지 않는다. 서비스 간 실제 요청 전달은 다음 Gateway 단계에서 구현한다.

1. Config는 기존처럼 자신의 시작 설정과 파일 시스템 설정 저장소만으로 실행한다. Eureka 주소 조회나 Eureka의 실행 상태에 의존하지 않는다.
2. Eureka는 앱 이름 `eureka-server`, 프로필, Config 주소를 자신의 `application.yml`과 환경변수로 먼저 정한 뒤 `spring.config.import`로 중앙 설정을 받는다. `optional:`과 별도 `bootstrap.yml`은 사용하지 않는다. [Config 공식 문서](https://docs.spring.io/spring-cloud-config/reference/client.html)
3. 단독 Eureka는 자기 등록과 다른 등록소 조회를 끈다. 포트는 8761이며 별도 DB는 추가하지 않는다. 서버 재시작 후 등록 정보는 살아 있는 클라이언트의 재등록으로 복구한다. [Eureka standalone 구성](https://docs.spring.io/spring-cloud-netflix/reference/spring-cloud-netflix.html#_standalone_mode)
4. Compose에서 Eureka는 Config의 healthy 이후, 공동구매 앱은 Config와 Eureka의 healthy 이후 기동한다. 이는 최초 시작 순서이며 실행 중 장애 복구는 클라이언트가 수행한다.
5. Config의 저장소 헬스 검사는 공동구매와 Eureka의 local/docker 설정 파일·필수 식별값을 읽어 확인한다. Eureka HTTP 호출은 하지 않아 역방향 실행 의존성을 만들지 않는다.

## 버전·의존성과 변경 예정 파일

기존 Boot·Cloud BOM·Java·Gradle 버전을 유지하고 Cloud BOM으로 Eureka 의존성을 관리한다. 공식 Netflix 문서는 현재 5.0.3을 안내하지만 실제 적용 버전은 기존 BOM의 의존성 해석과 실행으로 확인해 결과에 기록한다. 개별 버전 강제 지정이나 Cloud 호환성 검사 비활성화로 문제를 우회하지 않는다.

- 서버: `spring-cloud-starter-netflix-eureka-server`, Config Client, Actuator와 필수 설정 검증. `@EnableEurekaServer`로 등록소 역할을 활성화한다.
- 공동구매: `spring-cloud-starter-netflix-eureka-client`를 추가한다. 기존 Config Client와 웹·설정 검증을 유지한다.
- 실제 선택된 Eureka HTTP 전송 구현과 그 제한시간 설정을 확인한다. 연결 1초·응답 5초를 초기 제안으로 두고, 서버 미응답 시 과도하게 기동·종료를 지연시키지 않는지 검증한다. 정확한 키·단위·의존성은 구현 결과에 기록한다.

| 파일·경로 | 변경 내용 |
|---|---|
| `settings.gradle` | `infrastructure/eureka-server/`를 `:eureka-server`로 등록 |
| `infrastructure/eureka-server/build.gradle`, `src/main/`, `src/test/` | 독립 실행 서버·Config 수신·필수 설정/프로필 검증·헬스·테스트 |
| `infrastructure/eureka-server/Dockerfile` | 기존 Java 21 이미지 기준의 비root 실행·curl 헬스 검사 |
| `config-repo/eureka-server.yml`, `eureka-server-local.yml`, `eureka-server-docker.yml` | 서버 일반 설정·환경 식별·단독 실행 설정 |
| `config-repo/group-purchase*.yml` | 클라이언트 등록·조회·환경별 등록소 주소·광고 주소·헬스 정책 |
| `infrastructure/config-server/src/main/`, `src/test/` | 새 서비스의 설정 준비 상태 검사와 누락·오타 회귀 검증 |
| `services/group-purchase-service/build.gradle`, `src/test/` | Eureka Client와 등록·발견·복구 검증, 기존 Config 테스트 격리 |
| 기존 두 모듈의 `Dockerfile` | 새 멀티 모듈이 이미지 빌드 컨텍스트에도 포함되도록 갱신하고 기존 이미지 빌드 회귀 확인 |
| `compose.yml`, `.github/workflows/ci.yml` | 세 서비스 기동·포트·준비 상태·등록 및 등록 주소 도달성 확인 |
| `README.md`, `Docs/handoff.md`, 문서 안내·로드맵 | 실행 방법·진행 상태·다음 단계 갱신 |
| `Docs/implementation/003-eureka-server.md` | 구현 후 실제 변경·버전·검증 결과·직접 테스트·한계 기록 |

테스트 fixture나 스크립트는 필요한 범위에서 추가한다. 운영 JAR 사이에 테스트용 서버 의존성을 넣거나 설정 검증을 위한 공통 라이브러리를 미리 만들지 않는다.

## 설정 소유권과 등록 정보

| 구분 | 제안 |
|---|---|
| 최초 접속 값 | 앱 이름·프로필·Config 주소는 각 앱의 시작 설정/환경변수. Config를 Eureka에서 찾는 discovery-first 방식은 사용하지 않음 |
| 서버 일반 설정 | `eureka-server*.yml`의 포트·단독 실행·환경 식별. `register-with-eureka=false`, `fetch-registry=false` |
| 클라이언트 일반 설정 | `group-purchase*.yml`의 등록/조회 활성화·등록소 주소·주소 광고·헬스 정책. 향후 모든 서비스에 강제될 공통 YAML에는 먼저 넣지 않음 |
| local 주소 | Config `http://localhost:8888`, Eureka `http://localhost:8761/eureka/`, 공동구매 등록 주소 `localhost:8080` |
| docker 주소 | Config `http://config-server:8888`, Eureka `http://eureka-server:8761/eureka/`, 공동구매 등록 주소 `group-purchase-service:8080` |
| 서비스·인스턴스 이름 | 서비스 ID는 `group-purchase`. REST 등록 목록의 앱 이름 `GROUP-PURCHASE`와 구분. 인스턴스 ID는 환경·프로세스/호스트·포트 등을 조합해 충돌 방지 |
| 진단·노출 | 호스트 8761도 `127.0.0.1`에만 공개. 기존 8080·8888 경계 유지. 관리용 Eureka 대시보드와 `/eureka/**`는 로컬에서 확인 |

Eureka 주소의 `service-url.defaultZone`에서 `defaultZone`은 대소문자를 유지한다. local과 docker를 섞은 실행은 이번 기본 절차에서 지원 범위로 잡지 않는다. 등록된 Docker 내부 주소를 호스트 브라우저에서 열 수 있다는 가정도 하지 않는다.

새 Eureka 앱은 `app.configuration.service=eureka-server`, 환경별 `environment`, `revision=003-v1`을 받아 값 누락·서비스/프로필 불일치 시 기동을 거절한다. 기존 공동구매 식별값 `002-v1`은 등록 기능 추가만을 이유로 바꾸지 않는다.

## 헬스·장애·복구 정책

앱 실행, 서버의 등록 목록, 클라이언트가 받은 발견 목록을 나누어 판정한다. `/actuator/health=UP`만으로 등록 완료를 판단하지 않는다.

- 공동구매는 Eureka 최초 등록 강제 옵션을 켜지 않는다. Config는 정상이고 Eureka만 꺼진 상태에서도 프로세스가 기동한 뒤 등록·조회 재시도를 지속하는지 확인한다. 이 동작은 [공식 초기 등록 설정](https://docs.spring.io/spring-cloud-netflix/reference/configprops.html)의 기본 정책을 기준으로 실제 검증한다.
- 프로세스 확인용 헬스가 등록소 조회 실패에 종속되지 않게 discovery health/composite contributor를 분리한다. 필요한 비활성화 키는 `spring.cloud.discovery.client.health-indicator.enabled=false`, `spring.cloud.discovery.client.composite-indicator.enabled=false`를 기준으로 실제 contributor를 확인한다. 발견 기능 자체를 끄지 않는다. [Commons 헬스 구성](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html#_health_indicators)
- 공동구매의 Actuator 상태를 Eureka에 반영하도록 `eureka.client.healthcheck.enabled=true`를 검토안에 포함한다. health/info 경로를 실제 노출 경로와 맞추고, 설정은 Config Data로 읽는 일반 YAML에 둔다.
- 하트비트·목록 갱신·응답 캐시의 기본 주기를 유지한다. 상태 수렴은 즉시 일어나지 않으므로 실제 조건을 반복 확인한다. 자동 테스트에서만 임시 주기를 줄일 수 있으며 정상 local/docker 설정과 구분해 기록한다.
- 자기 보호 모드는 기본 활성 상태로 유지한다. 하트비트가 줄어든 상황에서 등록 정보를 보존하는 동작 때문에 강제 종료된 인스턴스가 남을 수 있다. 정상 종료의 등록 해제와 구분하며, 강제 종료 후 일정 시간 안에 무조건 제거된다는 완료 기준을 두지 않는다. [Netflix 자기 보호 설명](https://github.com/Netflix/eureka/wiki/Understanding-Eureka-Peer-to-Peer-Communication)
- Eureka 중단·복구 시 기존 앱을 재시작하지 않고 재등록·목록 갱신을 확인한다. Config 중단 중 이미 받은 설정은 유지하지만 Eureka를 새로 시작하려면 Config가 필요하다.

등록·재등록·정상 종료 후 목록 반영은 각 동작의 준비 완료 시점부터 최대 180초를 초기 검증 한도로 제안한다. 2초 간격 조건 확인으로 판정하고, 초과하면 로그·실제 주기·등록/캐시 상태를 남겨 원인을 확인한다. 이 수치는 측정된 복구 보장이나 운영 SLA가 아니다.

## 검증 계획과 완료 기준

아래는 합의한 검증 기준이다. 실제 실행 결과·관측 범위와 미검증 항목은 [003 결과](../003-eureka-server.md)에 구분해 기록했다.

| 시나리오 | 완료 기준 |
|---|---|
| 전체·선택 Gradle 빌드 | 기존 테스트 회귀 없이 세 모듈의 실행 JAR 생성, BOM이 선택한 실제 Eureka 버전과 HTTP 전송 기록 |
| Eureka의 Config 수신 | local/docker 서버 설정 실제 반영, Config 불가·필수 값 누락·프로필 오타·환경 불일치 시 기동 실패 |
| Config 준비 상태 | 두 서비스의 필수 설정 확인. Eureka 설정 누락도 DOWN으로 검출하며 실행 중 Eureka 자체를 조회하지 않음 |
| 정상 등록·조회 | REST 목록의 `GROUP-PURCHASE`에 의도한 인스턴스가 UP으로 나타나고 주소·포트·식별자가 맞음 |
| 실제 클라이언트 발견 | Config·Eureka·앱을 테스트가 제어하고 앱의 실제 `DiscoveryClient.getInstances("group-purchase")`가 수렴 후 해당 인스턴스를 반환. 서버 목록 조회로 대체하지 않음 |
| 등록 주소 도달성 | local은 호스트, docker는 Compose 네트워크의 다른 컨테이너에서 등록 주소의 `/actuator/health`를 호출해 UP 확인. 하드코딩 주소 호출만으로 통과 처리하지 않음 |
| 정상 종료·재기동 | 앱을 정상 종료하면 해당 인스턴스가 등록/발견 목록에서 사라짐. 재기동 후 정상 주소로 재등록되며 불필요한 중복 없음 |
| Eureka가 없는 최초 기동 | Config는 켜 둔 채 Eureka만 중단. 앱 기동·헬스는 유지되고 등록 실패가 관측되며 Eureka 복구 후 앱 재시작 없이 등록·발견 |
| 실행 중 Eureka 장애·재시작 | 앱 실행 유지와 등록소 접근 실패를 구분. 빈 등록소로 시작한 서버에 앱이 재등록하고 실제 클라이언트 목록도 갱신 |
| 강제 종료와 자기 보호 | 하트비트 중단·잔존 등록·자기 보호 상태를 관측. 강제 종료와 정상 등록 해제를 같은 시간 조건으로 판정하지 않음 |
| Config 장애 회귀 | 기존 앱/Eureka는 받은 설정 유지, Config 중단 중 Eureka 신규 기동은 실패, 복구 후 기동·등록 정상 |
| 이미지·Compose·CI | 세 이미지 빌드·정의 검증·healthy 이후 별도 등록/도달성 검사 성공. 실패 시 로그·목록 수집과 컨테이너 정리 |
| 노출 경계·결과 기록 | 호스트 로컬 바인딩 유지, 비밀값/환경 전체 노출 없음, 실제 실행 결과·직접 테스트·미검증 범위 기록 |

일반 Gradle 검증은 미리 실행한 서비스나 Docker에 의존하지 않는다. 기존 Config 전용 테스트에서는 Eureka를 테스트 설정으로 명시적으로 끄고, 등록 통합 테스트는 실제 임시 Config·Eureka와 앱의 생명주기를 제어한다. 서버 starter의 자동 구성이 클라이언트 테스트에 섞이면 별도 테스트 실행 경로나 프로세스로 격리한다. 고정 sleep·등록 성공 mock만으로 복구를 검증하지 않는다.

CI의 기존 빌드·테스트 job에 새 모듈 검증을 포함하고 Compose job을 세 서비스로 확장한다. healthy 대기와 등록 완료 대기는 분리한다. 이번 단계에서 외부 이미지 게시·배포·PR 생성/병합은 진행 범위로 추가하지 않는다.

## 사용자 직접 테스트 안내에 포함할 절차

다음은 계획 당시 작성한 절차 초안이다. 구현 후 확정한 실행·기대값·검증 상태는 [003 직접 테스트](../003-eureka-server.md#manual-tests)를 따른다.

준비: 프로젝트 루트 `C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase`, Docker Desktop Linux 엔진과 Compose, 비어 있는 8080·8761·8888 포트. 호스트 Gradle 실행에는 JDK 21과 `JAVA_HOME`이 필요하다.

```powershell
# 구현 후: 정상 일괄 기동과 진단
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 180
docker compose ps
Invoke-RestMethod http://localhost:8761/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/info
Invoke-RestMethod -Uri http://localhost:8761/eureka/apps/GROUP-PURCHASE -Headers @{Accept='application/json'} | ConvertTo-Json -Depth 10
```

기대 결과는 세 서비스 healthy와 각 헬스 UP, 공동구매 기존 설정 식별값 유지, 제한시간 내 UP 등록이다. 최초 등록 전에는 조회가 404일 수 있으므로 결과 문서에 제한시간이 있는 반복 조회 명령을 제공한다. 응답에 실린 host/port로 Compose 내부에서 요청하는 방법과 `http://localhost:8761/` 대시보드 확인도 포함한다.

```powershell
# 정상 종료·재기동: 각 조작 후 등록 목록을 반복 조회
docker compose stop -t 30 group-purchase-service
docker compose up -d --no-deps group-purchase-service

# Eureka 장애·복구: 기존 공동구매 앱은 재시작하지 않음
docker compose stop -t 30 eureka-server
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose up -d --no-deps eureka-server

# Eureka가 없는 최초 기동: Config는 계속 실행
docker compose stop -t 30 eureka-server group-purchase-service
docker compose up -d --no-deps group-purchase-service
# 앱의 기동·등록 실패 로그를 확인한 뒤 서버를 복구
docker compose up -d --no-deps eureka-server

# 종료: 이 Compose 프로젝트의 컨테이너·네트워크만 정리
docker compose down
```

결과 안내는 위 세 시나리오를 독립 절차로 나누고, 조작 사이에 수행할 제한시간 반복 확인·로그 명령을 넣는다. `--no-deps`는 시작 순서를 의도적으로 우회하는 장애 시험에서만 사용한다. Config 중단 시험·잘못된 프로필·강제 종료는 별도 절차로 제공하고, 변경한 환경변수·임시 파일 원복 및 정상 Compose 재기동 방법을 함께 기록한다.

호스트 실행은 세 터미널에서 `:config-server:bootRun` → `:eureka-server:bootRun` → `:group-purchase-service:bootRun` 순서다. 선택 테스트 명령은 `.\gradlew.bat :eureka-server:test :group-purchase-service:test`, 전체 회귀 명령은 `.\gradlew.bat build`를 기본으로 실제 테스트 태스크에 맞춰 안내한다. 종료는 각 터미널 Ctrl+C다.

## 계획 검토 결과와 남은 판단

2026-11-02 기존 소스·Compose·CI·공식 문서와 대조해 다음을 계획에 반영했다.

- **시작 순환:** Config의 Eureka 등록/탐색을 제외하고, 저장소 헬스는 파일 검증만 수행하도록 명시했다.
- **거짓 성공 판정:** healthy·서버 등록·클라이언트 발견·등록 주소 도달성을 각각 완료 기준으로 두었다.
- **장애 전파:** Eureka 최초 접속 실패 시 앱 유지·재시도를 제안하고, Config 필수 수신 정책과 구분했다.
- **종료·캐시 오해:** 정상 종료와 강제 종료를 나누고 자기 보호를 끈 결과를 기본 복구 검증으로 사용하지 않도록 했다.
- **기존 구성 회귀:** Config 테스트 격리와 기존 Dockerfile의 멀티 모듈 COPY 범위를 변경 목록에 포함했다.

앞의 세 가지 추천안에 사용자 동의·구현 요청을 받았다. 실제 Eureka 버전·HTTP 전송·헬스 contributor와 관측 결과는 구현 기록에 남겼다. 단일 등록소 장애, 캐시된 오래된 주소, local/docker 혼합 접속은 이번 범위의 한계다. Gateway 단계에서는 발견 목록의 대상에 대한 실제 요청 실패·제한시간·라우팅을 추가 검증한다.

합의한 단일 등록소·캐시·장애 정책의 재검토 조건과 대안/비용은 [설계 재검토 목록 R-23](../../decisions/design-review.md#r-23-단일-eureka와-등록발견-복구)에 반영했다. 테스트에서는 서버 starter의 classpath 간섭을 피하도록 서버 JAR를 별도 Java 프로세스로 실행했다. 일반 실행의 기본 주기·자기 보호·Config 필수 수신 정책은 유지했다.

[구현 계획 목록](../README.md) · [프로젝트 인계](../../handoff.md)
