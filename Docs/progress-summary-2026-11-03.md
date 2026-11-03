# 공동구매 프로젝트 전체 진행 정리

기준일: 2026-11-03 KST

이 문서는 프로젝트 시작부터 현재까지의 목표·설계 결정·구현·검증·남은 작업을 모은 시점 기록이다. 저장소 파일, 단계별 결과, 최근 작업 대화와 GitHub PR·CI를 대조했다. 정책의 상세 기준은 원문 설계를, 이후 최신 작업 상태는 [프로젝트 인계](handoff.md)를 따른다.

## 1. 현재 위치

**설계 기준을 정리하고 001~004 공통 기반의 구현·검증을 완료했다. 다음 작업은 005 PostgreSQL 실행 기반이다.**

| 구분 | 2026-11-03 확인 결과 |
|---|---|
| 저장소 | [y0y00700/group-purchase](https://github.com/y0y00700/group-purchase) |
| 로컬 브랜치·HEAD | `feat/api-gateway` · `b94dcd4` |
| 원격 main | `a2a88dc` · Eureka PR #3 병합 커밋 |
| 현재 PR | [#4 API Gateway](https://github.com/y0y00700/group-purchase/pull/4) OPEN·미병합 |
| PR #4 최신 CI | head `b94dcd4`의 [CI #41437130117](https://github.com/y0y00700/group-purchase/actions/runs/41437130117): Build and test·Images and Compose smoke test 모두 성공 |
| main 최신 CI | `a2a88dc`의 [CI #41402512856](https://github.com/y0y00700/group-purchase/actions/runs/41402512856): 두 검사 모두 성공 |
| 구현된 실행 모듈 | Config Server·Eureka Server·API Gateway·공동구매 초기 앱, 총 4개 |
| 자동 테스트 | 004 최종 검증 기록 기준 28개 통과. 이번 문서 작성으로 Gradle 테스트를 재실행한 것은 아님 |
| 005 상태 | 상세 계획 작성 완료·검토 대기·코드 구현 미착수 |
| 로컬 실행 | 최초 조회에서는 4개 모두 종료 코드 255로 중지. 재기동 후 4개 모두 running·healthy, 등록·주소 도달성·Gateway 검사 PASS. 종료 원인은 별도 미확정 |

회원·공동구매 업무·결제·알림 API, DB, Kafka, Saga와 전체 부하 검증은 아직 구현하지 않았다. 현재 확인용 ping은 실제 구매 기능의 완료를 의미하지 않는다.

## 2. 프로젝트 목표와 범위

판매자가 상품과 기준 수량을 게시하고 구매자를 모집한 뒤, 기한 안의 유효 결제가 기준 수량을 채우면 구매를 확정하는 **목표 달성형 공동구매** 서비스다. 최종 마감에 미달이면 무산 처리하고 결제 완료분을 환불한다.

취업용 개인 프로젝트로 다음 과정을 근거와 함께 남기는 것이 목표다.

1. 작은 기능 단위로 설계·구현한다.
2. 정합성·동시성·장애·복구를 검증한다.
3. 부하와 관측 결과로 문제를 확인한다.
4. 대안·비용을 비교해 설계를 유지하거나 변경한다.
5. 같은 조건에서 다시 검증하고 결과를 기록한다.

초기에는 API·테스트 클라이언트, PG Mock, 알림 테스트 대역으로 검증한다. 실제 결제 업체·실제 알림 채널, 사용자 화면·운영 화면의 확장은 필요가 생길 때 별도로 정한다.

근거: [프로젝트 목표](README.md#project-goal), [검증 계획](design/verification.md), [설계 재검토 목록](decisions/design-review.md).

## 3. 합의한 업무 규칙

아래는 설계 합의이며, 해당 업무 코드와 검증의 완료 표시는 아니다.

| 주제 | 합의 내용 |
|---|---|
| 판매 조건 | 기준 수량은 최소 10개인 정수. 목표금액은 기준 수량 × 상품 단가 |
| 최초 모집 | 기준 수량의 120%를 올림해 모집. 기준 11개이면 14개 모집 |
| 구매자별 한도 | 같은 공동구매의 유효 참여금액 합계가 목표금액의 10% 이하. 여러 참여 건도 합산 |
| 결제 회차 | 공동구매 서비스가 회차 공통 개시 시각을 저장. 기한은 개시 후 168시간과 최종 마감 중 빠른 시각 |
| 알림과 기한 | 알림의 지연·실패·재시도·읽음 여부로 개시 시각이나 기한을 변경하지 않음 |
| 구매 확정 | 취소되지 않은 유효 결제 수량이 기준 수량에 정확히 도달하면 즉시 확정. 남은 미결제 확정 참여는 취소 |
| 초과 결제 | 기존 결제 수량에 참여 한 건 전체 수량을 더해 기준을 넘으면 해당 건 전체 거절. 일부 수량만 자동 결제하지 않음 |
| 승인 시각 | 유효한 기한 내 접수와 실제 승인 완료 시각으로 판단. 기한과 같거나 이후의 승인 성공은 전액 환불 |
| 결과 불명 | 통신 실패나 마감만으로 미승인을 단정하지 않음. 기존 시도와 수량 확보를 추적하며 실제 결과 확인 |
| 취소 | 구매 확정 전까지 참여 한 건 전체 취소 허용. 결제했다면 전액 환불. 구매 확정 후 구매자 취소 불가 |
| 취소·확정 경합 | 공동구매 서비스의 원자적 반영 순서로 판단. 취소가 먼저면 뒤늦은 승인도 성립 대상에서 제외하고 환불 |
| 재모집·무산 | 결과 확인 후 미달이고 최종 마감이 남으면 부족분 재모집. 최종 마감 기준 미달이면 전체 무산·환불 |
| 상품 수정 | 초안에서 판매자만 수정. 최초 게시 후 상품명·설명·단가·기준 수량·최종 마감 고정 |
| 재참여 | 모집 중이고 잔여 수량이 있으면 새 참여 건으로 신청. 이전 취소·환불 이력 보존 |

공동구매는 초안 → 모집 → 결제 대기 → 필요한 마감 결과 확인 → 구매 확정 또는 재모집·만료로 진행한다. 참여 업무 상태와 개별 결제 시도·환불 진행은 구분한다. 정확한 테이블·enum·API 필드는 해당 기능의 구현 계획에서 결정한다.

상세 기준: [업무 규칙](design/business-rules.md), [결제·메시지 흐름](design/payment-and-messaging.md).

## 4. 서비스 경계와 기술 결정

### 업무 서비스의 책임

| 서비스 | 소유 책임 | 구현 상태 |
|---|---|---|
| 회원 | 회원·인증·역할·계정 상태·세션과 내부 인증 확인 | 설계 합의·미구현 |
| 공동구매 | 상품 정보·공동구매·참여·수량·기한·성사 판정·Saga 조정 | 초기 앱·설정·등록·ping만 구현 |
| 결제 | PG 승인·환불 실행·결과 조회·결과 불명 복구 | 설계 합의·미구현 |
| 알림 | 안내 구성·전달·재시도·전달 결과 | 설계 합의·미구현 |

상품·참여를 별도 서비스로 분리하지 않는다. 서비스는 자신이 소유한 데이터를 변경하고 다른 서비스 DB에 직접 접근하지 않는다. 업무 엔티티·로직을 공용 모듈로 공유하지 않는 방향이다.

### 합의한 구현 방향

| 영역 | 결정과 현재 적용 범위 |
|---|---|
| 빌드 | Java 21·Gradle Wrapper·Groovy DSL·단일 저장소 멀티 프로젝트 적용 완료 |
| 설정 | `application.yml`, Config native 파일 백엔드, local/docker 프로필 적용 완료. 비밀값은 별도 주입 원칙 |
| 실행 | 루트 `compose.yml`에 완성한 서비스의 이미지·헬스체크·기동 조건을 누적 |
| 발견 | 단일 Eureka, Config 최초 접속은 고정 주소. 앱 등록·조회 재시도와 정상 종료 등록 해제 구현 |
| 외부 진입 | Gateway의 명시적 공개 경로만 전달. 보호된 업무 경로의 인증은 후속 구현 |
| DB | PostgreSQL 단일 서버 안에 서비스별 DB·계정. 005에서 처음 구현 예정 |
| 인증 | 세션·쿠키·Spring Security·Spring Session JDBC. 회원 DB에 세션 저장, 변경 요청 CSRF 검증. 미구현 |
| 내부 신뢰 | Gateway가 회원 내부 API로 인증 확인, 외부 신원 헤더 제거, 서비스별 호출자 검증. 미구현 |
| 경합 | 같은 공동구매 행의 비관적 잠금과 로컬 트랜잭션을 초기 기준으로 합의. 미구현 |
| 요청 멱등성 | 사용자·작업·키, 요청 내용·접수 결과를 업무 및 필요한 Outbox와 같은 트랜잭션에 저장. 미구현 |
| 분산 업무 | 공동구매가 조정하는 Saga와 보상, 승인 전 수량 확보, 미완료 상태 영속화·복구. 미구현 |
| 메시지 | Kafka 단일 브로커, JSON 계약, 서비스 내부 Outbox 폴링·소비 기록·중복 방지. 미구현 |
| 실패 메시지 | 한도 재시도·DLT 보관·실패 파티션 보류·운영 재처리/재개. 미구현 |
| 운영 복구 | 실행 ID·DB 시간 기준 기한·조건부 갱신·복구 회차별 한도와 이력. 미구현 |
| 관측 | Actuator 기본 health/info는 적용. JSON 로그·Prometheus·Grafana·Alertmanager·운영 수신기는 후속 |
| 검증 | 실제 PostgreSQL/Kafka, PG Mock 거래와 DB 대사, k6 검증은 후속 |

Redis 세션, CDC, 별도 복구 프로세스, 다중 DB 서버·Kafka 브로커 등은 관측 후 비교할 대안이다. 현재 도입이 확정된 기능으로 취급하지 않는다. R-01~R-23에 잠금·집계·메시지·인증·저장소·관측·부하·Eureka의 재검토 조건과 비용을 기록했다.

상세 기준: [아키텍처](design/architecture.md), [실행·관측](design/operations.md), [재검토 목록](decisions/design-review.md).

## 5. 단계별로 완료한 작업

| 단계 | 수행한 내용 | 검증·원격 반영 | 결과 문서 |
|---|---|---|---|
| 000 · 2026-10-28 | Java 21·Boot 4.0.9 단일 앱, Maven Wrapper, 초기 설계 문서 구조·생성 범위 결정 | Maven verify 성공·기본 테스트 1개 | [000 결과](implementation/000-initial-project.md) |
| 문서 정리 · 2026-10-29 | 주제별 설계 통합, 상대 링크 정리, 계획/결과 분리와 로드맵 마련 | Git 이력 `4e9bb28` | [문서 안내](README.md) |
| 001 · 2026-10-29 | Gradle 9.8.2 멀티 프로젝트, 공동구매 모듈 이동, YAML·BOM·Wrapper 체크섬·Gradle CI 전환 | 전체/선택 빌드·bootRun·JAR·CI 성공. [PR #1](https://github.com/y0y00700/group-purchase/pull/1) 병합 | [001 결과](implementation/001-multi-module-build.md) |
| 002 | Config Server, native YAML 저장소·필수 값/환경 검증·첫 HTTP 클라이언트·이미지/Compose | 당시 총 13개 테스트와 정상/누락/장애·Compose 검증. [PR #2](https://github.com/y0y00700/group-purchase/pull/2) 2026-11-02 병합 | [002 결과](implementation/002-config-server.md) |
| 003 · 2026-11-02 | Eureka Server·공동구매 등록/발견·local/docker 주소·장애 후 재등록·검사 스크립트 | 당시 총 23개 테스트·Compose·장애/복구·Windows JAR·최종 PR/main CI 성공. [PR #3](https://github.com/y0y00700/group-purchase/pull/3) 병합 | [003 결과](implementation/003-eureka-server.md) |
| 004 · 2026-11-02 | Gateway 모듈·발견 기반 GET ping 전달·비공개 경로 차단·오류 구분·별도 관리 포트 | 총 28개 테스트·네 이미지/Compose·Windows JAR·대상/Eureka 복구·최신 CI 성공. PR #4 OPEN | [004 결과](implementation/004-api-gateway.md) |
| 005 계획 · 2026-11-02 | PostgreSQL·서비스별 DB/계정·JDBC/Flyway·secret·권한/데이터 유지/장애 검증 계획 | 문서만 작성, 구현·DB 검증 미착수 | [005 계획](implementation/plan/005-postgresql-foundation.md) |

000의 Maven과 초기 단일 앱 설명은 당시 기록이다. 현재 빌드 방식과 실행 모듈은 001~004를 기준으로 한다. 테스트 수는 각 단계 당시의 누적 수치이며 모두 더하는 값이 아니다.

## 6. 현재 구현의 실제 요청 흐름

```text
일반 설정: config-repo/*.yml → Config Server :8888
                              ├→ Eureka Server :8761
                              ├→ 공동구매 앱 :8080
                              └→ API Gateway :8000 / 관리 :9000

서비스 등록: 공동구매 앱 → Eureka
서비스 발견: Gateway → Eureka
확인 요청: 클라이언트 → Gateway :8000
           GET /api/group-purchases/ping
           → lb://group-purchase → 공동구매 앱 :8080
           ← 200 {"service":"group-purchase","message":"pong"}
```

- Config 설정은 클라이언트 재시작으로 반영한다. Config 접속은 Eureka 조회에 의존하지 않는다.
- Config 필수 설정의 누락·서비스/환경 불일치는 기동 실패로 처리한다.
- Eureka가 없어도 공동구매 앱·Gateway가 기동하고 등록·조회 재시도로 회복하도록 구현했다.
- Gateway는 자신을 등록하지 않고 목록만 조회한다. 자동 서비스 경로 생성은 껐다.
- 8000에서는 명시한 GET ping만 공개한다. 내부·관리 경로와 비공개 메서드는 404다.
- 대상 없음 503, 연결 실패 502, 전송 제한시간 초과 504를 구분한다. 대상의 실제 HTTP 오류 응답은 보존한다.
- Docker의 Gateway 관리 9000은 호스트에 게시하지 않는다. 현재 서비스 포트는 호스트 loopback에만 게시한다.
- local 등록 주소는 `localhost:8080`, docker는 `group-purchase-service:8080`이다. Windows 앱과 Docker 앱을 섞어 실행할 때 등록 주소의 도달성을 확인해야 한다.

현재 해석·검증 기록의 버전은 Boot 4.0.9, Cloud BOM 2025.1.4, Config 5.0.6, Netflix 5.0.3, Gateway 5.0.4, Gradle 9.8.2다. 최신 버전이라는 의미는 아니다.

## 7. 확인한 문제와 유지할 검증 원칙

| 관측한 문제·제약 | 대응과 남은 해석 |
|---|---|
| Eureka CI 2건 실패 | 2026-11-02 15:32·15:34 KST 시작한 CI에서 기존 앱의 발견 캐시 수렴을 기다리지 않아 실패. `2914c1d`에서 등록소·새 관찰 클라이언트·기존 앱 캐시를 각각 기다리도록 수정. 이후 성공한 head와 과거 실패 알림을 구분 |
| Windows 테스트 출력 파일 잠금 | 테스트 Eureka를 별도 Java 프로세스로 유지하고 출력은 메모리 수집. 프로세스·읽기 스레드 종료 보완 |
| 이미지 병렬 빌드 중 발견 테스트 시간 초과 | 자원 경합이 있는 실행에서 45초 내 수렴 실패를 기록. 이미지 빌드 종료 후 전체 빌드 성공. 실패 이력을 삭제하지 않음 |
| Eureka 강제 종료 후 오래된 UP 등록 | 110초 후 잔존을 관측. 이것만으로 자기 보호 발동이나 정확한 만료 시간을 단정하지 않음 |
| 등록 UP과 실제 도달성의 차이 | Eureka 목록, 앱 health, 등록 주소의 실제 HTTP 호출을 별도로 검사 |
| 호스트·Docker 주소 혼용 | Windows에서 Docker DNS 등록 주소를 해석할 수 없는 경계 확인. 환경별 주소·프로필을 맞춤 |
| GitHub 연결 도구의 저장소 404 | 이번에도 연결 도구 조회 실패. 인증된 `gh` CLI로 PR·main·CI의 최신 상태를 확인 |
| 현재 컨테이너 중지 | 최초 조회에서 네 개 모두 종료 코드 255. 코드 결함이나 Docker 종료 원인으로 단정하지 않고 재기동·회귀 검사를 첫 스텝으로 수행 |

CI 실패 링크: [#41400600606](https://github.com/y0y00700/group-purchase/actions/runs/41400600606), [#41400760140](https://github.com/y0y00700/group-purchase/actions/runs/41400760140). 상세 원인과 재검증은 [003 결과](implementation/003-eureka-server.md), [004 결과](implementation/004-api-gateway.md)를 따른다.

부하 테스트의 2건/초, 준비 2분·측정 5분, 접수 p95 500ms 미만·예상 밖 오류 1% 미만·정상 결제 최종 반영 p95 120초 이내는 **측정 전 초기 목표**다. 실제 성능 결과는 아직 없다.

## 8. 작업 파일과 문서의 현재 상태

이번 정리 시작 전부터 다음 로컬 미커밋 변경이 있었다. 해당 내용을 보존했다.

- 변경: `README.md`, `Docs/README.md`, `Docs/handoff.md`, `Docs/implementation/README.md`, `Docs/implementation/004-api-gateway.md`, `Docs/implementation/plan/roadmap.md`.
- 신규: `Docs/implementation/plan/005-postgresql-foundation.md`.
- 기존 변경의 내용은 주로 005 계획 연결과 004의 최종 CI·인계 갱신이다. 이 내용은 아직 원격 PR #4에 포함되지 않았다.

이번 정리 파일도 로컬 문서다. 커밋·push·PR 병합은 수행하지 않았다. 브랜치나 이력을 바꾸기 전에 이 문서 변경을 보존해야 한다.

| 위치 | 용도 |
|---|---|
| [Docs/README.md](README.md) | 문서·설계의 시작점 |
| [Docs/handoff.md](handoff.md) | 최신 브랜치·PR·검증·다음 작업 |
| [Docs/implementation/README.md](implementation/README.md) | 단계별 계획·결과와 진행 규칙 |
| [Docs/implementation/plan/roadmap.md](implementation/plan/roadmap.md) | 전체 구현 순서 |
| `Docs/explanations/` | Config·Eureka·Gateway 역할 학습 노트. 기존 Git 제외 유지 |
| `Docs/process/` | 로컬 협업 원칙·문서 이동 기록. 기존 Git 제외 유지 |
| [Docs/artifacts/README.md](artifacts/README.md) | ERD·시스템 아키텍처·인프라 산출물 기준. 실제 도식은 작성 대기 |
| `.tmp/` | 빌드 캐시·검증 로그·실행 스크립트 등 로컬 자료 |

## 9. 정리 다음의 첫 스텝: 기존 구성 회귀 확인

005에서 DB 의존성을 추가하기 전, 현재 네 서비스가 정상적으로 이어지는지 기준 상태를 확인했다. 중지된 컨테이너를 재기동한 뒤 정상 회귀 검사를 완료했다. 이 작업은 005의 DB 코드 구현과 구분한다.

### 직접 실행 방법

준비: Docker Desktop Linux 엔진, Python 3, PowerShell. 8000·8080·8761·8888 포트를 확보한다. 기존 이미지가 없거나 소스 변경을 반영해야 하면 기동 명령에 `--build`를 추가한다. 전체 빌드에는 JDK 21과 `JAVA_HOME`이 별도로 필요하다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
docker compose config --quiet
docker compose up -d --wait --wait-timeout 180
docker compose ps
Invoke-RestMethod http://localhost:8888/actuator/health
Invoke-RestMethod http://localhost:8761/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health
python scripts/check-discovery.py --timeout 90
python scripts/check-gateway.py --timeout 90
Invoke-RestMethod http://localhost:8000/api/group-purchases/ping | ConvertTo-Json
```

정상 판정: 네 컨테이너 모두 healthy, 세 health 응답 `UP`, 등록·실제 주소 호출과 Gateway 검사 `PASS`, ping HTTP 200과 `message=pong`. Gateway 검사에는 공개 ping 전달과 비공개 경로·메서드 404 확인이 포함된다.

실패 시 `docker compose ps --all`과 `docker compose logs --tail=100`으로 기동 상태를 확인한다. 등록이 아직 수렴하지 않았다면 검사 스크립트가 제한시간 안에서 기다리며, 시간 초과는 성공으로 처리하지 않는다. 대상 중단 시 503 수렴·복구 등 장애 재현은 [004 직접 테스트](implementation/004-api-gateway.md#manual-tests)의 절차를 따른다.

종료는 `docker compose down`, 재기동은 위 `up` 명령이다. 이번 작업 후 네 컨테이너는 실행 상태로 두었다. 004의 전체 Gradle·Windows JAR·장애 주입 검증은 과거 결과이며 이번 정상 회귀 확인과 구분한다.

### 이번에 실제 실행한 결과

| 명령·확인 | 2026-11-03 결과 |
|---|---|
| `docker compose config --quiet` | 종료 코드 0, Compose 정의 정상 |
| `docker compose up -d --wait --wait-timeout 180` | 종료 코드 0, 기존 이미지로 네 서비스 재기동·healthy 대기 완료 |
| `python scripts/check-discovery.py --timeout 90` | PASS. 단일 `group-purchase:docker:…:8080` UP 등록과 Config 컨테이너에서 등록 health 주소 호출 성공 |
| `python scripts/check-gateway.py --timeout 90` | PASS. ping 200, 비공개 경로와 POST/PUT/DELETE 404 |
| `docker compose ps` | Config·Eureka·공동구매·Gateway 모두 running·healthy |
| 8888·8761·8080의 `/actuator/health` | 모두 `UP` |
| Gateway ping 직접 요청 | 200, `{"message":"pong","service":"group-purchase"}` |
| 문서 확인 | 상대 링크 존재 여부와 `git diff --check` 확인 |

이미지 재빌드, 전체 Gradle 28개 테스트 재실행, Windows JAR·대상/등록소 장애 주입, PostgreSQL 검증은 이번 스텝에서 수행하지 않았다.

## 10. 이어갈 005 PostgreSQL 단계

상세 계획: [005 PostgreSQL 실행 기반](implementation/plan/005-postgresql-foundation.md). 전체 단계 번호는 유지하고 내부 작업을 다음 순서로 나눈다. 이 분할은 진행 순서이며 각 스텝을 이미 완료했다는 의미는 아니다.

| 스텝 | 작업 | 완료 판단 |
|---|---|---|
| 1 · 기준 확인 · 완료 | 기존 네 서비스 재기동·회귀 확인, 로컬 문서 보존·PR/main/CI 상태 확인 | 2026-11-03 Gateway·발견·헬스 정상. 브랜치 전환·PR 병합 없이 현재 시작 기준 확인 |
| 2 · DB 실행·경계 · 다음 작업 | PostgreSQL·named volume·secret 준비, 회원/공동구매/결제/알림 DB·계정과 권한 초기화 | 자기 DB 접속 4쌍 성공, 타 업무 DB 교차 접속 12쌍 거절, 잘못된 비밀번호 거절 |
| 3 · 첫 앱 연결 | 공동구매에 JDBC·드라이버·Flyway, 환경별 URL·secret·`app` 스키마 연결 | 최초 migration·재기동 이력 정상, DB/비밀번호/migration 오류 시 기동 실패 |
| 4 · 유지·복구·회귀 | 볼륨 유지한 재생성, DB 중단/복구, 기존 Config/Eureka/Gateway와 Windows JAR 연동 | 데이터·계정·이력 유지, 동일 앱 프로세스에서 health DOWN→UP, 기존 기능 회귀 통과 |
| 5 · 결과·CI | 실제 PostgreSQL 테스트·Compose 검사·CI 정의 갱신, 재현 명령·결과·역할 설명 기록 | 전체 빌드와 직접 테스트 통과. PR 진행 시 최종 head 원격 CI도 별도 확인 |

구성 제안은 PostgreSQL 18.6, 단일 서버, `member`/`group_purchase`/`payment`/`notification` DB와 각각의 앱 계정이다. 이미지 태그·Flyway/드라이버 호환성은 구현 때 다시 확인한다. 초기화는 DB·계정·권한, Flyway는 서비스 소유 스키마 변경을 담당한다. 비밀번호는 Config 저장소에 넣지 않고 Git 제외 secret으로 주입한다.

PR #4가 열려 있으므로 구현용 후속 브랜치는 Gateway head를 기반으로 하고, 후속 PR을 만든다면 Gateway 브랜치를 base로 삼아 변경 중복을 피하는 방식을 사용할 수 있다. #4가 병합된 후에는 최신 main을 기준으로 다시 확인한다. 현재 PR을 자동 병합하지 않는다.

005에는 회원 인증·업무 테이블·JPA·Outbox·Kafka·HA·백업 복구를 포함하지 않는다. 현재 상세 계획은 검토 대기이며, 이번 첫 스텝의 기존 구성 점검을 005 코드 구현 완료로 표시하지 않는다.

## 11. 그 이후의 전체 진행 순서

| 순서 | 남은 작업 |
|---|---|
| 006 | API 공통 오류·요청 식별자·JSON 로그·헬스/기본 지표 기준 |
| 007~008 | 회원·세션·CSRF, Gateway 인증 확인·내부 호출 신뢰 |
| 009~010 | 공동구매·상품 초안/게시/조회, 참여·수량 한도·멱등성·결제 전 취소 |
| 011~013 | Kafka·메시지 계약, Outbox 발행, 소비 기록·실패 분류·DLT·보류 |
| 014~015 | PG Mock과 결제 승인·환불·결과 불명 복구 |
| 016~017 | 공동구매 Saga·수량 확보·취소/마감/재모집, 알림 연결 |
| 018~019 | 운영 복구 API·실행권/이력, 관측·운영 알림 전체 환경 |
| 020~021 | 전체 흐름·장애·k6 부하 검증, CI 확장·재현 문서 정리 |

위 숫자는 현재 로드맵의 순서다. 큰 기능을 나누면 이후 상세 계획 파일 번호는 달라질 수 있다. 매 단계는 계획 검토 → 요청한 범위 구현·검증 → 결과와 사용자 직접 테스트 기록 → 다음 단계 순서로 진행한다.
