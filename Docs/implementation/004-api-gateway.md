<a id="top"></a>

# 004. API Gateway 기본 라우팅 구현과 직접 테스트

작성일: 2026-11-02

상태: 구현·로컬 검증 완료. [PR #4](https://github.com/y0y00700/group-purchase/pull/4)의 코드 커밋 `c16c7cb`에서 [CI #41436116472](https://github.com/y0y00700/group-purchase/actions/runs/41436116472)의 두 검사가 성공했다. 문서 갱신 후 최종 head의 CI는 PR Checks에서 별도 확인한다.

[구현 계획](plan/004-api-gateway.md) · [구현 목록](README.md) · [전체 로드맵](plan/roadmap.md)

## 변경과 동작

- 독립 `api-gateway` 모듈을 추가했다. WebFlux·Netty 기반이며 Config에서 local/docker 라우트와 포트·제한시간을 받는다. Eureka에서 `group-purchase` 주소를 조회하고, Gateway 자신은 등록하지 않는다.
- 8000의 `GET /api/group-purchases/ping`만 공동구매 앱의 같은 경로로 전달한다. 공동구매 앱의 확인용 응답은 `{"service":"group-purchase","message":"pong"}`이다. 자동 서비스 경로 생성은 끈다.
- 관리 health/info는 9000으로 분리했다. Config 준비 상태 검사가 Gateway 설정도 확인한다. Docker는 8000만 호스트 loopback에 게시한다.
- 인스턴스 없음 503, 연결 실패 502, 전송 제한시간 초과 504를 구분한다. 대상이 실제 HTTP 4xx·5xx를 반환한 경우에는 그 응답을 보존한다. 응답은 내부 주소나 예외 내용을 포함하지 않는다.
- 네 이미지·Compose·GitHub Actions 정의에 Gateway를 추가하고 실제 전달과 비공개 경로를 검사하는 `scripts/check-gateway.py`를 추가했다.
- 역할·개요는 로컬 학습 노트 [API Gateway](../explanations/api-gateway.md)에 기록했다. 이 노트는 Git 제외 경로에 둔다.

실제 의존성 해석에서 Cloud Gateway starter는 5.0.4, Reactor Netty HTTP는 1.3.8이었다. Boot 4.0.9·Cloud BOM 2025.1.4·Java 21·Gradle 9.8.2를 유지했다.

## 검증 결과

| 구분 | 실제 확인 범위 |
|---|---|
| 선택 빌드 | `:api-gateway:bootJar` 성공 |
| Gateway 통합 테스트 | 테스트용 실제 HTTP 서버에서 공개 경로 전달, 경로·쿼리·헤더 및 대상 상태 보존, 비공개 경로 차단, 인스턴스 없음·연결 실패·지연 검증. 연결 시간 초과 예외의 504 분류도 확인. 4개 테스트 통과 |
| Config 테스트 | Gateway의 docker 설정 제공과 저장소 준비 상태 검사. 3개 테스트 통과 |
| 전체 빌드 | Docker 네 이미지 병렬 빌드와 함께 수행한 첫 실행에서 기존 Eureka 등록·복구 테스트 2개가 45초 내 조건을 만족하지 못함. 이미지 빌드 종료 뒤 단독 재실행 성공. 연결 시간 초과 분류 수정 후 Gateway 선택 빌드와 네 모듈 전체 `build`를 다시 실행해 최종 28개 테스트·실패 0개 |
| 이미지·Compose | 네 이미지 빌드·네 컨테이너 healthy, Config에서 등록 주소 도달성 PASS, Gateway ping 200·비공개 경로/메서드 404·info 004-v1 확인. Eureka API에서 Gateway 미등록 404 확인. 마지막 전송 오류 수정 후 Gateway 이미지를 다시 빌드·교체하고 같은 검사 PASS |
| 대상 정상 종료·복구 | Eureka 목록에서 공동구매 등록 제거 확인, Gateway가 503으로 수렴하고 자체 health는 UP, 다시 시작한 뒤 등록·Gateway 200 복귀 확인 |
| Windows JAR local 연동 | `JAVA_HOME`의 Java 21로 공동구매 앱·Gateway를 함께 실행. local 프로필 Config 수신, `localhost:8080` 단일 등록, Gateway ping 200·내부 경로 404·info 환경 local 확인 |
| Eureka 없는 기동과 회복 | Eureka를 멈춘 상태에서 Gateway JAR 기동·헬스 UP·ping 503, 서버 복구 후 **동일 Gateway PID 26632**에서 ping 200. 대상 앱도 Windows JAR로 유지 |
| 기동 실패 | Config 주소 `127.0.0.1:59999` 접속 거절, 오타 프로필 `dockre`에서 Gateway JAR이 각각 종료 코드 1로 기동 거절 |
| 원격 CI | PR #4 코드 커밋 `c16c7cb`의 CI #41436116472에서 Build and test·Images and Compose smoke test 모두 성공. Gateway 경로·비공개 경로 검사와 종료 단계까지 통과 |

Gateway 자동 테스트는 Config를 끈 전용 `application.yml`을 사용한다. 따라서 fixture의 라우트·인스턴스 공급자만으로 Config·Eureka 연동을 증명하지 않는다. 실제 Docker와 Windows JAR 연동을 별도로 검증했다. Windows `PATH`의 기본 `java`는 17이라 첫 JAR 실행이 class file version 65를 거절했다. 이후 `JAVA_HOME`의 Java 21로 실행해 확인했다.

<a id="manual-tests"></a>

## 사용자 직접 테스트 방법

준비 사항: Docker Desktop Linux 엔진·Python 3, 비어 있는 호스트 포트 8000·8080·8761·8888. Gradle 테스트와 로컬 JAR 실행에는 JDK 21 및 `JAVA_HOME`이 필요하다. 관리 포트 9000은 Compose에서는 호스트에 게시하지 않고, 로컬 JAR 실행 때 사용할 수 있어야 한다. 작업 디렉터리는 저장소 루트다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 180
docker compose ps
python scripts/check-discovery.py
python scripts/check-gateway.py --timeout 180
curl.exe -i http://localhost:8000/api/group-purchases/ping
curl.exe -i http://localhost:8000/internal/probe
curl.exe -i http://localhost:8000/actuator/health
docker compose exec -T api-gateway curl --fail --silent http://localhost:9000/actuator/health
docker compose exec -T api-gateway curl --fail --silent http://localhost:9000/actuator/info
```

정상이면 네 컨테이너가 healthy이고 두 스크립트가 `PASS`를 출력한다. ping은 200과 `service=group-purchase`, `message=pong` JSON을 반환한다. 8000의 내부·관리 경로는 404다. 9000의 컨테이너 내부 health는 `UP`, info의 configuration은 `api-gateway`·`docker`·`004-v1`이다. Gateway 자체 health의 UP과 실제 전달 성공은 각각 판정한다.

공동구매 앱을 정상 종료한 뒤 Gateway의 발견 캐시가 비워지는지 검사한다. 갱신 주기 때문에 잠시 502 또는 200이 나올 수 있다. `check-gateway.py --absent`는 503으로 수렴할 때까지 최대 180초를 기다린다.

```powershell
docker compose stop group-purchase-service
python scripts/check-discovery.py --absent --timeout 180
python scripts/check-gateway.py --absent --timeout 180
docker compose exec -T api-gateway curl --fail --silent http://localhost:9000/actuator/health
docker compose start group-purchase-service
python scripts/check-discovery.py --timeout 180
python scripts/check-gateway.py --timeout 180
```

예상 결과는 대상이 없을 때 Gateway ping 503·Gateway 자체 health UP, 다시 시작하면 ping 200이다. 강제 종료 시 Eureka의 오래된 UP 주소가 남을 수 있으므로 이 절차는 정상 종료를 사용한다.

Eureka 장애는 기존 캐시에 따라 결과가 달라질 수 있다. 다음 명령은 서버가 다시 시작된 뒤 앱 등록과 Gateway 실제 요청의 복구를 확인한다.

```powershell
docker compose stop eureka-server
curl.exe -i http://localhost:8000/api/group-purchases/ping
docker compose start eureka-server
python scripts/check-discovery.py --timeout 180
python scripts/check-gateway.py --timeout 180
```

연결 거절 502와 지연 504의 재현은 자동 테스트의 실제 HTTP fixture로 제공한다. 이 검사는 운영용 장애 유발 API를 추가하지 않는다.

```powershell
.\gradlew.bat :api-gateway:test --tests '*RoutingIntegrationTests'
.\gradlew.bat build
```

`RoutingIntegrationTests`에서 비공개 경로 404, 인스턴스 없음 503, 연결 거절 502, 3초 테스트 제한시간보다 느린 대상 504, 대상이 반환한 503 보존을 확인한다. 전체 빌드는 다른 세 모듈 회귀도 실행한다. 실제 실행 결과는 위 검증 표에서 확인한다.

Windows JAR local 연동은 새 PowerShell 터미널 세 개에서 수행한다. 세 터미널 모두 저장소 루트를 작업 디렉터리로 사용한다. 터미널 A에서 Docker의 공동구매 앱·Gateway만 멈추고 로컬 공동구매 JAR을 실행한다. Config·Eureka 컨테이너는 계속 켜 둔다.

```powershell
# 터미널 A: 아래 java 명령은 실행 상태로 유지한다.
docker compose stop api-gateway group-purchase-service
& "$env:JAVA_HOME\bin\java.exe" -jar services/group-purchase-service/build/libs/group-purchase-service-0.0.1-SNAPSHOT.jar
```

터미널 C에서 등록 목록의 유일한 인스턴스 `hostName=localhost`, `port=8080`을 확인한 뒤 Eureka만 멈춘다. 호스트에서 실행하는 앱 두 개의 주소를 맞추기 위한 절차다.

```powershell
# 터미널 C
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod -Headers @{Accept='application/json'} http://localhost:8761/eureka/apps/GROUP-PURCHASE | ConvertTo-Json -Depth 5
docker compose stop eureka-server
```

터미널 B에서 Gateway JAR을 시작한다. 이 터미널도 실행 상태로 유지한다.

```powershell
# 터미널 B
& "$env:JAVA_HOME\bin\java.exe" -jar infrastructure/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar
```

터미널 C에서 9000 health UP과 Gateway ping 503을 확인한다. Eureka를 다시 시작하고 같은 Gateway 프로세스를 유지한 채 ping 200 복귀를 기다린다. 정상 복구에는 Eureka 등록·Gateway 발견 캐시 갱신 시간이 걸릴 수 있다.

```powershell
# 터미널 C
Invoke-RestMethod http://localhost:9000/actuator/health
curl.exe -i http://localhost:8000/api/group-purchases/ping
docker compose start eureka-server
python scripts/check-gateway.py --timeout 180
Invoke-RestMethod http://localhost:9000/actuator/info | ConvertTo-Json -Depth 5
```

마지막 info의 `configuration`은 `api-gateway`·`local`·`004-v1`이다. Docker 공동구매 앱만 실행하며 Windows Gateway를 붙이면 Eureka가 알려 준 `group-purchase-service`라는 Docker 내부 이름을 Windows에서 해석할 수 없다. 위 로컬 절차는 공동구매 앱과 Gateway를 같은 호스트 주소 체계로 실행한다.

Config 접속 실패·프로필 오타는 터미널 A/B의 JAR을 종료한 뒤 확인한다. 두 명령은 Gateway가 기동 전에 종료 코드 1을 반환해야 한다. `CONFIG_SERVER_URL`은 테스트 후 복원한다.

```powershell
$previousConfigUrl = $env:CONFIG_SERVER_URL
try {
    $env:CONFIG_SERVER_URL = 'http://127.0.0.1:59999'
    & "$env:JAVA_HOME\bin\java.exe" -jar infrastructure/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar --server.port=0 --management.server.port=0
    $LASTEXITCODE
} finally {
    if ($null -eq $previousConfigUrl) { Remove-Item Env:CONFIG_SERVER_URL -ErrorAction SilentlyContinue }
    else { $env:CONFIG_SERVER_URL = $previousConfigUrl }
}
& "$env:JAVA_HOME\bin\java.exe" -jar infrastructure/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=dockre --server.port=0 --management.server.port=0
$LASTEXITCODE
```

종료·복구:

```powershell
docker compose stop eureka-server
docker compose start config-server eureka-server group-purchase-service api-gateway
python scripts/check-discovery.py --timeout 180
python scripts/check-gateway.py --timeout 180
# 마지막에 이 Compose 프로젝트의 컨테이너·네트워크만 제거한다.
docker compose down
```

로컬 JAR 두 개는 각각 터미널에서 `Ctrl+C`로 종료한다. 강제 종료로 남은 localhost 등록을 제거하려면 위 종료·복구 블록처럼 Eureka를 재시작한 뒤 Docker 서비스를 시작한다. 설정 파일을 바꾸어 오류를 재현했다면 원래 내용을 복원하고 `docker compose up --build -d --wait`로 다시 기동한다. 이 단계에는 DB나 영속 테스트 데이터가 없다.

## 남은 작업과 한계

- 위 원격 CI는 코드 커밋 `c16c7cb`의 결과다. 이 문서를 추가 갱신한 최종 PR head의 결과는 PR Checks에서 다시 확인한다.
- 502·504의 실제 Compose 장애 재현과 전체 요청 소요시간 보장은 검증 범위와 구분한다. 자동 테스트는 제어 가능한 fixture로 상태 매핑을 확인한다.
- Gateway 8000의 경로 제한은 기존 8080·8761·8888 진단용 호스트 포트를 닫지 않는다. 회원 인증과 내부 호출자 신뢰는 로드맵의 후속 단계다.
