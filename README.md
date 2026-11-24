# 목표 달성형 공동구매

Java 21 · Spring Boot 4.0.9 · Spring Cloud 2025.1.4 · Gradle Wrapper 9.8.2

Gradle 멀티 프로젝트에 Config Server·Eureka Server·API Gateway·공동구매 초기 앱·회원 서비스를 둔다. PostgreSQL에 서비스별 DB와 계정을 두고 공동구매·회원의 JDBC와 Flyway를 연결한다. Gateway는 공개 ping과 회원 API를 라우팅하고, 공동구매 보호 경로에서는 회원 세션과 CSRF를 확인한 뒤 검증된 신원을 전달한다.

## 빠른 실행과 직접 확인

Docker Desktop의 Linux 컨테이너 엔진을 실행하고 프로젝트 루트에서 PowerShell로 수행한다. 5432·8000·8080·8081·8761·8888·9092 포트가 비어 있어야 하며 첫 빌드에는 인터넷 연결이 필요하다. 이미지 내부에서 Java·Gradle을 사용하므로 Compose 실행에는 호스트 JDK가 필요하지 않다. Kafka 검증 JAR를 호스트에서 실행할 때는 JDK 21이 필요하다. 검사 스크립트에는 Python 3이 필요하다.

```powershell
.\scripts\prepare-local-secrets.ps1
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
docker compose ps
python scripts/check-postgres.py --stage application
Invoke-RestMethod http://localhost:8888/actuator/health
Invoke-RestMethod http://localhost:8761/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
python scripts/check-discovery.py
python scripts/check-gateway.py
Invoke-RestMethod http://localhost:8000/api/group-purchases/ping | ConvertTo-Json
```

여섯 서비스가 `healthy`이고 헬스 응답의 `status`가 `UP`이어야 한다. 공동구매 `info`의 `configuration`에는 `service=group-purchase`, `environment=docker`, `revision=002-v1`이 표시된다. 등록 검사는 UP 인스턴스와 주소 도달성을 확인하고 Gateway 검사는 실제 ping 200과 비공개 경로 404를 확인한다. Eureka 대시보드는 `http://localhost:8761/`, Config 조회는 `http://localhost:8888/group-purchase/docker`다. 공동구매 `/`에 사용자 화면은 없다.

회원과 보호 API는 Gateway `http://localhost:8000`을 통해 호출한다. 로그인부터 보호 요청과 거절 확인까지는 [008 직접 테스트](Docs/implementation/008-gateway-authentication.md#manual-tests)를 따른다. 회원 직접 호출과 두 인스턴스 세션 공유는 [007 직접 테스트](Docs/implementation/007-member-session-authentication.md#사용자-직접-테스트-방법)에 있다. 제품 JAR 검사에는 Java 21과 `JAVA_HOME`이 필요하다.

```powershell
# 실행 로그
docker compose logs --tail=100

# 종료: 컨테이너·네트워크를 제거하고 PostgreSQL·Kafka 데이터 볼륨은 보존한다.
docker compose down
```

**Gateway 전달·차단·대상 중단/복구는 [004 직접 테스트](Docs/implementation/004-api-gateway.md#manual-tests)를 따른다.** Eureka 등록 해제·복구·강제 종료는 [003 직접 테스트](Docs/implementation/003-eureka-server.md#manual-tests), Config 설정 변경은 [002 결과](Docs/implementation/002-config-server.md#manual-tests)를 따른다.

## 빌드와 자동 테스트

JDK 21을 `JAVA_HOME`에 지정하고 프로젝트 루트에서 실행한다. Wrapper가 고정한 Gradle 배포본을 내려받으므로 Gradle 별도 설치는 필요 없다.

```powershell
.\gradlew.bat build
.\gradlew.bat :config-server:test :eureka-server:test :group-purchase-service:test
```

테스트는 실제 임시 Config·Eureka·앱과 설정 파일을 직접 제어한다. 공동구매 테스트에는 Docker Desktop Linux 엔진이 필요하며 격리 PostgreSQL·Kafka·임시 secret을 직접 준비하고 정리한다. 일반 DB·secret과 사전 실행한 앱은 사용하지 않는다. 등록 테스트의 Eureka는 라이브러리 간섭을 피하도록 별도 Java 프로세스로 실행하고 종료한다. HTML 결과는 각 실행 모듈의 `build/reports/tests/test/index.html`에 생성된다.

호스트 개발 실행은 세 터미널에서 순서대로 수행한다. Compose를 실행 중이면 먼저 `docker compose stop api-gateway group-purchase-service eureka-server config-server`로 앱 포트를 비우고 PostgreSQL은 유지한다. DB가 없다면 빠른 실행 절의 secret 준비 후 `docker compose up -d --no-deps --wait postgres`로 시작한다. 다음 bootRun 절차는 사용자용이며 이번 연결 스텝의 실측은 격리 테스트의 local 환경변수 주입과 Compose 실행이다.

```powershell
# 터미널 1: 준비된 뒤 터미널 2를 실행한다.
.\gradlew.bat :config-server:bootRun

# 터미널 2: Config 준비 후 기본 local 프로필로 시작한다.
.\gradlew.bat :eureka-server:bootRun

# 터미널 3: Eureka 준비 후 시작한다.
$previousPassword = $env:SPRING_DATASOURCE_PASSWORD
$previousCredential = $env:APP_AUTH_INTERNAL_CREDENTIAL
try {
    $env:SPRING_DATASOURCE_PASSWORD = (Get-Content -Raw secrets/group_purchase_password).TrimEnd("`r", "`n")
    $env:APP_AUTH_INTERNAL_CREDENTIAL = (Get-Content -Raw secrets/group_purchase_internal_credential).TrimEnd("`r", "`n")
    .\gradlew.bat :group-purchase-service:bootRun
} finally {
    $env:SPRING_DATASOURCE_PASSWORD = $previousPassword
    $env:APP_AUTH_INTERNAL_CREDENTIAL = $previousCredential
}
```

터미널 실행 종료는 각각 Ctrl+C다. macOS·Linux에서는 `bash ./gradlew build`와 `bash ./gradlew :config-server:bootRun`처럼 Wrapper를 호출하고 HTTP 확인에는 `curl`을 사용한다.

## 구조와 설정

```text
group-purchase/
├── settings.gradle / build.gradle / gradlew / gradlew.bat
├── gradle/wrapper/
├── compose.yml
├── config-repo/                     # 공통·서비스별·환경별 일반 YAML
├── infrastructure/config-server/    # native 파일 백엔드·저장소 헬스
├── infrastructure/eureka-server/    # Config 수신·단독 서비스 등록소
├── services/group-purchase-service/ # Config 수신·필수 설정 검증·헬스/info
├── scripts/check-discovery.py       # 등록·주소 도달성·정상 해제 확인
├── .github/workflows/ci.yml
└── Docs/
```

앱 이름은 기존 `group-purchase`를 유지한다. Gradle·Compose 이름 `group-purchase-service`와 달라 중앙 파일 이름은 `group-purchase*.yml`이다.

- Config 주소·앱 이름·프로필 등 최초 접속 값은 앱 시작 설정에 둔다. 호스트 기본 주소는 `http://localhost:8888`, Compose 주소는 `http://config-server:8888`이다.
- 중앙 설정 폴더는 Config 컨테이너에 읽기 전용으로 마운트한다. 비밀값은 이 폴더나 이미지에 넣지 않고 필요한 서비스에 별도로 주입한다.
- 설정 누락·환경 불일치·Config 접속 실패는 기동 실패로 처리한다. 설정 변경은 클라이언트 재시작으로 반영한다.
- 기동 후 Config 장애는 이미 받은 설정을 없애지 않는다. 클라이언트의 헬스는 Config의 지속적인 가용성에 종속시키지 않으며 새 기동에는 Config가 필요하다.
- 호스트 포트는 `127.0.0.1`에만 바인딩한다. 클라이언트는 `health,info,metrics`를 Actuator로 노출하며 Config는 `health,metrics`를 노출한다. Gateway의 관리 포트는 9000이다. 공개 8000의 라우트는 [008 공개 경로 목록](Docs/implementation/plan/008-gateway-authentication.md#공개-라우트와-보호-확인-경로)을 따른다.
- Eureka 장애는 앱 실행을 유지하며 등록·조회 재시도로 복구한다. local에서는 `localhost:8080`, docker에서는 내부 DNS 이름 `group-purchase-service:8080`을 등록한다. 앱 헬스와 Eureka 등록 성공은 별도 검사한다.

## CI

GitHub Actions는 `main` 대상 PR·`main` push·수동 실행에서 `Build and test`와 `Images and Compose smoke test`를 수행한다. Compose job은 다섯 앱 이미지 빌드·PostgreSQL/Kafka 기동·토픽 초기화·임시 secret 준비·DB 권한/영속성·Kafka 양쪽 리스너·실제 설정값·등록 주소·Gateway 라우팅과 인증 검사·정리를 정의한다. 전체 down/up·Kafka 중단 복구는 로컬 전용 011 프로젝트에서 별도로 검증한다. 원격 실행 여부는 최신 인계에서 구분한다.

## 문서

- [011 Kafka 결과·직접 작성·테스트](Docs/implementation/011-kafka-message-contract.md): 사용자 작성·묶음 리뷰·로컬 재검증을 완료했으며 기준 구현과 최종 결과를 구분한다. Kafka는 HOST `localhost:9092`, Compose INTERNAL `kafka:19092`이며 컨트롤러는 호스트에 공개하지 않는다. 상시 7개 서비스와 일회성 kafka-init(exit 0)을 구분한다.

- [전체 진행 정리 · 2026-11-03](Docs/progress-summary-2026-11-03.md)
- [프로젝트 인계·다음 작업](Docs/handoff.md)
- [문서·설계·현재 진행 상태](Docs/README.md)
- [구현 계획·진행 규칙·직접 테스트 안내 기준](Docs/implementation/README.md)
- [002 Config Server 계획](Docs/implementation/plan/002-config-server.md) · [구현 결과·직접 테스트](Docs/implementation/002-config-server.md)
- [003 Eureka 계획](Docs/implementation/plan/003-eureka-server.md) · [구현 결과·직접 테스트](Docs/implementation/003-eureka-server.md)
- [004 Gateway 계획](Docs/implementation/plan/004-api-gateway.md) · [구현 결과·직접 테스트](Docs/implementation/004-api-gateway.md)
- [005 PostgreSQL 실행 기반 계획](Docs/implementation/plan/005-postgresql-foundation.md) · [최종 결과](Docs/implementation/005-postgresql-foundation.md#runtime-final-results) · [장애 복구·Windows JAR·전체 down/up 직접 테스트](Docs/implementation/005-postgresql-foundation.md#runtime-manual-tests): 로컬 구현·최종 검증 완료
- [001 Gradle·YAML·CI 전환 결과](Docs/implementation/001-multi-module-build.md)

006의 오류 계약·요청 ID·JSON 로그·probes/metrics와 여섯 학습 구간의 작성·로컬 최종 검증을 완료했다. [최종 결과·사용자 직접 테스트](Docs/implementation/006-api-observability-baseline.md#final-results)에 현재 소스의 50개 테스트 결과·네 이미지/다섯 서비스·실제 관측/DB 복구·Windows 제품 JAR 근거를 기록했다. 원격 PR/CI 상태는 최신 인계에서 구분한다.

007의 회원·JDBC 세션 인증과 DDD 계층 분리·여섯 학습 구간의 작성/재검증도 완료했다. [007 최종 결과](Docs/implementation/007-member-session-authentication.md#final-results)에 현재 소스의 64개 테스트 결과(기존 50개 유효 결과 재사용 포함)·회원 제품 JAR/이미지·여섯 서비스·이전 세션 호환 근거를 기록했다.
