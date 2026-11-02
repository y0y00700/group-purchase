# 002. Config Server와 첫 클라이언트 연동

작성일: 2026-10-30

상태: 구현·로컬 검증 완료 — 2026-10-30 사용자 진행 요청으로 착수·검증했다. 원격 GitHub Actions 실행은 미수행이다. [구현 결과와 사용자 직접 테스트](../002-config-server.md)를 따른다.

진행 합의 범위: 이 계획의 Config Server·첫 공동구매 클라이언트·이미지·Compose·CI 구성과 구현·검증·결과 기록. 사용자 직접 테스트 방법을 결과 문서에 포함한다. Eureka 이후 구현은 후속 단계다.

## 목표와 현재 상태

001에서 Java 21·Spring Boot 4.0.9·Gradle Wrapper 9.8.2의 멀티 프로젝트와 YAML 설정·CI 전환을 완료했다. 현재 실행 모듈은 `services/group-purchase-service/` 하나다. 웹 서버·업무 API·Spring Cloud·Docker Compose는 아직 없다. 기존 검증 근거는 [001 결과](../001-multi-module-build.md)를 따른다.

이번 목표는 Config Server가 같은 저장소의 일반 YAML 설정을 제공하고, 첫 클라이언트인 공동구매 앱이 환경에 맞는 설정을 받아 기동하도록 하는 것이다. 서버와 클라이언트를 각각 이미지로 만들고 루트 Compose 한 명령으로 함께 실행한다.

전체 순서는 [로드맵](roadmap.md), 서비스 경계는 [아키텍처](../../design/architecture.md#service-boundaries), 설정·비밀값·실행 기준은 [실행 환경](../../design/operations.md#local-runtime)을 따른다. 아래 세부 선택은 사용자 진행 요청에 따라 적용한다. 실제 검증 상태는 구현 결과에 기록한다.

## 먼저 검토할 세 가지

| 항목 | 추천안 | 대안과 비용 |
|---|---|---|
| 설정 저장·제공 | 같은 저장소의 `config-repo/`에 YAML을 두고 Config Server의 native 파일 시스템 백엔드로 제공. Compose에서는 읽기 전용 마운트 | Git 백엔드는 브랜치·커밋 기준 조회에 유리하지만 clone·인증·조회 버전 관리가 추가됨. 저장소 파일을 Git으로 이력 관리하는 것과 Config Server의 Git 백엔드를 쓰는 것은 구분 |
| 최초 접속·실패 처리 | 앱 이름·활성 프로필·Config 주소는 앱의 시작 설정과 환경변수로 제공. 일반 설정은 중앙에서 수신하며 접속 실패·필수 설정 누락 시 기동 실패 | `optional:`로 접속 실패를 허용하면 독립 실행은 편하지만 중앙 설정 없이 실행될 수 있음. 테스트 격리는 테스트 전용 설정으로 명시 |
| 첫 적용·실행 범위 | Config Server와 기존 공동구매 앱까지만 연결하고 각각 이미지·Compose·최소 헬스 확인 제공 | 후속 서비스를 한꺼번에 추가하면 설정 수신·서비스 발견·라우팅 문제의 원인을 나누어 확인하기 어려움. Eureka와 Gateway는 다음 단계에서 각각 추가 |

파일 시스템 백엔드로 시작하면 별도 원격 설정 저장소 없이 로컬 구성을 재현할 수 있다. 대신 여러 Config 인스턴스 사이의 파일 동기화나 실행 시점의 커밋 고정은 제공하지 않는다. 설정 변경은 클라이언트 재시작으로 반영하며 동적 갱신·Spring Cloud Bus는 후속 검토 대상으로 둔다.

## 버전과 의존성

- Java 21·Spring Boot 4.0.9·Gradle Wrapper 9.8.2는 유지한다.
- 공식 호환표에서 Boot 4.0.x와 Cloud 2025.1.x의 대응을 확인했다. 2026-10-30 공식 프로젝트 페이지에 표시된 `2025.1.4`를 Cloud BOM 후보로 삼는다. 구현 시 배포 아티팩트 확인·의존성 해석·서버/클라이언트 실제 기동 후 정확한 조합을 확정하고 결과에 기록한다.
- Cloud 의존성이 필요한 모듈에 `spring-cloud-dependencies` BOM을 적용하고 개별 Config 라이브러리 버전을 임의로 섞지 않는다. 기존 Boot BOM 기반 Gradle 방식을 유지한다.
- Cloud 호환성 검사를 끄거나 Boot 버전을 자동 변경해 문제를 우회하지 않는다. 호환 문제를 확인하면 근거와 대안을 계획에 반영한다.
- Config Server의 설정 제공과 Actuator, 클라이언트의 Config Data Import·최소 웹 서버·Actuator에 필요한 의존성만 추가한다. 공통 업무 라이브러리는 만들지 않는다.

## 제안 구조와 변경 예정 파일

```text
group-purchase/
├── settings.gradle                         # config-server 모듈 등록
├── build.gradle                            # Cloud BOM 버전 기준
├── compose.yml                             # 서버와 클라이언트 실행·준비 상태
├── .dockerignore                           # 비밀값·캐시·불필요 파일 제외
├── config-repo/
│   ├── application.yml                     # 실제 공통 일반 설정
│   ├── group-purchase.yml                  # 공동구매 기본 설정
│   ├── group-purchase-local.yml            # 호스트 개발 환경
│   └── group-purchase-docker.yml           # Compose 환경
├── infrastructure/config-server/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/{main,test}/                    # 서버·YAML·계약 검증
├── services/group-purchase-service/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/{main,test}/                    # Config 수신·기동·기존 테스트
├── .github/workflows/ci.yml                # 기존 테스트와 이미지 빌드 검증
└── Docs/implementation/002-config-server.md # 구현 후 결과 작성
```

루트 README와 문서 안내도 실제 실행 방법·진행 상태에 맞춰 갱신한다. 테스트 설정·fixture의 세부 파일명은 구현 시 필요한 범위에서 정한다. 미래 서비스의 빈 모듈이나 사용할 값이 없는 설정 파일은 미리 만들지 않는다.

## 설정 소유권과 이름

| 구분 | 위치·책임 |
|---|---|
| 서버 자신의 시작 설정 | Config Server의 `application.yml`과 환경변수. 포트·native 백엔드·설정 경로·최소 헬스 노출. 자기 자신이나 Eureka에서 가져오지 않음 |
| 클라이언트의 시작 설정 | 공동구매 앱의 `application.yml`과 환경변수. 앱 이름·프로필·Config 접속 주소 등 원격 설정을 읽기 전에 필요한 값 |
| 일반 설정 | `config-repo/`의 공통·서비스별·환경별 YAML. 실제로 사용하는 값만 추가 |
| 비밀값 | Git 및 Config 응답에 넣지 않고 필요한 서비스에 직접 주입. 실제 비밀값은 이번 단계에서 필요하지 않으며 예시는 가짜 값만 사용 |

기존 `spring.application.name=group-purchase`를 유지한다. Gradle 모듈·Compose 서비스 이름 `group-purchase-service`와 구분하므로 중앙 설정 파일은 `group-purchase*.yml`, 조회 예시는 `/group-purchase/local`과 `/group-purchase/docker`다.

Config Server는 포트 8888, 첫 클라이언트는 포트 8080을 초기 제안값으로 둔다. 호스트에서 실행하는 클라이언트는 `http://localhost:8888`, Compose 안에서는 `http://config-server:8888`로 접속한다. 주소·활성 프로필은 중앙 설정을 읽기 전에 결정한다. Windows 파일 경로와 컨테이너 경로를 구분해 검증한다.

## 구현할 동작

1. `infrastructure/config-server/`에 독립 실행 모듈을 등록하고 native 백엔드가 지정한 `config-repo/`에서 YAML을 읽도록 구성한다.
2. 공통·서비스·프로필별 값의 합성 및 우선순위를 검증한다. 서버 자신의 설정이나 비밀값이 클라이언트 응답에 포함되지 않는지 확인한다.
3. 공동구매 앱에 `spring.config.import` 방식의 Config Client를 적용한다. 기본 실행에서는 `optional:`을 사용하지 않고 접속 제한시간을 명시한다. 별도 `bootstrap.yml`은 도입하지 않는다.
4. 서버 응답이 왔다는 사실과 필요한 설정을 받았다는 사실을 구분한다. 서비스·환경을 식별하는 필수 설정을 검증해 오타·누락으로 공통 설정만 받은 앱이 기동하지 않도록 한다. `accept-empty=false`만으로 이를 모두 보장한다고 가정하지 않는다.
5. 기존 공동구매 앱에 최소 웹 서버와 Actuator health를 추가해 설정을 받은 프로세스가 계속 실행되는지 확인한다. 업무 API는 후속 단계에서 구현한다. 공통 오류·JSON 로그·전체 메트릭 기준은 로드맵의 별도 단계에서 정리한다.
6. 두 실행 모듈의 이미지를 만들고 루트 Compose에 누적한다. Config의 실제 설정 제공 가능 여부를 healthcheck에 포함하고, 클라이언트는 `service_healthy` 조건 뒤에 기동한다. 검사 명령에 필요한 도구가 이미지에 실제로 있는지 확인한다.
7. 호스트 진단 포트는 로컬 인터페이스에 한정하고 설정 폴더는 읽기 전용으로 연결한다. Actuator는 이번 단계에 필요한 최소 경로만 노출한다. 이미지 빌드 컨텍스트에서 `.git`·비밀값·로컬 캐시를 제외한다.
8. 일반 테스트는 미리 실행한 Config나 Docker에 의존하지 않도록 격리한다. Config 통합 검증은 테스트가 임시 서버·fixture를 제어하며 실제 클라이언트의 설정 반영과 실패를 확인한다. 테스트 전용 우회를 일반 실행 설정에 적용하지 않는다.
9. 기존 CI의 `Build and test`를 유지하고 필요한 테스트·이미지 빌드·Compose 정의 검증을 연결한다. 외부 이미지 게시·서버 배포·PR 병합은 이번 계획의 범위에 포함하지 않는다.

단순한 기동 순서 제어는 실행 중 장애 복구를 보장하지 않는다. Config 중단 뒤 이미 설정을 받은 클라이언트의 프로세스 유지와, 설정을 새로 받아야 하는 클라이언트 재기동의 실패를 구분해 검증한다. 재시작 시 Config가 복구되어 있으면 다시 기동할 수 있어야 한다.

## 검증 계획과 완료 기준

아래는 수행할 검증이며 아직 실행 결과가 아니다.

| 시나리오 | 기대 결과 |
|---|---|
| 전체·선택 Gradle 빌드와 기존 테스트 | 기존 앱 이름 유지, 서버·클라이언트 각각 실행 JAR 생성, 기존 테스트 회귀 없음 |
| 설정 제공·실제 수신 | 서버 JSON 조회뿐 아니라 클라이언트의 최종 설정값과 활성 환경 확인 |
| local/docker 설정 합성 | 해당 프로필 값과 우선순위가 예상과 일치하고 호스트/컨테이너 주소 혼동 없음 |
| Config 접속 불가 | 명시한 제한시간 안에 클라이언트 기동 실패, 정상 기동으로 기록하지 않음 |
| 필수 값·서비스/환경 설정 누락·잘못된 YAML | 잘못된 설정으로 기동하지 않고 실패 원인 확인 가능. 프로필 오타와 공통 설정만 존재하는 경우 포함 |
| Docker Compose 일괄 기동 | `docker compose config` 유효성 확인 후 `docker compose up --build -d`로 두 프로세스가 실행되고 헬스와 설정 수신 확인 |
| Config 중단·복구·클라이언트 재시작 | 기존 클라이언트 상태와 신규 기동 실패를 구분하고, Config 복구 후 재시작으로 설정 재수신 |
| 설정 변경 | 클라이언트 재시작 후 변경값 반영. 실행 중 자동 갱신을 완료로 표시하지 않음 |
| 비밀값·노출 경계 | Git·이미지 컨텍스트·Config 응답·로그에 비밀값 없음, 진단 포트의 로컬 바인딩 확인 |
| CI·재현 문서 | 빌드·테스트·이미지 검증과 실제 수행한 로컬/CI 결과 기록, 미수행 항목 명시 |

구현 완료 시 [구현 기록 목록](../README.md)에 같은 번호의 결과 문서를 연결하고 실제 버전·파일·실행 명령·검증 결과·남은 한계를 남긴다. Docker 또는 외부 CI를 실행하지 못했다면 그 검증까지 완료한 것으로 표시하지 않는다.

## 구현 시 구체화한 사항

- Cloud BOM은 `2025.1.4`, Config는 BOM의 `5.0.6`으로 의존성 해석·실제 기동·테스트를 통과했다.
- 사용자 직접 확인을 위해 클라이언트의 `health`와 세 설정 식별값만 제공하는 `info`를 노출했다. 전체 환경·설정 목록은 노출하지 않는다.
- Config의 저장소 헬스에 local/docker 필수 설정 검증을 추가했다. 기동 후 클라이언트 헬스는 Config 지속 가용성에 의존시키지 않았다.
- 기존 컨텍스트·앱 이름 테스트를 실제 Config 연동 테스트에 통합했다. 자동 테스트는 선행 실행 서비스·Docker 없이 자체 임시 서버로 수행한다.
- 사용자 직접 테스트 안내 규칙과 단계별 실행·기대 결과·종료/복구 절차를 추가했다.

## 이후 연결

이번 범위가 완료되면 Eureka Server의 상세 계획을 작성한다. Eureka도 Config에서 일반 설정을 받되 Config 최초 접속은 Eureka 탐색에 의존시키지 않는다. 이어서 Gateway 기본 라우팅, PostgreSQL 기반, API·로그 기준, 회원·세션 인증을 순서대로 연결한다. 이후 공동구매·참여, Kafka·Outbox·소비 안정성, PG Mock·결제·Saga·알림, 운영 복구·관측·장애·부하 검증으로 확장한다.

## 근거

- [Spring Cloud 공식 프로젝트·Boot 호환표](https://spring.io/projects/spring-cloud/): Cloud 버전 계열과 BOM 기준.
- [Config 파일 시스템 백엔드](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/file-system-backend.html): native 프로필·설정 경로·파일 시스템 운영 조건.
- [Config Client](https://docs.spring.io/spring-cloud-config/reference/client.html): Config Data Import·필수 접속·앱/프로필 조회.
- [Config Environment Repository](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository.html): 설정 합성·프로필 우선순위·빈 설정 응답.
- [Compose 기동 순서](https://docs.docker.com/compose/how-tos/startup-order/): 컨테이너 실행과 준비 상태의 차이·`service_healthy` 조건.

[구현 로드맵](roadmap.md) · [계획 목록](../README.md)
