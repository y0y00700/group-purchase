# 003. Eureka Server 구현과 직접 테스트

작성일: 2026-11-02

상태: 구현·로컬 검증·원격 CI·PR 병합 완료. 2026-11-02 [PR #3](https://github.com/y0y00700/group-purchase/pull/3)의 최종 head `2914c1d`와 병합 main `a2a88dc`에서 CI 두 검사 성공을 확인했다. 최신 상태는 [프로젝트 인계](../handoff.md)를 따른다.

[구현 계획](plan/003-eureka-server.md) · [구현 목록](README.md) · [전체 로드맵](plan/roadmap.md)

## 변경과 동작

- `infrastructure/eureka-server/`를 독립 실행 모듈로 추가했다. `eureka-server` 이름으로 고정 Config 주소에서 local/docker 일반 설정을 받아 단독 등록소를 시작한다.
- 서버 자기 등록·다른 등록소 조회는 끈다. Config는 Eureka에 등록하지 않으며, Config의 준비 상태는 두 서비스의 설정 파일만 확인한다.
- 공동구매 서비스 이름은 `group-purchase`로 유지하고 REST 목록에는 `GROUP-PURCHASE`로 나타난다. 실제 발견은 Eureka Client의 `DiscoveryClient`로 검증한다.
- local은 `localhost:8080`, docker는 `group-purchase-service:8080`을 등록한다. 인스턴스 ID는 서비스·환경·실행 호스트·포트를 조합한다.
- Config 수신·필수 값·서비스/프로필 일치가 기동 필수 조건이다. Eureka 접속 실패는 앱 기동을 막지 않고 등록·조회 재시도를 수행한다.
- Actuator 헬스에 discovery contributor를 포함하지 않아 등록소 장애가 앱 헬스를 끌어내리지 않는다. 앱의 자체 헬스 상태는 Eureka에 전달한다. `UP` 헬스와 등록 완료를 따로 확인한다.
- 기본 하트비트·목록 갱신·서버 캐시 주기와 자기 보호는 유지한다. 테스트 JVM에서만 갱신을 가속한다. 정상 종료의 등록 해제와 강제 종료 후 잔존 등록을 구분한다.
- 세 이미지와 Compose 기동 순서·30초 종료 유예를 구성하고, CI에 등록된 주소를 다른 컨테이너에서 호출하는 검사를 추가했다.

## 자동 검증과 실행 환경

Java 21·Boot 4.0.9·Cloud BOM 2025.1.4·Gradle Wrapper 9.8.2를 유지했다. BOM이 선택한 Spring Cloud Netflix는 5.0.3, Netflix Eureka Client는 2.0.6이다. 실제 클라이언트 전송은 RestClient이며 `eureka.client.timeout`의 연결/풀 대기 1000ms·소켓 읽기 5000ms를 적용했다. 통합 테스트에서 해당 바인딩과 실제 전송 bean을 확인했다.

```powershell
.\gradlew.bat --gradle-user-home .tmp\gradle-home build --no-daemon
```

**BUILD SUCCESSFUL. Config Server 2개 + Eureka Server 8개 + 공동구매 Config 11개 + 등록·발견 2개 = 23개 테스트, 실패·오류 0개.** 위 실행은 기존 로컬 캐시를 사용한 검증 명령이며 사용자 실행은 기본 `.\gradlew.bat build`다.

| 자동 검증 | 결과 |
|---|---|
| 기존 Config 수신·환경 검증·장애·설정 변경 회귀 | 13개 통과 |
| Eureka local/docker 설정 수신·단독 서버·헬스/info·대시보드·진단 비노출 | 통과 |
| Eureka 설정 누락·서비스/환경 불일치·프로필 오타·잘못된 YAML | 기동 거절, Config 준비 상태 DOWN 검출 |
| Config 무응답·중단과 복구 | 기동 제한시간 확인, 기존 Eureka 실행 유지·신규 기동 실패·복구 후 기동 |
| 실제 앱·관찰 클라이언트의 발견 및 등록 주소 호출 | 통과 |
| 정상 종료 후 관찰 클라이언트 목록 제거·재기동 후 단일 등록 | 통과 |
| Eureka 부재 중 앱 기동·서버 등장 후 등록·서버 재시작 후 재등록 | 앱 재시작 없이 통과 |

실행 로그는 로컬 `.tmp/003-build.log`, `.tmp/003-discovery-tests.log`에 저장했다. HTML 테스트 결과는 각 모듈의 `build/reports/tests/test/index.html`에 생성된다.

일반 Gradle 검증은 Docker나 미리 실행한 서비스에 의존하지 않는다. 테스트가 임시 Config·Eureka·앱과 조회 전용 관찰 클라이언트의 생명주기를 제어한다. 기존 Config 전용 테스트에서는 Eureka만 끈다. 서버 모듈 테스트 의존성은 운영 JAR에 포함하지 않는다.

등록 테스트의 서버 starter를 클라이언트와 같은 테스트 classpath에 넣었을 때 Eureka 등록 bean 초기화가 실패했다. 계획의 격리 기준에 따라 서버는 `bootJar`를 별도 Java 프로세스로 실행하고 테스트 종료 시 정리하도록 변경했다. 클라이언트 운영 설정이나 호환성 검사를 우회하지 않았다.

## 실제 Compose 검증

Windows·Docker Engine 29.8.1의 Linux 컨테이너 엔진에서 검증했다. 기존 Java 21 이미지 digest·비root 실행·로컬 포트 바인딩과 읽기 전용 설정 마운트를 유지했다.

```powershell
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 180
python scripts/check-discovery.py
```

세 이미지 빌드와 세 서비스 healthy, Eureka·공동구매의 실제 설정 식별값, 등록 주소의 다른 컨테이너에서의 호출을 확인했다. 정상 종료·등록 제거·재등록, 실행 중 Eureka 중단·서버 복구, Eureka 없는 앱 최초 기동·뒤늦은 서버 기동도 통과했다. 서버 장애 중 앱은 UP을 유지했다.

Config 중단 중 기존 앱·Eureka 헬스는 UP이었고 새 Eureka 실행은 종료 코드 1로 실패했다. Config 복구 후 등록 검사도 통과했다. `dockre` 프로필 오타의 일회성 컨테이너도 종료 코드 1로 기동을 거절했다.

자기 보호를 활성화한 기본 설정에서 앱을 SIGKILL한 뒤 110초 후에도 UP 등록이 남는 것을 관측했고, 같은 컨테이너 재기동 후 실제 주소 호출로 복구를 확인했다. 당시 대시보드의 `EMERGENCY` 문자열은 검출되지 않았다. 이 관측만으로 자기 보호 발동이나 정확한 만료 시간을 확정하지 않으며, 임대·퇴출 주기·캐시의 영향과 구분한다.

Compose 실행 로그는 `.tmp/003-compose-up.log`, `.tmp/003-compose-lifecycle.log`, `.tmp/003-compose-failures.log`에 있다. 최종 소스로 세 이미지를 다시 빌드한 로그는 `.tmp/003-compose-build-final.log`다.

기동 직후에는 앱 헬스가 UP이어도 Eureka 등록이 잠시 DOWN이거나 이전 캐시가 보일 수 있었다. 기본 상태 갱신·캐시 주기를 유지했으며 등록 검사 스크립트는 최종 UP과 도달성이 확인될 때까지 별도로 기다린다.

Windows JDK 21에서 세 실제 JAR를 순서대로 실행하고 local 설정 식별값, 단일 UP 등록, 등록된 `http://localhost:8080/actuator/health`의 호출 성공까지 확인했다. 검증용 호스트 프로세스는 종료했다. 로그는 `.tmp/003-local-smoke.log`와 `.tmp/003-local-{config,eureka,app}.{out,err}.log`다. 위 자동 테스트와 아래 Compose 정상·실패·복구 절차는 실제 수행했으며, 호스트의 Gradle `bootRun` 명령 자체는 별도로 실행하지 않았다.

호스트 검증 후 최종 이미지로 Compose를 다시 기동해 세 서비스 healthy·docker 설정 식별값·단일 UP 등록과 내부 주소 호출을 재확인했다. 완료 시점에 세 컨테이너는 실행 중이며 기동 로그는 `.tmp/003-compose-final.log`다. 이후 실제 상태는 `docker compose ps`와 등록 검사로 확인한다.

<a id="manual-tests"></a>

## 사용자 직접 테스트

### 준비와 정상 기동

작업 디렉터리는 `C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase`다. Docker Desktop Linux 엔진·Compose·Python 3, 비어 있는 8080·8761·8888 포트가 필요하다. 첫 이미지 빌드에는 인터넷 연결이 필요하며 Compose만 실행할 때 호스트 JDK는 필요 없다.

```powershell
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 180
docker compose ps
Invoke-RestMethod http://localhost:8888/actuator/health
Invoke-RestMethod http://localhost:8761/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8761/actuator/info | ConvertTo-Json -Depth 5
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
python scripts/check-discovery.py
```

기대 결과: 세 서비스 healthy·헬스 UP. Eureka의 `configuration`은 `service=eureka-server`, `environment=docker`, `revision=003-v1`이다. 공동구매는 `group-purchase`·`docker`·`002-v1`이다. 검사 스크립트는 최대 180초 동안 2초 간격으로 등록을 조회하고, UP 인스턴스 한 개·호스트/포트·헬스 URL을 확인한 뒤 Config 컨테이너에서 그 등록 주소를 호출한다. `PASS`이면 성공이며 실패하면 0이 아닌 코드와 마지막 원인이 나온다.

대시보드는 `http://localhost:8761/`이다. 목록을 직접 조회하려면 다음을 실행한다. 등록이 반영되기 전에는 404가 나올 수 있으므로 위 스크립트로 수렴을 먼저 확인한다.

```powershell
Invoke-RestMethod -Uri http://localhost:8761/eureka/apps/GROUP-PURCHASE -Headers @{Accept='application/json'} | ConvertTo-Json -Depth 10
```

`hostName=group-purchase-service`, 포트 8080, `status=UP`, `healthCheckUrl=http://group-purchase-service:8080/actuator/health`를 확인한다. 이 주소는 Compose 내부 주소다. 호스트에서 앱을 확인할 때는 `http://localhost:8080/actuator/health`를 사용한다.

### 정상 종료와 재등록

정상 기동 완료 후 실행한다. 종료·재기동 사이에 등록 제거를 반드시 확인한다.

```powershell
docker compose stop -t 30 group-purchase-service
python scripts/check-discovery.py --absent
docker compose up -d --no-deps group-purchase-service
python scripts/check-discovery.py
```

기대 결과: 정상 종료 후 캐시가 갱신되면 등록이 사라지고, 재기동 후 UP 인스턴스 한 개가 등록된다. 조회 캐시 때문에 즉시 사라지지 않을 수 있다.

### 실행 중 Eureka 장애와 복구

```powershell
$taskAppBefore = docker compose ps -q group-purchase-service
$taskStartedBefore = docker inspect --format '{{.State.StartedAt}}' $taskAppBefore
docker compose stop -t 30 eureka-server
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose logs --tail=40 group-purchase-service
docker compose up -d --no-deps eureka-server
python scripts/check-discovery.py
$taskAppAfter = docker compose ps -q group-purchase-service
$taskStartedAfter = docker inspect --format '{{.State.StartedAt}}' $taskAppAfter
($taskAppBefore -eq $taskAppAfter) -and ($taskStartedBefore -eq $taskStartedAfter)
```

기대 결과: Eureka 중단 중 앱은 UP이고 등록·조회 오류가 로그에 나타난다. 서버 복구 후 앱 재시작 없이 재등록하며 마지막 비교는 `True`다. 초기 한 차례의 조회 오류만으로 재시도 중단을 판단하지 않는다. 180초 초과 시 아래 로그를 수집한다.

### Eureka가 꺼진 상태에서 앱 최초 기동

```powershell
docker compose stop -t 30 group-purchase-service eureka-server
docker compose up -d --no-deps --wait --wait-timeout 180 group-purchase-service
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose logs --tail=40 group-purchase-service
docker compose up -d --no-deps eureka-server
python scripts/check-discovery.py
```

기대 결과: Config만 정상이어도 앱이 기동하며 등록소 접속 오류는 재시도한다. Eureka 복구 후 같은 앱이 등록된다. `--no-deps`는 장애 재현을 위해 의도적으로 시작 의존성을 우회한다.

### Config 장애와 잘못된 프로필

```powershell
# 기존 Eureka는 받은 설정으로 계속 실행
docker compose stop config-server
Invoke-RestMethod http://localhost:8761/actuator/health
# 새 Eureka는 Config 접속 실패로 종료 코드 0이 아닌 값이어야 함
docker compose run --rm --no-deps eureka-server
$LASTEXITCODE
# 원래 서버·앱의 상태로 복구
docker compose up -d --wait --wait-timeout 180
python scripts/check-discovery.py

# 잘못된 프로필: 공통 설정만으로 서버를 실행하지 않아야 함
docker compose run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dockre eureka-server
$LASTEXITCODE
```

기대 결과: Config 중단 중 기존 Eureka 헬스는 UP. 새 실행은 Config 수신 실패로 종료된다. Config 복구 후 정상 기동·등록. 프로필 오타 실행도 필수 환경값 검증 실패로 종료된다. `run --rm`은 별도 일회성 컨테이너를 사용하므로 기본 서비스의 프로필·포트·환경변수를 바꾸지 않는다.

### 강제 종료와 자기 보호

```powershell
docker compose kill -s SIGKILL group-purchase-service
Invoke-RestMethod -Uri http://localhost:8761/eureka/apps/GROUP-PURCHASE -Headers @{Accept='application/json'} | ConvertTo-Json -Depth 10
docker compose logs --tail=60 eureka-server
# 기존 컨테이너를 다시 시작하여 같은 식별자로 등록·복구
docker compose up -d --no-deps group-purchase-service
python scripts/check-discovery.py
```

강제 종료는 등록 해제 요청을 보내지 못하므로 종료 직후 등록이 남을 수 있다. 자기 보호 상태에서는 만료 등록을 더 오래 보존할 수 있어 `--absent`의 180초 통과를 요구하지 않는다. 살아 있지 않은 대상이 UP 등록으로 남는 한계를 관측하고, 복구 후 실제 주소의 호출 성공을 확인한다.

### 자동 테스트·호스트 실행·종료

JDK 21을 `JAVA_HOME`에 지정하고 실행한다.

```powershell
.\gradlew.bat build
.\gradlew.bat :eureka-server:test :group-purchase-service:test
```

호스트 실행은 Compose를 먼저 `docker compose down`으로 종료하고 세 터미널에서 순서대로 수행한다.

```powershell
# 터미널 1
.\gradlew.bat :config-server:bootRun
# 터미널 2: Config 준비 후
.\gradlew.bat :eureka-server:bootRun
# 터미널 3: Eureka 준비 후
.\gradlew.bat :group-purchase-service:bootRun
```

등록 주소는 `localhost:8080`, 환경은 local이어야 한다. HTTP 헬스·대시보드·등록 목록을 같은 호스트 포트로 확인한다. 각 터미널 Ctrl+C로 종료한다. Docker와 호스트 실행을 혼합하지 않는다.

```powershell
# 진단
docker compose ps -a
docker compose logs --no-color --tail=150
# 종료: 이 프로젝트의 컨테이너·네트워크만 제거하며 볼륨 삭제 옵션은 사용하지 않음
docker compose down
```

일회성 프로필 시험은 자동 정리된다. 기본 설정 파일을 직접 수정했다면 변경값을 원복한 뒤 `docker compose up -d --wait --wait-timeout 180`과 등록 검사를 다시 실행한다.

## 한계와 다음 단계

단일 Eureka이며 HA·인증·외부 배포를 구현하지 않았다. 앱 헬스 UP은 등록 성공이나 업무 준비 상태 전체를 보장하지 않는다. 캐시에 오래된 주소가 남을 수 있으며 실제 서비스 요청 실패와 라우팅은 Gateway 단계에서 검증한다. 선택의 재검토 조건은 [R-23](../decisions/design-review.md#r-23-단일-eureka와-등록발견-복구)을 따른다.

구현 커밋 `120471f`를 `feat/eureka-server`에 push하고 사용자 요청으로 [PR #3](https://github.com/y0y00700/group-purchase/pull/3)을 생성했다. 최초 원격 실행에서 이미지·Compose는 통과했으나 복구 테스트가 다른 클라이언트의 캐시 수렴을 원래 앱의 수렴과 동일하게 취급해 즉시 검사한 부분이 실패했다. 원래 앱의 발견 캐시도 제한시간 안에 별도로 갱신되는지 기다리도록 수정했다. Windows 재검증에서는 임시 로그 파일 삭제 시 잠금 오류도 관측해, 서버 출력을 메모리로 수집하고 프로세스·출력 읽기 스레드를 모두 종료하도록 정리했다. 운영 설정·재시도 정책·검증할 기대 결과는 유지했다.

보완 후 Windows에서 등록·발견 통합 테스트 2개를 다시 실행해 통과했다. 최종 head `2914c1d`의 [CI #41401664562](https://github.com/y0y00700/group-purchase/actions/runs/41401664562)에서 전체 빌드·테스트와 이미지·Compose 검사가 모두 성공했다. 2026-11-02 재확인 시 PR은 병합 완료였으며, 병합 main `a2a88dc`의 [CI #41402512856](https://github.com/y0y00700/group-purchase/actions/runs/41402512856)도 두 검사 모두 성공했다.
