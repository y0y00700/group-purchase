# 002. Config Server와 첫 클라이언트 연동 결과

작성일: 2026-10-30

갱신일: 2026-11-02

상태: 구현·로컬 자동 테스트·이미지·Compose·GitHub CI 검증 완료. 2026-11-02 재개 시 [PR #2](https://github.com/y0y00700/group-purchase/pull/2)의 병합 완료를 확인했다. PR 최종 head는 `b20925e`, 병합 커밋은 `56f3474`이다. 최종 head의 [CI #41373781570](https://github.com/y0y00700/group-purchase/actions/runs/41373781570)에서 두 검사가 성공했다. 아래 PR 생성 당시 기록과 구분한다.

[구현 계획](plan/002-config-server.md) · [구현 목록](README.md) · [전체 로드맵](plan/roadmap.md)

## 변경과 동작

- `infrastructure/config-server/`를 독립 Gradle 실행 모듈로 추가했다. Config Server의 native 백엔드가 같은 저장소의 `config-repo/` YAML을 제공한다.
- 기존 공동구매 앱에 Config Client·웹 서버·Actuator·설정 검증을 추가했다. `spring.application.name=group-purchase`는 유지한다.
- 공통·서비스별·`local`/`docker` 설정을 합성한다. 앱 이름·프로필·Config 주소는 최초 접속에 필요한 시작 설정이고, 일반 값은 중앙에서 받는다.
- `app.configuration.service/environment/revision`은 필수다. 서비스·현재 환경과 맞지 않거나 필수 값이 없으면 기동하지 않는다. `accept-empty=false`만으로 공통 설정에 가려진 서비스 설정 누락을 판정하지 않는다.
- `/actuator/info`는 위 세 식별값만 보여 주는 전용 contributor를 사용한다. 전체 환경·설정 목록은 노출하지 않는다. Config Server 자체 설정도 중앙 응답에 포함하지 않는다.
- Config Server의 추가 헬스 검사는 첫 클라이언트의 local/docker 설정을 실제로 읽어 필수 식별값을 확인한다. Compose는 이 준비 상태 뒤에 클라이언트를 기동한다.
- 클라이언트의 원격 Config 헬스 검사는 끈다. 받은 설정을 유지하는 실행 중 앱과 새 설정을 받아야 하는 신규 기동을 구분하기 위해서다. Config 중단 시 기존 클라이언트는 실행을 유지하지만 새 기동은 실패한다.
- 이미지 두 개와 루트 `compose.yml`을 추가했다. 비root 사용자, 읽기 전용 설정 마운트, `127.0.0.1` 호스트 포트, 이미지에 실제 포함된 curl 헬스 검사를 사용한다.
- 기존 CI 빌드·테스트를 유지하고 이미지·Compose·설정 확인 job을 추가했다. 2026-11-02 사용자 요청으로 구현 커밋을 push하고 PR #2를 생성했다. PR 병합은 수행하지 않았다.

`AGENTS.md`와 [구현 진행 규칙](README.md#사용자-직접-테스트-방법)에 매 단계의 사용자 직접 테스트 안내를 필수로 추가했다. 로컬 전용 협업 원칙에도 같은 요청을 반영했다.

## 적용 버전과 검증 환경

| 항목 | 실제 적용·확인 |
|---|---|
| Spring Boot | 4.0.9 유지 |
| Spring Cloud BOM | 2025.1.4 |
| Spring Cloud Config | BOM이 선택한 5.0.6 |
| Gradle Wrapper | 9.8.2 유지 |
| 호스트 JDK | Microsoft Java 21.0.12.1, Windows PowerShell 7.6.5 |
| 이미지 JDK/JRE | Eclipse Temurin 21.0.13+9, Ubuntu Jammy, Linux amd64 |
| Docker / Compose | Engine 29.8.1 / Compose 5.5.1 |

Dockerfile은 공식 Java 21 이미지의 검증한 다중 플랫폼 digest를 고정한다.

- JDK: `sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69`
- JRE: `sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269`

이미지 내부에서 `java -version`으로 Temurin 21.0.13+9, `id`로 `uid=999(app)` 실행을 확인했다. 다른 CPU 아키텍처에서의 실행은 검증하지 않았다.

## 자동 검증 결과

프로젝트 루트에서 실행했다. 이 세션은 기존 로컬 캐시 위치를 사용했으며 저장소 기본 설정에 이 경로를 고정하지 않았다.

```powershell
.\gradlew.bat --gradle-user-home .tmp\gradle-home build --no-daemon
```

**BUILD SUCCESSFUL. Config Server 2개 + 클라이언트 11개 = 총 13개 테스트, 실패·오류 0개.**

| 검증 | 결과 |
|---|---|
| 실제 서버의 중앙 설정 응답·자체 설정 미노출 | 통과 |
| 저장소 헬스·공통 설정만 남은 저장소의 DOWN | 통과 |
| local/docker 실제 클라이언트 설정 반영·HTTP health/info | 통과 |
| 기존 앱 이름·기본 local 프로필·컨텍스트 기동 | 통과 |
| 클라이언트 env/configprops 비노출 | 통과, HTTP 404 |
| 프로필 오타·서비스/환경 불일치·서비스 설정 누락 | 통과, 기동 거절 |
| 잘못된 원격 YAML·응답하지 않는 Config | 통과, 기동 거절·제한시간 확인 |
| Config 중단 중 기존 앱 유지·신규 기동 실패·복구 후 기동 | 통과 |
| 파일 변경 전후·클라이언트 재시작 후 반영 | 통과 |

테스트는 실제 Config Server를 임시 포트에서 띄우고 필요한 경우 임시 설정 복사본을 만든다. 기존 컨텍스트·앱 이름 검사는 실제 Config 연동 테스트에 통합했다. 서버 테스트 의존성은 `testImplementation`에만 있으며 운영 클라이언트 JAR에는 포함하지 않는다.

검증 중 Boot 4에서 이동한 Health API 패키지를 적용했다. 같은 테스트 JVM에 있는 Config Server 의존성이 Config Client를 기본 비활성화하는 동작은 테스트에서만 명시적으로 재활성화했다. 테스트 서버 주소는 앱의 `CONFIG_SERVER_URL`로 주입해 실제 `application.yml`의 import 경로를 검증한다. 호환성 검사를 비활성화하지 않았다.

## 이미지·Compose 검증

```powershell
docker compose config --quiet
docker compose up --build --wait --wait-timeout 180
docker compose ps
```

두 이미지 빌드와 두 서비스의 `healthy` 상태를 확인했다. HTTP로 서버·클라이언트의 `status=UP`과 다음 실제 설정값을 확인했다.

```json
{"configuration":{"service":"group-purchase","environment":"docker","revision":"002-v1"}}
```

실제 Compose에서 잘못된 프로필은 종료 코드 1, Config 중단 중 기존 클라이언트는 UP, Config가 중단된 상태의 클라이언트 재기동은 종료 코드 1, 복구 후 재기동은 정상임을 확인했다. 설정 변경은 재시작 전 `002-v1` → 재시작 후 `002-manual-test` → 파일 원복·재시작 후 `002-v1`을 확인했다. 아래 사용자 직접 테스트의 정상·실패·복구·설정 변경 절차를 실제 수행했다.

Windows 호스트에서도 두 실행 JAR를 임시 포트 18888·18080으로 띄워 `file:./config-repo/` 경로와 기본 local 프로필의 `local / 002-v1` 수신·헬스를 확인하고 해당 임시 프로세스를 종료했다. 호스트 실행 로그는 `.tmp/002-local-*.log`에 남겼다.

로컬 근거: `.tmp/002-build.log`, 각 모듈의 `build/test-results/test/TEST-*.xml`, `.tmp/002-compose-build.log`, `.tmp/002-bad-profile.log`. 로그·빌드 산출물은 Git 제외다. 원격 CI 성공을 이 로컬 결과로 대신 표시하지 않는다.

## PR과 GitHub CI 검증

2026-11-02 사용자 요청으로 최신 origin/main `4143251`에서 `feat/config-server`를 분리하고 구현 커밋 `b95fd76ac94d3dbafc744797bc9df4144da6f783`을 push했다. [PR #2](https://github.com/y0y00700/group-purchase/pull/2)의 대상은 main이다. 기존 Gradle 전환 PR #1은 병합 완료 상태임을 확인했다.

[CI #41373439233](https://github.com/y0y00700/group-purchase/actions/runs/41373439233)은 구현 커밋 `b95fd76`을 대상으로 실행했고 두 검사가 모두 성공했다.

| 원격 검사 | 실제 결과 |
|---|---|
| Build and test | Ubuntu·Java 21의 Gradle 전체 빌드·테스트 성공 |
| Images and Compose smoke test | Compose 정의·두 이미지 빌드·healthy 대기·docker/002-v1 적용값·컨테이너 정리 성공 |

위 근거는 구현 커밋에 대한 기록이다. 후속 문서 커밋을 포함한 최신 상태는 [PR #2 Checks](https://github.com/y0y00700/group-purchase/pull/2/checks)에서 확인한다. 다음 작업·브랜치·직접 실행·재개 문구는 [프로젝트 인계](../handoff.md)에 정리했다.

<a id="manual-tests"></a>

## 사용자 직접 테스트

아래 정상·실패·복구 절차는 Windows PowerShell에서 수행하는 기준이다. Docker 명령은 macOS·Linux에서도 같고 HTTP 요청은 `curl`로 바꿀 수 있다.

### 1. 준비와 자동 테스트

Docker Desktop에서 Linux 엔진을 실행한다. 8080·8888 포트를 비우고 이 프로젝트 루트에서 실행한다. 첫 이미지 빌드에는 인터넷이 필요하다. DB·Kafka·비밀값·업무 테스트 데이터는 아직 필요하지 않다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
docker compose version
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 180
docker compose ps
```

기대 결과: 두 서비스가 `healthy`. `--wait`는 최초 이미지 빌드 시간과 별도로 서비스의 준비 상태를 기다린다. 실패하면 `docker compose logs --tail=100`으로 원인을 확인한다.

JDK 21이 설치되어 있고 `JAVA_HOME`이 지정되어 있으면 자동 테스트도 직접 실행할 수 있다. 미리 실행한 Compose는 자동 테스트의 필수 조건이 아니다.

```powershell
.\gradlew.bat build
```

기대 결과: `BUILD SUCCESSFUL`, 테스트 실패 0개. 보고서는 `infrastructure/config-server/build/reports/tests/test/index.html`과 `services/group-purchase-service/build/reports/tests/test/index.html`에서 연다.

### 2. 설정 응답과 실제 적용값

```powershell
Invoke-RestMethod http://localhost:8888/actuator/health | ConvertTo-Json
Invoke-RestMethod http://localhost:8888/group-purchase/docker | ConvertTo-Json -Depth 8
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
```

기대 결과: 두 health의 `status`는 `UP`. Config 응답은 `group-purchase-docker.yml`·`group-purchase.yml`·`application.yml`을 제공한다. 클라이언트 info는 `service=group-purchase`, `environment=docker`, `revision=002-v1`. JSON 키 순서는 판정 기준이 아니다.

### 3. 잘못된 프로필 거절

정상 서비스는 그대로 두고 일회성 클라이언트를 실행한다.

```powershell
docker compose run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dockre group-purchase-service
$LASTEXITCODE
```

기대 결과: 종료 코드 `1`. `app.configuration.environment`의 `must not be blank` 오류로 기동하지 않는다. 공통 설정만 수신했다는 이유로 성공 처리하지 않는다. `--rm`이 일회성 컨테이너를 정리한다.

### 4. Config 장애와 복구

```powershell
docker compose stop config-server
Invoke-RestMethod http://localhost:8080/actuator/health

docker compose restart group-purchase-service
docker compose wait group-purchase-service
docker compose ps -a
docker compose logs --tail=50 group-purchase-service
```

기대 결과: Config를 중단해도 기존 클라이언트는 `UP`. 클라이언트를 재시작하면 Config 접속 실패로 `Exited (1)`이 된다. `compose restart` 자체의 성공은 앱 기동 성공을 뜻하지 않는다. `compose wait`의 비정상 종료는 이 테스트의 기대 결과다.

```powershell
docker compose up -d --no-build --wait --wait-timeout 120
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
```

기대 결과: Config부터 복구되고 두 서비스가 다시 `healthy`, 클라이언트가 `docker / 002-v1` 설정을 수신한다.

### 5. 설정 변경·클라이언트 재시작·원복

실행 중 설정 갱신을 자동으로 하지 않는지 확인한다. 아래 전체 블록을 같은 PowerShell에서 실행하면 임시 변경을 `finally`에서 복원한다.

```powershell
$taskConfigPath = (Resolve-Path 'config-repo/group-purchase.yml').Path
$taskOriginalConfig = [System.IO.File]::ReadAllText($taskConfigPath)
try {
  [System.IO.File]::WriteAllText($taskConfigPath,
    $taskOriginalConfig.Replace('002-v1', '002-manual-test'),
    [System.Text.UTF8Encoding]::new($false))

  # 재시작 전에는 002-v1
  Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
  docker compose restart group-purchase-service
  docker compose up -d --no-build --wait --wait-timeout 120
  # 재시작 후에는 002-manual-test
  Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
}
finally {
  [System.IO.File]::WriteAllText($taskConfigPath, $taskOriginalConfig,
    [System.Text.UTF8Encoding]::new($false))
  docker compose restart group-purchase-service
  docker compose up -d --no-build --wait --wait-timeout 120
}
# 원복 후에는 002-v1
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
```

이 테스트 중 다른 터미널에서 같은 설정 파일을 수정하지 않는다. 설정 파일은 이미지에 포함되지 않으므로 이 변경에는 이미지 재빌드가 필요하지 않다.

### 6. 종료와 다음 실행

```powershell
docker compose down
```

이 Compose 프로젝트의 컨테이너·네트워크를 제거한다. 원본 설정 파일과 로컬 이미지는 유지한다. 다음에는 준비 단계의 `up` 명령으로 다시 실행한다. 이번 단계는 영속 DB나 업무 테스트 데이터를 만들지 않는다.

## 한계와 다음 단계

- native 설정은 현재 체크아웃의 파일을 제공한다. Config 다중 인스턴스·Git label·동적 refresh·외부 비밀값 관리 제품은 적용하지 않았다.
- Config의 저장소 헬스는 현재 첫 클라이언트의 local/docker 설정을 대상으로 한다. 후속 서비스를 추가할 때 검증 대상도 확장한다.
- 외부 인터넷에 공개하는 배포 환경·운영용 설정 접근 인증·TLS는 이번 로컬 실행 검증 범위에 포함하지 않는다.
- 로컬 검증과 구현 커밋의 GitHub CI 두 검사 성공을 확인했다. 후속 커밋의 검증은 PR의 최신 Checks를 기준으로 확인한다.
- 다음 구현 대상은 Eureka Server이며, 진행 전에 해당 상세 계획을 검토한다.

## 근거

- [Spring Cloud 호환표와 BOM](https://spring.io/projects/spring-cloud/)
- [Config native 파일 백엔드](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/file-system-backend.html)
- [Config Client의 Config Data Import](https://docs.spring.io/spring-cloud-config/reference/client.html)
- [Config Server 저장소 헬스](https://docs.spring.io/spring-cloud-config/reference/server/health-indicator.html)
- [Boot 4.0 Actuator 노출 범위](https://docs.spring.io/spring-boot/4.0/reference/actuator/endpoints.html)
- [Compose 준비 상태 대기](https://docs.docker.com/compose/how-tos/startup-order/)
