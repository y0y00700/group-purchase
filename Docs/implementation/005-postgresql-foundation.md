# 005. PostgreSQL 실행 기반 — 구현·최종 검증 결과

검증일: 2026-11-04 KST · 상태: **005 로컬 구현·최종 검증 완료**. 후속 사용자 요청에 따른 커밋·PR과 원격 CI는 [후속 PR 기록](#runtime-pr)에서 구분한다.

[계획·채택한 추천안](plan/005-postgresql-foundation.md) · [인계](../handoff.md)

<a id="runtime-pr"></a>

## 장애 복구·최종 검증 후속 PR — 2026-11-04

로컬 구현 완료 후 사용자가 PR 진행을 요청했다. GitHub 연결 도구는 저장소/PR 조회에 404를 반환해 인증된 `gh`로 확인했다. PR #6 MERGED, 최신 main `3d601bd50a1c2d6bd829940b3f26202ea125d9c3`와 기존 head의 CI 두 검사 성공, 열린 PR 없음을 확인했다. fetch한 main과 기존 로컬 HEAD `3d5cb0d`의 파일 내용은 같으므로 최신 main에서 `test/postgresql-runtime-recovery`를 만들고 기존 미커밋 구현·문서·사용자 규칙을 보존했다. merge/rebase·기존 PR 변경·PR 병합은 하지 않는다.

이번 변경은 opt-in 관찰 JAR·Windows 장애/복구 및 전체 down/up 검사, 최종 결과·직접 테스트 문서, 구현 완료 후 학습 문서 자동 갱신 규칙이다. 로컬 최종 보고서 `passed=true`·정리 오류 없음, 공동구매 22개/Gateway 4개 XML의 실패·오류·skip 0과 제품 JAR의 관찰 코드 미포함을 확인했다. 아래 실제 실행 근거를 재사용하며 PR 준비를 위해 DB 중단이나 전체 검증을 반복하지 않았다. Python 문법·변경 형식·secret 제외를 별도 확인한다.

main 대상 후속 PR을 생성하고 최종 head의 `Build and test`·`Images and Compose smoke test`를 확인한다. 기존 main CI와 이번 변경의 CI를 구분한다. Windows 전용 전체 장애 검사는 로컬 실측 근거이며 현재 Linux CI 두 job에서 실행하는 검사가 아니다. PR 링크와 최종 Checks 위치는 생성 후 이 절과 짧은 인계에 기록한다. Git 제외 학습 노트·secret·원시 로그·생성 JAR은 PR에 포함하지 않는다.

<a id="runtime-final-results"></a>

## 장애 복구·전체 연동 최종 검증 — 2026-11-04

착수 시 로컬 `feat/postgresql-foundation` / `3d5cb0d93f06e57f32b557c38e733cc46afe6e3b`와 사용자 미커밋 `AGENTS.md`·구현 안내 변경을 보존했다. GitHub 연결 도구의 404 이후 인증된 `gh`로 읽기 전용 조회했다. [PR #6](https://github.com/y0y00700/group-purchase/pull/6)은 MERGED/base main, 기존 head의 [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/41572899963), 최신 main `3d601bd50a1c2d6bd829940b3f26202ea125d9c3`의 [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/41585524931)을 확인했다. 이번 미커밋 변경의 원격 CI 성공으로 해석하지 않는다. 브랜치 이동·merge/rebase·커밋·push·PR 변경·원격 CI 실행은 하지 않았다.

### 추가 구현과 재사용

- [실행 검사](../../scripts/check-runtime-recovery.py)는 local 실행→DB 장애/복구→일반 제품 JAR→전체 Compose down/up→복구/정리를 수행한다. 단계별 상한·monotonic 실측·전체/단계 누적 시간을 JSON으로 남긴다.
- [관찰 launcher](../../scripts/runtime-probe/com/example/runtimeprobe/RuntimeProbe.java)는 실제 공동구매/Gateway 앱을 기동하고 **같은 context**의 DataSource·DiscoveryClient와 Gateway의 실제 서비스별 LoadBalancer supplier를 읽는다. 별도 DriverManager/psql로 앱 SQL 복구를 대체하지 않는다. 테스트 관찰 포트는 `127.0.0.1`에만 바인딩한다.
- 두 모듈의 opt-in `runtimeProbeJar`만 관찰 코드를 포함한다. 실제 일반 bootJar ZIP을 검사해 두 제품 JAR 모두 `runtimeprobe` 항목이 없음을 확인했다. 제품 API·ping·Compose·DB/secret·권한·JDBC/Flyway 설정·V1을 변경하지 않았다.
- 기존 22개 공동구매 통합 테스트, 전체 build/초기화 격리·기동 거절·checksum·secret 비노출 검증은 관련 제품 변경이 없어 재사용했다. 이번 `gradlew.bat build`도 **13초 성공, 29 task 중 28 UP-TO-DATE**였다. 기존 테스트 결과는 실제 재실행과 구분한다. XML의 공동구매 22·Gateway 4 실패/오류/skip 0을 확인했다.
- 관찰 JAR 빌드는 두 모듈 모두 성공했다. Python 문법 검사·`git diff --check`도 통과했다. 최초 Gradle 스크립트의 BootJar 클래스 접근/Java target 설정 오류를 수정했다. 첫 실행에서는 Gateway 하위 context 이벤트까지 세어서 주 context 횟수 검사에 실패했으며, 주 context 객체와 일치하는 이벤트만 세도록 수정한 뒤 재실행했다. 실패 실행도 소유 PID 종료·다섯 healthy/secret/env 유지 정리가 통과했다.
- 최종 포장 검토에서 Docker build context에 복사하지 않는 별도 Gradle 스크립트 참조를 발견해, opt-in 작업 정의를 기존 루트 `build.gradle` 안으로 옮겼다. 모듈/Dockerfile에 새 파일 복사 의존을 만들지 않았다. 이후 `build`와 두 `runtimeProbeJar`는 **4초 성공·33 task 전부 UP-TO-DATE**로 제품·관찰 JAR/기존 테스트가 불변임을 확인했다. 근거 `.tmp/005-final-build-packaging.txt`.
- 이어 `docker compose build group-purchase-service api-gateway`가 성공했다(각 내부 Gradle 1분 36초·1분 40초). Docker build에는 관찰 Java 소스를 복사하지 않아도 일반 bootJar가 정상 생성된다. 최종 `compose up --no-deps --wait`는 두 기존 컨테이너를 Running/Healthy로 유지했고 재생성하지 않았다. DB application 권한·Flyway 검사, Config에서 등록 주소 도달성, Gateway ping 200·비공개 경로/메서드 404가 다시 통과했다. 근거 `.tmp/005-final-image-build.txt`·`005-final-image-runtime.txt`. 이 마지막 이미지 포장 점검과 앞선 동일 프로세스 장애 복구의 관찰 시점을 구분한다.

### DB 장애·복구 실측

DB stop **완료 후** 120.033초 중단했다. 아래 대기 시간은 직전 단계 완료 뒤 독립 관찰을 시작한 시간이며, 각 캐시 자체의 전파 지연을 역산한 값은 아니다. 누적 열은 중단 단계에서는 stop 완료부터, 복구 단계에서는 start 명령 직전부터 잰 초다. 운영 heartbeat/캐시 주기는 가속하지 않았다.

| 단계 | 관찰 대기(초) | 단계 누적(초) | 관찰 상한(초) |
|---|---:|---:|---:|
| 중단: 앱 health DOWN/503 | 3.046 | 3.046 | 60 |
| 같은 앱 DataSource SQL 실패 | 3.044 | 6.090 | 60 |
| Eureka 응답 DOWN | 54.098 | 60.206 | 180 |
| 공동구매 DiscoveryClient 대상 제외 | 27.527 | 87.734 | 180 |
| Gateway DiscoveryClient 대상 제외 | 0.005 | 87.739 | 180 |
| Gateway LoadBalancer supplier count=0 | 0.006 | 87.745 | 180 |
| 복구: DB healthy | 5.998 | 5.998 | 120 |
| 같은 앱 health UP/200 | 0.015 | 6.013 | 60 |
| 같은 앱 DataSource 새 SQL 성공 | 0.007 | 6.019 | 60 |
| Eureka 응답 UP | 23.480 | 29.509 | 180 |
| 공동구매 DiscoveryClient 대상 발견 | 28.360 | 57.870 | 180 |
| Gateway DiscoveryClient 대상 발견 | 0.003 | 57.873 | 180 |
| Gateway supplier count=1 | 0.029 | 57.902 | 180 |
| Gateway 전달/비공개 경로·메서드 회귀 | 0.428 | 58.330 | 180 |

중단 중 직접 ping은 200이었다. Gateway는 캐시 제외 뒤 503을 **관찰**했으며 장애 중 특정 코드 보장 계약을 추가하지 않았다. 상태 전파 중 ping 전달이 성공할 가능성도 유지한다.

앱 DataSource에서 매 요청 `getConnection()` 후 실제 SQL로 `current_user=group_purchase_app`, DB `group_purchase`, schema `app`를 확인했다. 장애 전 backend PID 691 / postmaster 시작 `2026-11-04 09:50:59.157523+09`, 복구 후 backend PID 36 / 시작 `2026-11-04 10:01:01.217393+09`다. DB 서버 epoch가 바뀌었으므로 장애 전 물리 세션이 살아 있었던 결과가 아니다. backend PID 숫자는 재사용 가능하므로 숫자 차이만으로 새 연결을 판정하지 않았다.

전후 identity 응답 문자열 전체를 비교해 아래 값이 동일함을 확인했다. 장애 유지 중에도 반복 비교했고 `refresh=1;close=0;active=true`가 변하지 않았다.

| 관찰 | 공동구매 | Gateway |
|---|---|---|
| Windows PID | 7340 | 20260 |
| OS 프로세스 시작(UTC) | 2026-11-04T00:57:42.138Z | 2026-11-04T00:57:42.152Z |
| 주 context UUID | dedf28fe-7a96-44df-8a2d-2451d5cdf5a2 | 2f87dbe6-0cd0-4bba-ad57-5da9c5317a81 |
| 주 context identityHash | 1219637204 | 1442342557 |
| context startup epoch ms | 1793753866604 | 1793753866413 |

### Windows 제품 JAR·전체 down/up·최종 정리

같은 검사에서 관찰 JAR을 종료한 뒤 **일반 제품 bootJar**를 Windows에서 별도로 실행했다. 공동구매 PID 27060·Gateway PID 13244이며 DB·Config·Eureka는 Compose로 유지했다. 공동구매 자식 환경에만 기존 파일의 비밀번호를 넣고 Gateway에는 제공하지 않았다. 두 `/actuator/info`의 environment=local, JDBC/Flyway 정상 검증·V1 재적용 없음·전체 이력 동일, Eureka localhost 등록과 Gateway ping/비공개 경로·메서드 회귀를 확인했다. 제품 공동구매 health는 기동 관찰 15.535초, 이어 Gateway health 2.505초·Eureka UP 2.037초에 확인했다. 실제 SQL·물리 연결 복구는 위 관찰 JAR의 같은 앱 context에서 검증한 근거와 구분한다.

전체 Compose `down`은 1.722초, 직전부터 잰 down/up 누적 **41.493초**에 다섯 healthy가 됐다(up 상한 180초). 이후 **46.667초**에 난수 probe 값·Flyway 전체 행(스키마 생성 이력 포함)·volume inspect 전체 동일성과 계정 접근 재검사가 통과했다. 일반 `group-purchase_postgres-data`를 제거하지 않았다. Flyway 이력 SHA-256은 최초 기준 `dc917bc0e5dca6ac071aa1d22e456c6551bf9aff2fe2ec69ba0b32c0bc379faf`와 일치하며 이력 비교는 checksum만이 아닌 전체 행 문자열 비교다.

down/up 전후 각각 자기 DB 4쌍 성공·교차 DB 12쌍/시스템 DB 8쌍/SET ROLE 12쌍 거절·오암호 4쌍 거절·SCRAM verifier 5개 검사를 통과했다. Config 컨테이너에서 docker 등록 주소 health 도달성과 Gateway 자체 발견 캐시/실제 전달은 추가 대기 후 down/up 누적 **123.304초**에 통과했다. 건강 상태 확인만으로 발견 수렴을 판정하지 않았다.

전체 검사는 **465.084초, 종료 코드 0**, 최종 JSON `passed=true`, `cleanupErrors=[]`다. 검사 schema `probe_runtime_e3acfb718b9d3edf`를 삭제하고 pg_namespace 0행을 확인했다. 네 local Java PID를 모두 종료하고 자식 secret 환경을 해제했다. Python 부모 환경 전체와 secret 다섯 파일의 SHA-256 전후 동일, local probe/product 로그에 실제 secret 값 미노출을 확인했다. 마지막 finally에서 다섯 Compose healthy·docker 단일 등록/도달성·Gateway 전달을 다시 확인했다. 종료 후 구성은 기존 다섯 Compose 서비스이며 검사용 loopback 서버는 남지 않는다.

별도 최종 감사에서도 보고서 hash와 실제 secret 파일·Flyway 전체 이력을 재대조하고 소유 schema 부재를 확인했다. 두 실행 시도의 소유 local Java PID 잔여 0, 18081/18082 listener 잔여 0이었다. JSON은 최종 PASS 출력 직전 465.082초에 기록됐다. 초기에 schema를 기록하는 비상 정리용 manifest와 부분 CREATE 실패 시 정리 범위도 보완했으며, 강제 OS 종료를 주입한 검사는 아니다.

제품/설정/migration이 변하지 않아 기존 격리 오류 SQL·checksum·기동 실패 검증을 다시 훼손하거나 반복하지 않았다. 일반 DB 이력 변조·자동 clean/repair·일반 볼륨 삭제·기존 secret 변경은 없다. 요청된 로컬 완료 기준의 미검증 항목은 없다. 이번 미커밋 변경의 원격 CI, HA·백업 복구·강제 OS 종료 장애 주입은 이번 완료 기준 밖이며 실행하지 않았다. 마지막 강제 종료용 절차는 비상 사용자 안내로, 실제 통과 근거인 정상/실패 finally 정리와 구분한다. 006은 구현하지 않았다.

원시 근거는 Git 제외 `.tmp/005-final-runtime.txt`·`005-final-runtime.json`, 두 local probe/product 로그, `005-final-jars-explicit.txt`·`005-final-build.txt`다. 이전 실패 시도는 `005-final-runtime-attempt1.*`에 분리했다. 기술 개요·프로젝트 적용 예시는 [PostgreSQL·Flyway 학습 문서](../explanations/postgresql-flyway.md)에 있다(기존 정책대로 Git 제외).

<a id="runtime-manual-tests"></a>

## 사용자 직접 테스트 — 장애 복구·Windows JAR·전체 down/up

준비: Windows PowerShell·Python 3·JDK 21/JAVA_HOME·Docker Desktop Linux 엔진. 일반 `group-purchase_postgres-data`와 기존 secret 다섯 파일을 사용한다. 검사 중 다른 업무를 실행하지 않고, 다른 local JAR은 종료한다. 5432·8888·8761·8080·8000·9000과 검사 전용 loopback 18081·18082를 사용한다. 시작 시 Docker 공동구매·Gateway를 멈추고 종료 시 다섯 Compose 서비스로 복구하므로 서비스 중단 시간이 있다. 최소 DB 장애는 120초이며 캐시별 대기로 전체 검사는 수 분 걸린다.

프로젝트 루트에서 아래 명령을 복사한다. `JAVA_HOME`은 실제 설치된 JDK 21 경로로 지정한다. 원래 환경변수는 finally에서 복원한다. 비밀번호는 스크립트가 파일에서 읽어 공동구매 자식 프로세스 환경에만 전달하며 인자/화면에 출력하지 않는다. Gateway에는 전달하지 않는다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = 'C:\Users\권민혁\.jdks\ms-21.0.12.1'
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed' }
    .\gradlew.bat :group-purchase-service:bootJar :api-gateway:bootJar :group-purchase-service:runtimeProbeJar :api-gateway:runtimeProbeJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'JAR build failed' }
    # 앱 이미지가 없다면 먼저 docker compose build를 실행한다.
    New-Item -ItemType Directory -Force .tmp | Out-Null
    python scripts/check-runtime-recovery.py 2>&1 | Tee-Object .tmp/005-final-runtime.txt
    if ($LASTEXITCODE -ne 0) { throw 'Runtime verification failed; inspect report and cleanupErrors' }
    Get-Content -Raw .tmp/005-final-runtime.json | ConvertFrom-Json | Select-Object passed,totalSeconds,cleanupErrors
    docker compose ps
} finally {
    $env:JAVA_HOME = $previousJavaHome
}
```

정상 기대: 마지막 `PASS all requested runtime checks`, 종료 코드 0, JSON `passed=true`, `cleanupErrors=[]`, 다섯 서비스 healthy. 같은 보고서의 `events`에 각 관찰의 실제 대기·상한·전체/단계 누적 초가 남는다. 검사 전용 JAR은 실제 앱을 기동하지만 loopback 관찰 코드는 일반 제품 JAR·이미지에 포함되지 않는다. 이후 일반 제품 JAR 두 개도 별도로 local 기동·Flyway 검증·등록/전달을 확인한다.

중요 판정:

- DB stop 뒤 health 503/DOWN·앱 DataSource SQL 실패, 120초 이상 중단 유지. 직접 ping 200은 정상이다.
- Eureka DOWN, 공동구매·Gateway 각 DiscoveryClient의 빈 목록, Gateway supplier count=0을 각각 기다린다. 장애 중 Gateway 응답은 관찰값이며 특정 코드를 보장하지 않는다.
- start 후 health 200/UP, 같은 앱 DataSource에서 새 연결을 얻은 SQL이 `group_purchase_app|group_purchase|app|...` 반환. DB postmaster 시작 시각이 바뀌어 이전 서버의 물리 세션을 재사용한 결과가 아님을 확인한다.
- 앱/Gateway PID·OS 시작 시각·주 context UUID/hash/시작 시각·refresh=1/close=0/active=true 문자열 전체가 전후 동일해야 한다. 그 뒤 Eureka UP·두 DiscoveryClient·Gateway supplier count=1과 실제 전달을 각각 확인한다.
- 전체 Compose down/up 전후 난수 probe·Flyway **전체 행**·볼륨 inspect가 동일하고, 네 계정의 자기 DB 접근과 타 DB/역할 접근 거절이 다시 통과해야 한다.

도구/포트/secret 누락, 관찰 timeout, SQL 실패, 이력 변경은 실패다. timeout만으로 복구를 통과시키지 않으며 일반 DB에 오류 migration을 적용하거나 이력을 repair하지 않는다. 정상/실패 종료의 finally는 자신이 시작한 local PID를 종료하고, postgres를 포함한 다섯 Compose 서비스를 복구하며 검사 probe만 제거한다. 원래 Python/PowerShell 환경변수·secret 파일은 보존한다. 정상 종료 때 로그·JSON은 `.tmp`에 남는 검증 근거이며 DB 검사 데이터는 남지 않는다.

검사 도중 다른 PowerShell에서 현재 단계만 관찰할 수 있다(이때 보이는 값은 단계에 따라 바뀐다).

```powershell
Get-Content .tmp/005-final-runtime.txt -Tail 15 -Wait
# local 관찰 JAR이 살아 있는 단계에서만:
curl.exe -i http://localhost:8080/actuator/health
curl.exe -s http://127.0.0.1:18081/identity
curl.exe -s http://127.0.0.1:18081/sql
curl.exe -s http://127.0.0.1:18081/discovery
curl.exe -s http://127.0.0.1:18082/discovery
curl.exe -s http://127.0.0.1:18082/supplier
curl.exe -i http://localhost:8000/api/group-purchases/ping
```

정상 종료 후 운영 확인·종료·재시작:

```powershell
python scripts/check-postgres.py --stage application
python scripts/check-discovery.py
python scripts/check-gateway.py
# 종료가 필요할 때만: 볼륨·secret을 보존한다.
docker compose down
# 같은 데이터로 복구
docker compose up -d --wait --wait-timeout 180
python scripts/check-postgres.py --stage application
python scripts/check-discovery.py
python scripts/check-gateway.py
```

Ctrl+C라면 finally의 복구가 끝날 때까지 기다린다. 터미널/프로세스 강제 종료는 finally 실행을 보장하지 않는다. 그 경우 출력 로그의 `started local JAR` PID와 현재 `Get-CimInstance Win32_Process -Filter "ProcessId = <기록된 PID>"`의 CommandLine을 대조해 이 프로젝트 JAR인 프로세스만 `Stop-Process -Id <확인한 PID>`로 종료한다. 전체 Java 프로세스를 종료하지 않는다. 그 뒤 위 Compose up 명령과 `docker compose restart eureka-server`, 발견/Gateway 검사를 실행해 local 등록 잔상을 제거하고 docker 주소 수렴을 기다린다. 보고서의 `probeSchema`가 남았다면 같은 schema만 삭제한다(아래는 강제 종료 시 사용자 복구용이며 이번 정상 경로의 자동 finally와 별개다).

```powershell
$report = Get-Content -Raw .tmp/005-final-runtime.json | ConvertFrom-Json
$schema = $report.probeSchema
if ($schema -notmatch '^probe_runtime_[0-9a-f]{16}$') { throw 'Unexpected probe schema' }
@"
import importlib.util
s = importlib.util.spec_from_file_location('pg', 'scripts/check-postgres.py')
p = importlib.util.module_from_spec(s)
s.loader.exec_module(p)
p.client(p.sql('group_purchase', 'DROP SCHEMA IF EXISTS $schema CASCADE'))
"@ | python -
```

검사는 자원 변경 전에 JSON에 소유 schema를 먼저 기록한다. 자식 PID는 출력 로그에 남긴다. 일반 app 스키마나 볼륨을 제거하지 않는다. 오류 SQL·checksum의 직접 테스트는 기존 [격리 연결 테스트](#jdbc-flyway-manual-tests)를 사용한다.

## 구현 범위

단일 `postgres:18.6-bookworm`에 `member/member_app`, `group_purchase/group_purchase_app`, `payment/payment_app`, `notification/notification_app`을 만들었다. 계정은 자기 DB 소유자이며 관리자 권한·다른 역할 멤버십을 갖지 않는다. 업무 DB와 `postgres`, `template1`의 PUBLIC DB 권한 및 업무 DB의 PUBLIC `public` 스키마 권한을 회수했다. `app` 스키마·업무 테이블·Flyway 이력은 생성하지 않는다.

- `compose.yml`: loopback 기본 5432, 내부 `postgres:5432`, `postgres-data` → `/var/lib/postgresql`, 다섯 secret, 초기화 마운트와 수락 healthcheck. `POSTGRES_PORT`는 격리 검사 시 충돌 회피용이다.
- `infrastructure/postgres/init/010-service-databases.sh`: 모든 secret의 파일·비어 있음·단일 printable line 검사 후 역할/DB/권한 생성. SQL은 `ON_ERROR_STOP`, 비밀번호는 환경변수→psql `\getenv`→인용한 SQL 리터럴로 전달한다. 파일은 LF이며 공식 entrypoint가 실행 권한 없는 `.sh`를 source하는 방식도 사용한다.
- `scripts/prepare-local-secrets.ps1` / `prepare-ci-secrets.py`: Git·Docker build 제외 `secrets/*_password`에 32바이트 난수를 hex로 저장. 없는 파일만 생성하고 기존 파일의 값은 유지하며 빈/잘못된 값은 실패한다. 비밀번호를 출력하지 않는다.
- `check-postgres.py`: 별도 Compose client에서 TCP/SCRAM SQL·거절 원인·권한을 검사한다. `--persistence`는 난수 probe 스키마/테이블/토큰을 만들고 DB 컨테이너만 재생성한 뒤 읽기·해당 probe 삭제·권한 재검사를 수행한다.
- `check-postgres-initialization.py`: 실행마다 새로운 `gp-db-check-<난수>` 프로젝트·볼륨·secret과 임의 loopback 포트를 사용한다. 누락·빈 값·부분 초기화·특수문자 비밀번호를 검사하고 자신이 만든 자원만 정리한다.
- CI Compose job에 secret 생성·권한/영속성 검사·job 전용 볼륨/secret 정리를 추가했다. 기존 CI가 새 secret 누락으로 실패하지 않도록 필요한 연동만 반영했다. 첫 스텝의 로컬 구현 당시에는 원격 실행을 하지 않았고, 이후 PR 진행은 아래 절에서 구분한다.

공동구매를 포함한 네 앱의 코드·의존성·Config 설정·DB 기동 의존 조건은 변경하지 않았다. JDBC·Flyway·Testcontainers·앱 DB 장애/복구·Windows JAR DB 연결은 후속 범위다. 전체 Gradle 재빌드는 이번 변경 범위에 없어 실행하지 않았다.

## 실제 검증과 근거

환경: Windows PowerShell, Python 3.13.15, Docker Engine 29.8.1, Compose v5.5.1, Linux containers. 이미지 pull 성공, 실제 digest:

```text
postgres@sha256:5c4783a8cc3f0074912164f94dfa1be4e043438f0f707cf968d7af9a0d31a2c8
```

| 실제 실행 | 결과 |
|---|---|
| secret 준비 두 번·기존 파일 해시 대조 | 생성 후 재실행에서 다섯 파일 내용 불변. 별도 임시 경로의 빈 기존 파일도 실패·보존 확인 |
| `docker compose config --quiet`, Python compile | 통과 |
| `docker compose up -d --no-deps --wait --wait-timeout 120 postgres` | 새 일반 볼륨 초기화·healthy·loopback 5432 확인 |
| `python scripts/check-postgres.py --persistence` | 자기 DB 4쌍 성공, 타 업무 DB 12쌍·시스템 DB 8쌍 CONNECT 권한 거절, 다른 서비스 SET ROLE 12쌍 거절, 오암호 4쌍 인증 거절, 다섯 SCRAM verifier 확인 |
| 같은 검사 내 DB 컨테이너 강제 재생성 | probe 토큰 유지·probe 스키마 삭제·접근 검사 재통과 |
| `python scripts/check-postgres-initialization.py` | 누락/빈 secret 초기화 중단, 부분 초기화 중단 및 재시작 시 자동 복구되지 않음, SQL/셸 특수문자 비밀번호 인증·권한 검사 통과, 임시 자원 정리 |
| `python scripts/check-discovery.py`, `python scripts/check-gateway.py` | 등록 주소 UP/도달성·Gateway ping 200·비공개 경로/메서드 404 통과 |
| `docker compose ps` | 기존 네 서비스와 PostgreSQL 모두 healthy |

검사 구현 중 Windows `text=True` 파이프의 CRLF 변환과 subshell의 비밀번호 환경변수 범위를 수정한 뒤 위 검사를 통과했다. 누락 secret은 이 Docker 환경에서 디렉터리로 마운트되어 Compose `up -d` 자체는 성공할 수 있었다. 초기화에서 일반 파일 여부까지 검사해 중단한다. 다른 Docker 환경에서 Compose가 먼저 거부하는 경우도 정상 실패로 판정한다.

`pg_isready`는 서버 수락 검사다. 유효한 계정·완성된 초기화의 증거는 TCP 권한 검사다. 부분 초기화 실패 후 재시작하면 서버만 healthy일 수 있음을 별도 격리 검사에서 실제 확인했다.

착수 기준: PR #4 OPEN, head `b94dcd412a31fa303f04b695c73903da205159c0`의 [CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41437130117), main `a2a88dce374c1ee7d93cac16fe35c0a047456f19`의 [CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41402512856)을 재확인했다. 이 head에서 `feat/postgresql-foundation`을 생성했다. 기존 미커밋 문서 변경을 보존하고 관련 상태만 갱신했다. 커밋·push·PR 생성/병합은 하지 않았다.

## PR과 원격 검증

2026-11-03 후속 PR 요청으로 구현·관련 문서를 `c69cf8d`에 커밋·push하고 [PR #5](https://github.com/y0y00700/group-purchase/pull/5)를 생성했다. 범위는 이 결과의 첫 DB·secret·계정·권한 스텝이다. JDBC·Flyway 연결 설계는 문서로 포함하며 앱 연결 코드는 없다. 로컬 전용 학습/협업 기록·실제 secret·캐시는 제외했다.

- base는 아직 열려 있는 PR #4의 `feat/api-gateway`다. Gateway 코드 중복을 피하고 #4 병합 뒤 최신 main과 대조해 base를 전환한다. PR #4·#5는 자동 병합하지 않는다.
- 이 workflow의 PR 자동 실행 조건은 `main`이므로, 005 head 브랜치에서 `workflow_dispatch`로 Build and test·Images and Compose smoke test를 실행한다. PR 링크 문서 반영 후의 head를 검증 대상으로 삼는다.
- 이번 PR 준비에서는 Compose 정의·Python 3개 구문·init LF/인코딩·secret Git 제외·문서 링크와 변경 형식을 확인했다. 앞 절의 로컬 DB·장애 주입 검증과 구분하며 전체 Gradle/이미지 빌드는 원격 CI에서 확인한다.
- 원격 실행의 정확한 head SHA·run URL·최종 결과는 [PR #5 설명과 Checks](https://github.com/y0y00700/group-purchase/pull/5/checks)에 기록·확인한다. 기존 PR #4의 성공을 005 변경의 성공으로 사용하지 않는다.

<a id="manual-tests"></a>

## 사용자 직접 테스트

준비: Docker Desktop Linux 엔진·Python 3·PowerShell. 아래 작업 디렉터리에서 실행한다. 기본 DB 포트 5432가 필요하며 기존 네 앱을 실행하면 8000·8080·8761·8888도 사용한다. 호스트 psql/JDK 설치는 DB 검사에 필요 없다. 최초 이미지는 인터넷에서 받는다. `COMPOSE_FILE`·`COMPOSE_PROJECT_NAME`·`POSTGRES_PORT`를 별도로 설정했다면 대상 프로젝트/포트가 달라짐을 확인한다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
.\scripts\prepare-local-secrets.ps1
docker compose config --quiet
docker compose up -d --no-deps --wait --wait-timeout 120 postgres
python scripts/check-postgres.py
```

기대: 첫 준비는 Created, 재실행은 Preserved. DB healthy 후 자기 DB 네 개 PASS, 교차 12쌍과 시스템 DB 8쌍은 `permission denied for database`로 구분한다. 오암호는 `password authentication failed`여야 한다. 일반 검사에서는 DB/계정 소유권·관리 권한 없음·역할 멤버십 없음·PUBLIC 스키마 권한 정리·`app` 미생성·SCRAM 저장 방식을 확인한다. 마지막 요약이 PASS이고 종료 코드 0이어야 한다. 이 스텝 검사는 후속 Flyway가 `app`을 만들 때 변경해야 한다.

DB 재생성은 해당 DB 접속을 잠시 끊는다. 다음은 이번에 실제 실행한 영속성 검사다. 일반 볼륨을 삭제하지 않으며 자신의 난수 probe만 삭제한다.

```powershell
python scripts/check-postgres.py --persistence
```

기대: `PASS: probe survived PostgreSQL container recreation`, `DROP SCHEMA`, 권한 검사 재통과. client마다 180초, DB healthy 대기는 120초 상한이다. 정리 실패가 있으면 오류를 고친 뒤 일반 검사를 재실행하고, 남은 `probe_<난수>`만 관리자가 확인해 제거한다. 다른 스키마를 일괄 삭제하지 않는다.

주요 실패 재현은 다음 격리 검사를 사용한다. **기존 secret을 비우거나 정상 볼륨을 삭제하지 않는다.** 이 명령은 실행 시 새로 만든 프로젝트의 볼륨만 `down -v`로 제거한다.

```powershell
python scripts/check-postgres-initialization.py
```

기대: missing/empty secret 초기화 중단, partial init 중단 및 재기동 후 누락 DB 유지, quoted secrets 인증·권한 PASS. 각 실패 기동 종료 대기는 45초, 재시작 healthy 대기는 60초 상한이다. 이 검사는 실패를 기대하는 시나리오에서도 스크립트 전체가 성공하면 종료 코드 0이다. 강제 종료로 정리가 중단되면 `docker compose ls -a`와 `docker volume ls`에서 **그 실행의 `gp-db-check-<난수>` 이름**을 확인해 해당 프로젝트만 정리한다. 일반 프로젝트 `group-purchase`에 `down -v`를 실행하지 않는다.

기존 앱이 실행 중이면 아래 회귀를 확인한다. 이번에는 실행 중인 네 앱에 대해 실제 통과했다. 앱까지 새로 빌드하는 전체 명령은 이번 스텝에서 재실행하지 않았다.

```powershell
python scripts/check-discovery.py
python scripts/check-gateway.py
docker compose ps
```

DB만 종료/재시작하려면:

```powershell
docker compose stop postgres
docker compose up -d --no-deps --wait --wait-timeout 120 postgres
python scripts/check-postgres.py
# 전체 종료가 필요할 때: DB 데이터 볼륨과 secret 파일을 보존한다.
docker compose down
```

정상 종료·재시작 명령은 사용자용 절차다. 이번 영속성 실측은 `--force-recreate`이며 전체 `down/up`을 따로 재측정하지 않았다.

## 초기화 실패·비밀번호 변경과 다음 작업

초기화는 빈 데이터 디렉터리에서만 실행된다. 기존 볼륨에서는 init 스크립트·secret 파일 변경으로 DB/계정/암호가 갱신되지 않는다. 누락·빈 파일은 준비 단계에서 고치고, 이미 실패한 볼륨은 로그와 SQL 상태를 확인해 관리자가 빠진 DB/권한을 복구한다. 자동 재실행·기본 비밀번호 대체는 없다. 재현 검증은 새 임시 프로젝트로 한다. 정상 데이터가 있는 볼륨은 삭제하지 않는다.

비밀번호 회전이 필요하면 관리자 세션 `docker compose exec postgres psql -U postgres -d postgres`에서 `\password member_app`처럼 대화형 변경을 수행하고, 같은 새 값을 해당 `secrets/member_password` 파일에 출력·Git 기록 없이 갱신한다. 후속 앱이 연결되면 앱 secret도 동기화하고 해당 앱을 재시작한다. 관리자 암호 변경도 DB 변경과 `postgres_password` 파일 갱신이 모두 필요하다. 회전 절차 자체는 이번 스텝에서 실행하지 않았다.

공식 근거: [PostgreSQL 이미지의 18+ 볼륨 경로·빈 디렉터리 초기화](https://hub.docker.com/_/postgres), [PUBLIC·CONNECT 기본 권한](https://www.postgresql.org/docs/18/ddl-priv.html).

다음 작업은 공동구매 JDBC·드라이버·Flyway와 `app` 스키마/이력, 서비스별 secret 주입, 실제 PostgreSQL 테스트 fixture와 기존 Config/Eureka 회귀, 앱 기동 실패/실행 중 복구와 Windows JAR 연동이다. 후속 코드 변경도 해당 head의 원격 CI를 별도로 확인한다. 이번 결과를 005 전체 완료로 취급하지 않는다.


<a id="jdbc-flyway-progress"></a>

## JDBC·Flyway 연결 스텝 중간 인계 — 2026-11-03 (재개 전 기록)

사용자가 상세 설계를 채택하고 구현을 요청했다. 구현 도중 다음 세션 인계를 요청해 아래 상태로 남긴다. **이 절의 코드는 초안이며 연결 스텝은 미완료다.** 앞의 첫 DB 스텝 검증 결과와 구분한다.

변경한 범위는 공동구매 JDBC/Flyway/드라이버·Testcontainers 의존성, Config 공통 풀/Flyway 및 local/docker URL, docker 필수 configtree import, Compose 앱 전용 secret과 DB healthy 의존 조건, V1의 app 설명/PUBLIC 권한 정리다. `PostgresFixture`와 기존 Config/Eureka 테스트 연결도 추가했다. 테스트용 in-process Config Server/observer는 공동구매 테스트 classpath의 JDBC auto-config 영향을 피하도록 JDBC/Flyway auto-config를 제외하며 실제 공동구매 앱은 제외하지 않는다. DB 검사는 database/application 단계와 application의 V1 이력 보존 비교를 추가했고 CI 호출도 조정했다.

실제 실행한 검증:

```powershell
.\gradlew.bat :group-purchase-service:dependencies --configuration testRuntimeClasspath --console=plain
.\gradlew.bat :group-purchase-service:compileTestJava --console=plain
```

의존성 해석은 Flyway 11.14.1, PostgreSQL JDBC 42.7.14, Testcontainers 2.0.5를 확인했고 테스트 코드 컴파일은 성공했다. 로컬 진단 파일은 `.tmp/005-dependencies.txt`, `.tmp/005-compile.txt`이며 Git 제외다. Java/Boot/Cloud/Gradle 버전은 변경하지 않았다.

아직 실행하지 않은 항목: fixture 실제 기동/정리, 기존 테스트 실행, 새 정상/실패/migration 통합 테스트 작성·실행, 변경한 Python DB 검사, Compose 앱 이미지 빌드/재기동과 실제 Flyway 적용, 전체 build·회귀·이번 변경의 원격 CI. 상세 설계가 요구한 local/docker 주입·누락/빈 값/오암호·DB 부재의 60초 내 원인 있는 실패·오류 SQL/checksum 거절·재기동 이력 불변·비밀값 미노출을 다음 세션에서 구현·확인해야 한다. 현재 코드를 검증 완료로 취급하지 않는다.

다음 세션의 시작 명령(아직 통과하지 않은 절차):

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
.\gradlew.bat :group-purchase-service:test --console=plain
```

Docker Desktop Linux 엔진과 JDK 21/JAVA_HOME이 필요하다. fixture는 테스트 전용 DB·secret을 사용해야 하며 Docker가 없으면 실패를 명시한다. 통과 후 새 DB 통합 테스트와 전체 build, Compose/application 검사·직접 테스트 문서를 이어서 완성한다. 기존 일반 DB는 아직 Flyway를 적용하지 않았으므로 application 검사를 바로 실행하면 통과를 기대할 수 없다.

착수 시 PR #4·#5 MERGED, 최신 main `26c04834345f1d6edfaffee37755be729fbc29f8`의 [CI 실행](https://github.com/y0y00700/group-purchase/actions/runs/41564604744)은 진행 중이었다. 현재 로컬 HEAD는 `c69cf8d`를 유지하며 미커밋 변경을 보존했다. DB/secret 변경이나 컨테이너 재기동, 커밋·push·PR 작업은 하지 않았다. 장시간 DB 장애 복구·Windows JAR·전체 down/up·005 최종 검증은 후속이다.

<a id="jdbc-flyway-results"></a>

## JDBC·Flyway 연결 스텝 재개 결과 — 2026-11-03

중간 인계의 초안을 실제 PostgreSQL에서 검증하고 미완료 연결 테스트·직접 테스트 안내를 완성했다. 이번 완료 범위는 계획의 JDBC/Flyway 연결 스텝이다. 장시간 DB 장애 후 동일 PID 복구·Eureka 상태 수렴, Windows local JAR 전체 연동, 전체 down/up, 005 최종 검증과 이번 변경의 원격 CI는 후속이다.

착수 시 브랜치 `feat/postgresql-foundation`, HEAD `c69cf8d16a56a6b6efca72641221e709f0acebf3`와 기존 미커밋 변경을 보존했다. PR #4·#5 MERGED, 최신 main `26c04834345f1d6edfaffee37755be729fbc29f8`의 [CI 두 job 성공](https://github.com/y0y00700/group-purchase/actions/runs/41564604744)을 다시 확인했다. head의 별도 check-run은 없었다. 이 병합 main의 성공은 이번 미커밋 변경의 원격 검증이 아니다. 브랜치 이동·merge/rebase·커밋·push·PR 작업은 하지 않았다.

### 실제 변경

- 공동구매 모듈에만 JDBC/Flyway starter·PostgreSQL 모듈/드라이버와 Testcontainers를 추가했다. 실제 해석 버전은 Flyway 11.14.1·JDBC 42.7.14·Testcontainers 2.0.5다.
- Config는 서비스 계정명·환경별 URL·풀/timeout·Flyway 정책을 제공한다. local 비밀번호는 환경변수, docker는 필수 configtree에서 받는다. Compose는 공동구매 앱에 자기 secret 하나만 제공하고 postgres healthy를 기다린다.
- Flyway가 `app`과 이력 테이블을 만들고 V1은 스키마 설명·PUBLIC 권한을 정리한다. 업무 테이블은 없다. 자동 clean/repair·baseline과 SQL init으로 우회하지 않는다.
- `PostgresFixture`는 동일 이미지·init 및 임시 secret 다섯 개로 클래스별 격리 DB를 시작·정리한다. Config/Eureka 기존 테스트에도 연결했다. 테스트용 Config/observer에만 JDBC/Flyway auto-config를 제외한다. 공동구매 앱의 DB/Flyway는 실제 실행한다.
- 새 `PostgresConnectionIntegrationTests`는 local 환경변수 property source/docker configtree, 실제 SQL·V1·재기동·secret 비노출, 인증/DB/migration 기동 실패를 검증한다. 잘못된 SQL/checksum은 추가 임시 PostgreSQL에서만 실행한다. Docker가 없으면 실패하며 건너뛰지 않는다.
- DB 검사 기본 `database`는 초기화 직후 조건을 유지한다. `application`은 공동구매 app 소유권·PUBLIC 차단·성공 V1/checksum과 다른 세 DB의 app 미생성을 검사한다. 영속성 검사는 V1 행 전체도 비교한다. Compose CI 호출은 `--stage application --persistence`로 변경했다.

### 실제 검증

환경은 기존 Windows/JDK 21·Docker Desktop Linux 엔진이다. 제한 환경에서는 Gradle 캐시 잠금 파일·Docker named pipe 접근이 거절되어 같은 명령을 권한 있는 실행으로 수행했다. 프로젝트 설정이나 secret을 변경해 우회하지 않았다.

| 실행 | 실제 결과 |
|---|---|
| 먼저 `:group-purchase-service:test` | 인계 초안의 Config 11개·Eureka 2개, 총 13개 통과. fixture 시작·종료와 기존 실패 원인 검증 확인 |
| 새 `PostgresConnectionIntegrationTests` | 8개 테스트 통과. local은 `SPRING_DATASOURCE_PASSWORD`를 모델링한 Spring 환경 소스, docker는 실제 임시 configtree로 주입. 실제 Windows JAR 실행과 구분 |
| 정상 최초/재기동 | health 200/UP, SQL 사용자 group_purchase_app·DB group_purchase·schema app, 성공 V1/checksum 및 재기동 이력 불변 |
| 누락·빈 값·오암호 | local 누락/빈 값·docker 누락 파일/빈 값은 JDBC SQLSTATE 08004, 오암호는 28P01. docker 디렉터리 자체 부재는 ConfigDataResourceNotFoundException |
| DB 없음 | PostgreSQL 프로토콜에 응답하지 않는 임시 TCP endpoint에서 08001 기동 실패, 최종 실측 11,836ms. 60초 상한 내 실제 실패이며 단순 테스트 timeout 통과가 아님 |
| 오류 SQL·checksum | 별도 임시 DB에서 syntax error와 checksum mismatch를 구분해 기동 거절. 정상 V1 이력 불변, 자동 repair 없음 |
| 전체 `gradlew.bat build` | 성공. 공동구매 21개 실행, 전체 Gradle 결과 36개 실패 0. 변경 없는 다른 모듈의 유효한 테스트 결과는 UP-TO-DATE 재사용 |
| `check-postgres-initialization.py` | 새 임시 프로젝트에서 누락/빈 secret·부분 초기화 실패와 재시작 미복구·특수문자 암호 및 database 단계 권한 검사 통과. 자체 자원 정리 |
| 앱 이미지 build·Compose 기동 | 새 공동구매 이미지 빌드 성공, 기존 일반 DB에 V1 적용 후 다섯 서비스 healthy. 나머지 세 앱의 이미지는 기존 검증본 유지 |
| `check-postgres.py --stage application --persistence` | 자기 DB 4쌍·교차 거절 12쌍·시스템 DB 8쌍·SET ROLE 거절 12쌍·오암호 4쌍·SCRAM 5개 통과. 같은 일반 볼륨으로 DB 재생성 후 probe와 V1 행 전체 유지, probe 정리 |
| Compose 공동구매 재시작 | 재시작 후 health UP·V1 행 전체 불변 |
| 민감정보 | 테스트 INFO 로그·HTTP 응답에 fixture 암호 미노출. 실제 Compose 전체 로그·Config local/docker 응답·Actuator health/info/env/configprops·공개 ping에서 다섯 로컬 secret 미노출. 기존 secret 파일 다섯 개의 전후 SHA-256 동일, 앱의 유일한 mount는 자기 secret |
| 발견·Gateway 회귀 | `check-discovery.py`에서 단일 UP/등록 주소 도달성, `check-gateway.py`에서 ping 200·비공개 경로/메서드 404 통과. 각각 자기 캐시 대기 유지 |

첫 새 테스트 실행은 한국어 JDBC 메시지를 영어 `password`로 판정해서 실패했다. 오류 원인은 이미 정상적으로 거절되고 있었으며, 로케일 독립 SQLSTATE로 검사하도록 수정했다. docker 누락 디렉터리도 실제 예외 메시지 `cannot be found`와 맞췄다. 최종 테스트는 이 수정 후 통과했다.

최종 기동 실패 실측: 누락/빈 값 1,637~1,793ms, 오암호 1,693~1,820ms, 누락 configtree 디렉터리 63ms, 오류 SQL 757ms, checksum mismatch 734ms였다. 각 실패의 원인 확인과 60초 미만 경과 시간 검사를 함께 통과했다.

로컬 근거는 Git 제외 `.tmp/005-resume-tests.txt`, `005-connection-tests.txt`, `005-build.txt`, `005-initialization.txt`, `005-image-build.txt`, `005-compose-up.txt`, `005-application.txt`, `005-runtime.txt`, `005-discovery.txt`, `005-gateway.txt`와 모듈별 `build/reports/tests/test/index.html`이다. 정상 볼륨 삭제·이력 변조·secret 갱신을 수행하지 않았다. 연결 스텝의 의도한 DB 변경은 app 스키마/V1 적용이며, 영속성 검사의 난수 probe는 제거했다.

<a id="jdbc-flyway-manual-tests"></a>

### 사용자 직접 테스트 — 연결 스텝

준비: Docker Desktop Linux 엔진·Python 3·PowerShell. Gradle 테스트에는 JDK 21/JAVA_HOME과 Docker 접근 권한이 필요하다. 앱이 사용하는 포트는 5432·8000·8080·8761·8888이다. 최초 의존성/이미지 다운로드에는 인터넷이 필요하다. 실제 비밀번호를 명령 인자에 쓰거나 출력하지 않는다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
# 기존 파일은 보존한다. 이 세션에서는 기존 secret을 그대로 사용했다.
.\scripts\prepare-local-secrets.ps1
docker compose config --quiet
# 이번 실측: 기존 Config/Eureka/Gateway/PostgreSQL에 새 공동구매 이미지를 연결했다.
docker compose build group-purchase-service
docker compose up -d --no-deps --wait --wait-timeout 180 group-purchase-service
python scripts/check-postgres.py --stage application
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
python scripts/check-discovery.py
python scripts/check-gateway.py
```

사전 서비스가 없다면 `docker compose up --build -d --wait --wait-timeout 180`으로 다섯 서비스를 시작한다. 이 전체 재빌드 명령은 사용자용이며 이번 세션에서는 공동구매 이미지만 새로 빌드했다. 정상 기대는 다섯 healthy, health UP, info의 environment docker, DB 검사 PASS·종료 코드 0이다. 이미 Flyway가 적용된 일반 DB에 기본 `--stage database`를 실행하면 app 미생성 조건 때문에 실패하는 것이 정상이다.

다음 자동 검증은 이번에 실제 실행했다. 테스트마다 임시 PostgreSQL·secret을 만들고 정리하며 일반 볼륨·secret을 사용하지 않는다.

```powershell
.\gradlew.bat :group-purchase-service:test --tests '*PostgresConnectionIntegrationTests' --console=plain
.\gradlew.bat build --console=plain
python scripts/check-postgres-initialization.py
```

기대: 현재 연결 테스트 9개 및 전체 build 성공(위 재개 시점 결과는 8개이며 후속 대조에서 1개 추가). 누락/빈 비밀번호·오암호·DB 부재·오류 SQL·checksum은 앱 기동 거절이 기대 결과다. 테스트 로그의 `Verified startup rejection`에는 원인과 실제 ms가 표시되며 각 기동 실패는 60초 미만이어야 한다. 60초는 이 테스트의 관찰 상한이며 전체 앱 기동 시간 보장이 아니다. 정상 DB의 migration 파일을 고쳐 실패를 재현하지 않는다. 초기화 검사는 자신이 만든 `gp-db-check-<난수>`만 정리한다.

데이터 유지 검사는 DB 컨테이너를 재생성해 접속을 잠시 끊는다. 사용 중 작업이 없는 때 실행한다. 첫 DB 스텝의 일반 볼륨은 보존하며 검사 자신의 probe만 지운다.

```powershell
python scripts/check-postgres.py --stage application --persistence
docker compose restart group-purchase-service
docker compose up -d --no-deps --wait --wait-timeout 120 group-purchase-service
python scripts/check-postgres.py --stage application
python scripts/check-discovery.py
python scripts/check-gateway.py
```

기대: probe survived·Flyway V1 checksum and installation history preserved·최종 PASS. DB 재생성 healthy 대기는 120초, 각 DB client는 180초, 발견/Gateway는 각각 180초 상한이다. 이번 앱 재시작의 이력 동일성은 별도 로컬 검사에서 V1 행 전체를 직접 비교했다. 위 일반 application 검사는 V1 유효성을 확인한다.

종료는 `docker compose down`으로 일반 볼륨과 secret을 보존한다. 이번에는 서비스를 healthy로 남겼으며 전체 down/up은 후속 검증이다. 복구는 같은 secret/볼륨으로 `docker compose up -d --wait --wait-timeout 180` 후 application·발견·Gateway 검사를 수행한다. 일반 프로젝트에 `down -v`를 실행하지 않는다. 자동 clean/repair로 migration 오류를 숨기지 않는다. local JAR 전체 실행은 후속이며 README의 bootRun 안내도 사용자용 절차로 구분한다.

<a id="design-alignment-results"></a>

## 설계 합의와 기존 구현 대조·보완 — 2026-11-03

별도 구현 요청에 따라 계획의 ‘2026-11-03 설계 합의’를 현재 작업트리·위 실제 결과와 대조했다. 기존 연결 구현을 다시 만들지 않았다. 현재 브랜치/HEAD는 `feat/postgresql-foundation` / `c69cf8d16a56a6b6efca72641221e709f0acebf3`이며 기존 미커밋 변경을 보존했다.

최신 원격 조회: PR #4 MERGED/base `main`, PR #5 MERGED/base `feat/api-gateway`. main은 `26c04834345f1d6edfaffee37755be729fbc29f8`, [두 CI job 성공](https://github.com/y0y00700/group-purchase/actions/runs/41564604744). 로컬 HEAD의 check-run·workflow run 조회는 빈 결과였다. GitHub 연결 도구의 404 후 로컬 `gh`로 읽기 전용 조회했다. 브랜치 변경·merge/rebase·PR base 수정·커밋·push·PR 작업·원격 CI 실행 없음.

| 확정 설계 대조 | 판단·조치 |
|---|---|
| Config·local 환경변수·docker 필수 configtree/앱 secret 하나 | 기존 설정·Compose와 정상/실패 테스트가 충족. docker 실행 정의에 다른 비밀번호 주입 경로 없음. 파일 출처 강제 제품 코드는 보류 유지 |
| 디렉터리 부재·password 파일 부재·빈 값·오암호 | 기존 검사가 구분하나 테스트 Spring 환경이 외부 JVM system property를 상속할 여지가 있었음. 테스트 환경에서 host 시스템 프로퍼티·환경변수 대신 명시한 입력만 사용하도록 보완. 이는 테스트 격리이며 제품의 주입 우선순위/정책을 바꾸지 않음 |
| Flyway app/V1·재기동 불변 | 기존 설정·V1·검증 재사용. 테스트 이력 조회의 `AND success` 필터를 제거하고 version 1의 전체 행이 정확히 하나이며 성공/checksum이 있는지 확인하도록 강화 |
| 클래스별 fixture·추가 오류 DB·준비 실패 정리 | 기존 클래스별/추가 DB 구분 유지. 파일/컨테이너 준비 전체를 정리 보장 범위로 옮기고, 정리 예외가 원래 실패를 덮지 않도록 suppressed 예외로 보존 |
| 기존 Config/Eureka 계약·검사 단계·CI | 기존 실패 원인·별도 Eureka 프로세스 정리·클라이언트별 캐시 대기 유지. database/application 명시 구분과 네 DB 접근 경계·Compose CI 연결은 기존 코드와 검증 근거를 재사용. 자동 단계 감지/반대 단계 재시도 추가 없음 |

실제 추가 검증: `.\gradlew.bat :group-purchase-service:test --console=plain` 성공, **22개 실패 0**(Config 11·Eureka 2·연결 9), 2분 44초. 누락 local/docker 검사 시 유효한 fixture 암호를 JVM property에 의도적으로 두어도 테스트 환경에서 대체값으로 소비하지 않는 회귀 1개를 추가했다. 해당 JVM property는 finally에서 이전 값으로 복원한다. 모든 앱 기동 실패는 예외·기대 원인과 60초 미만 경과를 함께 확인했다. 증거: Git 제외 `.tmp/005-design-alignment-tests.txt` 및 공동구매 HTML/XML 테스트 결과.

재사용한 검증: 위 전체 build 36개 결과, database 격리 초기화, Compose 이미지/실행·application 권한/영속성·앱 재시작·발견/Gateway·비노출. 제품 코드·설정·migration·DB 검사·CI 정의가 이번 대조로 바뀌지 않아 이 검증들을 반복하지 않았다. 일반 DB·컨테이너·볼륨에 변경 작업을 수행하지 않았으며 기존 secret 다섯 파일의 SHA-256도 이전 검증 snapshot과 일치했다. 준비 실패 정리의 각 예외 분기를 별도로 장애 주입한 것은 아니며 정상 fixture 준비·종료는 위 통합 테스트에서 실제 실행했다.

직접 재현은 [연결 스텝 절차](#jdbc-flyway-manual-tests)의 준비·작업 위치·복사 명령·기대 결과·종료/복구를 따른다. 이번 보완만 확인하려면 같은 디렉터리에서 아래 명령을 실행한다. 사전 앱/일반 DB는 필요 없으며 Docker Desktop Linux 엔진·JDK 21/JAVA_HOME이 필요하다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
.\gradlew.bat :group-purchase-service:test --console=plain
```

기대: 22개 테스트 성공·종료 코드 0, 임시 context/별도 프로세스→DB/secret 순서 정리. Docker 부재는 필수 통합 테스트 실패이며 조용히 건너뛰지 않는다. 정상 종료 때 일반 DB/secret을 복원할 작업은 없다. 강제 중단했다면 해당 실행의 임시 자원만 확인하고 일반 프로젝트/볼륨은 정리하지 않는다.

확정 설계와 보류가 필요한 충돌은 없었다. JDBC/Flyway 연결 스텝 완료를 유지한다. 장시간 DB 장애 후 동일 PID 복구·Windows local JAR 전체 연동·전체 down/up·005 최종 검증과 미커밋 변경의 원격 CI는 여전히 이번 범위 밖이다.

<a id="jdbc-flyway-pr"></a>

## DB 기반·JDBC/Flyway 후속 PR — 2026-11-03

테스트 완료 후 사용자가 PR 진행을 요청했다. PR #4는 `main`, #5는 `feat/api-gateway`에 병합된 상태이며, fetch한 `origin/main`은 `26c04834345f1d6edfaffee37755be729fbc29f8`이다. 이 main에는 `c69cf8d`의 첫 DB 기반 변경이 아직 없다. 따라서 브랜치 `feat/postgresql-foundation`을 유지하고 main 대상 새 PR에 첫 DB 스텝과 후속 JDBC/Flyway 연결을 함께 제안한다. merge/rebase·기존 PR 수정·PR 병합은 하지 않는다.

로컬 공동구매 XML 보고서의 22개 테스트·실패/오류/건너뛰기 0과 `.tmp/005-design-alignment-tests.txt`의 성공을 확인했다. 앞선 전체 build·Compose·영속성·회귀 검증은 기록된 유효한 근거를 재사용하고, PR 준비를 위해 서비스를 기동하거나 테스트를 반복하지 않았다. 변경 형식·포함 파일과 secret 제외를 확인한 뒤 커밋·push하고 PR의 최종 head CI를 별도로 확인한다. 기존 main의 성공은 이번 변경의 성공을 뜻하지 않는다. 005 전체 완료 여부는 후속 검증 범위와 구분한다.

연결 구현·설계·테스트·문서를 `c30909f`로 커밋·push하고 [PR #6](https://github.com/y0y00700/group-purchase/pull/6)을 `main` 대상으로 생성했다. 제목은 ‘feat: PostgreSQL 기반과 공동구매 JDBC·Flyway 연결’이다. GitHub 연결 도구의 저장소 조회가 404여서 인증된 로컬 `gh`로 원격 조회·PR 생성을 진행했다. PR 링크·인계 갱신도 후속 문서 커밋에 포함하며, 정확한 최종 head SHA·원격 CI 결과는 [PR #6 Checks](https://github.com/y0y00700/group-purchase/pull/6/checks)와 완료 보고에서 확인한다. PR 병합은 하지 않았다.
