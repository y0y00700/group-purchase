# 목표 달성형 공동구매

Java 21 · Spring Boot 4.0.9 · Spring Cloud 2025.1.4 · Gradle Wrapper 9.8.2

Gradle 멀티 프로젝트에 Config Server·Eureka Server·공동구매 초기 앱을 둔다. Eureka와 공동구매 앱은 Config에서 환경별 일반 설정을 받고, 공동구매 앱은 Eureka에 등록·발견한다. 업무 API·Gateway·DB·Kafka는 후속 단계다.

## 빠른 실행과 직접 확인

Docker Desktop의 Linux 컨테이너 엔진을 실행하고 프로젝트 루트에서 PowerShell로 수행한다. 8080·8761·8888 포트가 비어 있어야 하며 첫 빌드에는 인터넷 연결이 필요하다. 이미지 내부에서 Java·Gradle을 사용하므로 Compose 실행에는 호스트 JDK가 필요하지 않다. 등록 검사 스크립트에는 Python 3이 필요하다.

```powershell
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 180
docker compose ps
Invoke-RestMethod http://localhost:8888/actuator/health
Invoke-RestMethod http://localhost:8761/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
python scripts/check-discovery.py
```

세 서비스가 `healthy`이고 헬스 응답의 `status`가 `UP`이어야 한다. 공동구매 `info`의 `configuration`에는 `service=group-purchase`, `environment=docker`, `revision=002-v1`이 표시된다. 등록 검사는 최대 180초 동안 UP 인스턴스를 기다리고 등록된 주소의 실제 호출까지 확인한다. Eureka 대시보드는 `http://localhost:8761/`, Config 조회는 `http://localhost:8888/group-purchase/docker`다. 공동구매 `/`에 사용자 화면은 없다.

```powershell
# 실행 로그
docker compose logs --tail=100

# 종료: 이 Compose 프로젝트의 컨테이너·네트워크를 제거한다.
docker compose down
```

**현재 세 서비스의 정상 실행·등록 해제·Eureka/Config 장애·복구·강제 종료는 [003 직접 테스트](Docs/implementation/003-eureka-server.md#manual-tests)를 따른다.** Config 설정 변경 설명과 이전 단계 검증은 [002 결과](Docs/implementation/002-config-server.md#manual-tests)에 있다.

## 빌드와 자동 테스트

JDK 21을 `JAVA_HOME`에 지정하고 프로젝트 루트에서 실행한다. Wrapper가 고정한 Gradle 배포본을 내려받으므로 Gradle 별도 설치는 필요 없다.

```powershell
.\gradlew.bat build
.\gradlew.bat :config-server:test :eureka-server:test :group-purchase-service:test
```

테스트는 실제 임시 Config·Eureka·앱과 설정 파일을 직접 제어하며 미리 실행한 서비스나 Docker에 의존하지 않는다. 등록 테스트의 Eureka는 라이브러리 간섭을 피하도록 별도 Java 프로세스로 실행하고 종료한다. HTML 결과는 각 실행 모듈의 `build/reports/tests/test/index.html`에 생성된다.

호스트 개발 실행은 세 터미널에서 순서대로 수행한다. Compose를 실행 중이면 먼저 `docker compose down`으로 같은 포트를 비운다.

```powershell
# 터미널 1: 준비된 뒤 터미널 2를 실행한다.
.\gradlew.bat :config-server:bootRun

# 터미널 2: Config 준비 후 기본 local 프로필로 시작한다.
.\gradlew.bat :eureka-server:bootRun

# 터미널 3: Eureka 준비 후 시작한다.
.\gradlew.bat :group-purchase-service:bootRun
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
- 호스트 포트는 `127.0.0.1`에만 바인딩한다. 클라이언트는 `health`와 허용한 설정 식별 정보 `info`만 Actuator로 노출한다.
- Eureka 장애는 앱 실행을 유지하며 등록·조회 재시도로 복구한다. local에서는 `localhost:8080`, docker에서는 내부 DNS 이름 `group-purchase-service:8080`을 등록한다. 앱 헬스와 Eureka 등록 성공은 별도 검사한다.

## CI

GitHub Actions는 `main` 대상 PR·`main` push·수동 실행에서 `Build and test`와 `Images and Compose smoke test`를 수행한다. Compose job은 세 이미지 빌드·헬스 대기·실제 설정값·등록 주소 호출과 정리를 수행하도록 확장했다. 003 변경은 [PR #3](https://github.com/y0y00700/group-purchase/pull/3)으로 올렸으며 최신 head의 원격 결과는 [Checks](https://github.com/y0y00700/group-purchase/pull/3/checks)에서 확인한다. 로컬 검증 근거·한계·직접 테스트는 [003 결과](Docs/implementation/003-eureka-server.md)를 따른다.

## 문서

- [프로젝트 인계·다음 작업](Docs/handoff.md)
- [문서·설계·현재 진행 상태](Docs/README.md)
- [구현 계획·진행 규칙·직접 테스트 안내 기준](Docs/implementation/README.md)
- [002 Config Server 계획](Docs/implementation/plan/002-config-server.md) · [구현 결과·직접 테스트](Docs/implementation/002-config-server.md)
- [003 Eureka 계획](Docs/implementation/plan/003-eureka-server.md) · [구현 결과·직접 테스트](Docs/implementation/003-eureka-server.md)
- [001 Gradle·YAML·CI 전환 결과](Docs/implementation/001-multi-module-build.md)
