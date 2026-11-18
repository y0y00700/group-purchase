# 008. Gateway 인증·내부 호출 신뢰

상태 **로컬 구현·검증 완료, [PR #10](https://github.com/y0y00700/group-purchase/pull/10) 병합**

[008 계획](plan/008-gateway-authentication.md#execution-summary) · [인계](../handoff.md)

## 착수 기준

2026-11-09 KST에 [PR #9](https://github.com/y0y00700/group-purchase/pull/9)가 최신 `main`의 `d017594889fee3fbedaa19e6b8e4977cd645cc34`에 병합된 것을 확인했다. [PR head CI](https://github.com/y0y00700/group-purchase/actions/runs/42015430348)의 `1d61965349b2a97a501df3b408ad397142343efd`는 두 job이 성공했다. [병합 main CI](https://github.com/y0y00700/group-purchase/actions/runs/42581485872)는 Build 성공, 이미지 단계의 Gradle 배포 파일 다운로드 HTTP 504로 Compose smoke 실패였다. 같은 `main`에서 `feat/gateway-authentication`을 만들었으며 착수 전 변경된 문서 네 건을 가져왔다.

## 구현 범위

- Gateway는 명시적으로 등록한 원시 경로와 HTTP 메서드를 라우팅한다. 회원 공개 API의 쿠키와 응답은 회원 서비스가 담당한다. 보호 요청은 정리한 헤더와 세션으로 회원에 한 번 인증 확인을 보낸다.
- 회원 확인은 인스턴스 선택과 최대 8 KiB 응답 검증을 포함해 2초로 제한한다. 성공했을 때 공동구매용 자격 증명과 검증한 UUID·역할을 원래 요청에 넣는다.
- 공동구매 MVC 인터셉터는 보호 표식이 붙은 핸들러를 실행하기 전에 호출자와 자격 증명을 확인한다. 핸들러는 요청 속성의 신원을 사용한다.
- Compose는 두 전용 자격 증명을 각각 필요한 송신·수신 앱에만 읽기 전용으로 전달한다. 준비 스크립트는 32바이트 난수를 64자리 hex로 만든다. 앱은 32자 이상의 ASCII 토큰 형식을 검사하고 Gateway는 같은 값의 재사용도 거절한다.

## 기준 구현 검증

| 검증 | 확인한 결과 |
|---|---|
| Gradle 전체 빌드 | Java 21·Gradle 9.8.2에서 `gradlew.bat build --offline --no-daemon --max-workers=3` 성공. 유효한 모듈 결과 총 69개, 실패·오류·건너뜀 0. 첫 검증의 격리 fixture에는 자격 증명을 명시적으로 주입했고, 007 호환성 테스트의 Windows 임시 로그 잠금은 재실행에서 해소됐다. |
| Gateway 자동 테스트 | 14개 테스트 성공. HTTP fixture에서 정확한 경로·메서드, 복수 쿠키 필드, Connection 지정 필드 제거, 인증 확인 요청과 원본 POST 전달을 검사했다. 인증 의존 장애에서 확인은 최대 한 번 시도했고 공동구매 수신은 0회였다. 빈 발견 목록, 연결 거부, 시간 초과는 503·502·504로 구분했다. |
| Compose 정상·거절 경계 | 다섯 이미지 빌드, 격리 Compose 여섯 서비스 healthy. 공개 ping 200, 외부 8000에서 관리·내부 경로 404, 내부 9000 헬스 UP. 실제 쿠키 jar의 회전·로그아웃·만료, 역할·상태 변경, CSRF 거절, 위조 및 직접 호출 검사를 통과했다. 공동구매 로그에서 성공 요청 ID를 찾았고 거절 요청 ID는 0건이었다. |
| Compose 장애·복구 | `check-gateway-auth.py --dependency-outages` 통과. 회원 중단 시 캐시 주소 연결 제한으로 Gateway 504, PostgreSQL 중단 시 회원 직접 확인 503과 Gateway 504. 장애 중 Gateway 관리 헬스 UP, 공동구매 전달 0회. 두 경우 모두 복구 후 같은 Gateway의 보호 요청 200. 앞선 실험에서는 회원 발견 목록이 비었을 때 503도 관측했다. |
| 기존 Compose 계약 | `check-postgres.py --stage application`, `check-discovery.py`, `check-gateway.py`, `check-api-observability.py` 통과. DB 역할·접근 경계, Eureka 등록 주소, ping·비공개 경로, 006 오류·로그·헬스·메트릭을 확인했다. |
| 007 회원 회귀 | `check-member-auth.py` 통과. 회원 8081 직접 호출, 18081·18082 두 제품 JAR의 회전·세션 공유·CSRF·로그아웃, Gateway를 통한 익명 `me` 401·유효 세션 200과 내부 경로 404를 확인했다. |
| Windows 제품 JAR | `check-gateway-auth.py --jars` 통과. Java 21 제품 JAR 세 개를 별도 루프백 포트에 띄우고 Compose Config·PostgreSQL에 연결했다. Windows JAR 경로에서는 단순 발견을 사용했고 Compose 경로에서는 Eureka를 사용했다. |
| 설정과 정리 | Gateway 제품 JAR이 누락·형식 오류·동일 자격 증명으로 기동을 거절했다. 앱별 secret 마운트 경로를 확인하고 임시 회원·세션, 테스트 JAR 프로세스와 격리 Compose를 정리했다. 일반 DB와 검증 DB 볼륨 및 기존 secret 파일을 보존했다. |

위 결과는 실행 본문을 참고 주석으로 옮기기 전 기준 소스의 검증이다. 작성 위치를 연 뒤 Gateway와 공동구매 모듈의 `compileJava`를 확인했다.

<a id="guided-coding"></a>

## 직접 작성한 여섯 Step

사용자가 여섯 메서드를 작성했다. 아래 표는 작성 구간과 검토한 동작을 연결한다.

| Step | 파일과 메서드 | 확인할 경계 |
|---|---|---|
| LEARN-01 | [ExternalHeaders.java](../../infrastructure/api-gateway/src/main/java/com/example/apigateway/ExternalHeaders.java) · `sanitize` | 외부 신원·내부 호출자·메서드 덮어쓰기·Connection 지정 헤더 제거 |
| LEARN-02 | [MemberAuthClient.java](../../infrastructure/api-gateway/src/main/java/com/example/apigateway/MemberAuthClient.java) · `send` | 선택한 회원 주소와 고정 내부 경로, 단일 세션 쿠키와 원본 메서드, CSRF 전달 |
| LEARN-03 | [MemberAuthClient.java](../../infrastructure/api-gateway/src/main/java/com/example/apigateway/MemberAuthClient.java) · `classifyResponse` | 유효한 신원만 허용하고 회원 오류와 응답 형식 오류 구분 |
| LEARN-04 | [AuthRouteFilter.java](../../infrastructure/api-gateway/src/main/java/com/example/apigateway/AuthRouteFilter.java) · `forwardAuthenticated` | 인증 성공 후 신원과 공동구매용 자격 증명 주입, 원본 본문 전달 |
| LEARN-05 | [GatewayIdentityInterceptor.java](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/web/GatewayIdentityInterceptor.java) · `validCaller` | 단일 호출자와 서비스 전용 자격 증명 비교 |
| LEARN-06 | [GatewayIdentityInterceptor.java](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/web/GatewayIdentityInterceptor.java) · `readIdentity` | UUID·역할 검증과 요청에 사용할 신원 구성 |

2026-11-12 KST에 여섯 구간을 리뷰했다. 작성한 소스로 `:api-gateway:test`를 실행해 14개 중 11개가 통과했다. 인증 통합 테스트 세 개는 모두 예상 상태 대신 504를 받아 실패했다.

- `LEARN-02`의 `send`는 현재 개발용 회원 서비스에 맞춰 `http://member/internal/auth/check`를 기본 주소로 사용한다. 내부 요청에는 `CREDENTIAL_HEADER`에 회원용 `credential`을 설정한다. 자격 증명이 빠지면 회원 서비스가 내부 호출을 403으로 거절한다.
- `LEARN-06`의 `readIdentity`는 회원 UUID를 `X-Authenticated-Member-Id`에서, 역할을 `X-Authenticated-Roles`에서 각각 읽는다. 역할 헤더를 UUID로 파싱하면 정상 요청도 400으로 거절된다.

사용자 요청에 따라 위 세 지점을 수정했다. 검증을 마친 뒤 중복 참고 주석과 임시 표식을 정리했다. 메서드의 호출 시점과 신원이 전달되는 흐름은 [Gateway 학습 문서](../explanations/api-gateway.md#인증된-공동구매-요청)에 정리했다.

## 수정 후 재검증

2026-11-12 KST에 다음을 확인했다.

- 전체 Gradle 빌드 성공. 모듈별 유효한 테스트 결과 69개, 실패·오류·건너뜀 0개. 앞서 504로 실패했던 Gateway 인증 통합 테스트 세 개도 통과했다.
- 격리 Compose에서 다섯 앱 이미지를 빌드하고 여섯 서비스의 헬스를 확인했다. 공개 ping, 007 회원 API, 쿠키 회전·만료, CSRF와 내부 호출자 검증을 통과했다.
- 회원과 PostgreSQL을 각각 중단했을 때 보호 요청은 공동구매에 전달되지 않았다. 두 서비스가 복구된 뒤 같은 Gateway에서 보호 요청이 다시 성공했다.
- Windows 제품 JAR에서도 로그인과 보호 요청을 검증했다. 로컬 JAR은 단순 발견을 사용하고 Compose는 Eureka를 사용했다.

<a id="pull-request"></a>

## PR 기록

- 2026-11-12 KST에 구현 커밋 `cc36c05f15ae4e724cf8f06411a80515ea8842e7`을 `feat/gateway-authentication`에 push하고 `main` 대상 [PR #10](https://github.com/y0y00700/group-purchase/pull/10)을 열었다.
- PR 준비 시 원격 `main`의 `d017594`와 분기 기준이 같음을 확인했다. 해당 커밋의 Build는 성공했고 Compose smoke는 Gradle 배포 파일 다운로드 HTTP 504로 실패했다. [기준 main CI](https://github.com/y0y00700/group-purchase/actions/runs/42581485872)
- 2026-11-12 KST 009 계획 검토 시 PR 최종 head `20718eb8004039565983decd9ff97b5db10fb97e`의 Build and test와 Images and Compose smoke test가 모두 성공했고 PR이 병합된 것을 확인했다. [PR #10](https://github.com/y0y00700/group-purchase/pull/10) · [PR 최종 head CI](https://github.com/y0y00700/group-purchase/actions/runs/43185432231)
- 같은 조회에서 원격 `main`은 병합 커밋 `f0a0821e4f4cc90fa2e643790aa035d3b4efe538`였고 해당 CI는 실행 중이었다. [병합 main CI](https://github.com/y0y00700/group-purchase/actions/runs/43196695980)

<a id="manual-tests"></a>

## 사용자 직접 테스트 방법

Java 21(`JAVA_HOME`), Python 3, Docker Desktop Linux 엔진을 준비한다. PowerShell에서 프로젝트 루트로 이동하고 5432·8000·8080·8081·8761·8888을 비워 둔다. 아래 명령은 별도 Compose 프로젝트를 사용한다. Gradle과 검사 스크립트는 수정 후 소스로 재실행했다. 개별 PowerShell 요청과 자격 증명 파일 교체는 직접 재현 절차다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
$env:COMPOSE_PROJECT_NAME = 'group-purchase-008'
.\scripts\prepare-local-secrets.ps1
.\gradlew.bat build --no-daemon
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
python scripts/check-gateway.py
python scripts/check-member-auth.py
python scripts/check-gateway-auth.py --dependency-outages --jars
```

- `check-gateway.py`는 공개 ping 200, 익명 보호 요청 401, 비공개·유사 경로와 ping의 미지원 메서드 404를 확인한다.
- `check-member-auth.py`는 007의 직접 회원 API와 두 제품 JAR의 세션 공유를 검사한다. Gateway의 익명 `me`는 401, 유효한 세션의 `me`는 200, 내부 인증 경로는 404를 기대한다.
- `check-gateway-auth.py`는 임시 회원과 쿠키 jar로 CSRF 발급, 로그인 쿠키 회전, 조회, CSRF가 필요한 POST, 역할 변경, 로그아웃, 만료를 검사한다. 쿠키 누락·만료·비활성 회원은 401, CSRF 누락·오답·중복은 403이다. 직접 8080에서 호출 자격 증명 오류는 403, 검증된 호출자의 잘못된 신원은 400이다.
- `--dependency-outages`는 현재 Compose 프로젝트의 회원과 PostgreSQL을 차례로 중지하고 각 검증 뒤 복구한다. 회원 주소 연결 실패는 502, 조회 가능한 인스턴스 부재 또는 회원의 인증 장애 응답은 503, 회원 확인 시간 초과는 504다. 검사 스크립트는 성공 요청의 ID가 공동구매 로그에 기록되고, 검사한 거절 요청의 ID는 그 로그에 없음을 확인한다.
- `--jars`는 Compose의 Config·PostgreSQL과 루프백의 회원·공동구매·Gateway 제품 JAR을 연결한다. 18081·18080·18000·19000도 비워 둔다. 제품 JAR의 인스턴스는 단순 발견으로 지정하고 Compose에서는 Eureka를 사용한다.

PowerShell에서 실패 경계를 개별 확인할 수 있다.

```powershell
# 200 pong
Invoke-RestMethod http://127.0.0.1:8000/api/group-purchases/ping

# 401 UNAUTHENTICATED, Cache-Control: no-store
Invoke-WebRequest http://127.0.0.1:8000/api/group-purchases/auth-probe -SkipHttpErrorCheck

# 403 INTERNAL_CALLER_DENIED
Invoke-WebRequest http://127.0.0.1:8080/api/group-purchases/auth-probe -SkipHttpErrorCheck

# 404 NOT_FOUND
Invoke-WebRequest http://127.0.0.1:8000/internal/auth/check -Method Post -SkipHttpErrorCheck
```

정상 로그인과 실패 요청을 직접 비교할 때는 새 개발용 회원만 만든다. 스크립트 방식은 고유 회원과 세션을 만들고 정리한다.

```powershell
$base = 'http://127.0.0.1:8000'
$probe = "$base/api/group-purchases/auth-probe"
$loginId = 'manual008_' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
python scripts/member-tool.py $loginId
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -SessionVariable browser
$password = [System.Net.NetworkCredential]::new('', (Read-Host '위 회원의 비밀번호' -AsSecureString)).Password
try {
    Invoke-RestMethod "$base/api/auth/login" -Method Post -WebSession $browser `
        -ContentType application/json -Headers @{ 'X-CSRF-TOKEN' = $csrf.token } `
        -Body (@{ loginId = $loginId; password = $password } | ConvertTo-Json -Compress)
} finally {
    Remove-Variable password
}
Invoke-RestMethod "$base/api/members/me" -WebSession $browser
Invoke-RestMethod $probe -WebSession $browser
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -WebSession $browser
(Invoke-WebRequest $probe -Method Post -WebSession $browser -SkipHttpErrorCheck).StatusCode
Invoke-RestMethod $probe -Method Post -WebSession $browser -Headers @{ 'X-CSRF-TOKEN' = $csrf.token }
(Invoke-WebRequest "$base/api/auth/logout" -Method Post -WebSession $browser `
    -Headers @{ 'X-CSRF-TOKEN' = $csrf.token }).StatusCode
(Invoke-WebRequest $probe -WebSession $browser -SkipHttpErrorCheck).StatusCode

# 직접 만든 임시 회원과 해당 세션을 정리한다.
$cleanup = @'
export PGPASSWORD="$(cat /run/secrets/member_password)"
psql -X -w -h 127.0.0.1 -U member_app -d member -v ON_ERROR_STOP=1 -v fixture="$1" <<'SQL'
DELETE FROM app.spring_session WHERE principal_name IN
    (SELECT CAST(member_id AS text) FROM app.members WHERE login_id = :'fixture');
DELETE FROM app.members WHERE login_id = :'fixture';
SQL
'@
$cleanup | docker compose exec -T postgres sh -s -- $loginId
```

정상 로그인·조회·올바른 토큰의 보호 POST는 같은 UUID와 역할을 반환한다. 토큰 없는 보호 POST는 403, 로그아웃은 204, 그 뒤 보호 요청은 401이다.

```powershell
# 중단 실험 후 복구가 필요한 경우
docker compose up -d --wait --no-deps postgres member-service

# 이 프로젝트의 컨테이너와 네트워크 종료. 볼륨과 secret 파일은 보존한다.
docker compose down
Remove-Item Env:COMPOSE_PROJECT_NAME
```

자격 증명 교체는 해당 연결 호출을 멈추고 보호된 파일에 새 난수 값을 넣은 뒤 두 앱을 재생성한다. 회원 연결은 `member-service api-gateway`, 공동구매 연결은 `group-purchase-service api-gateway`를 대상으로 `docker compose up -d --force-recreate --wait`를 실행한다. 이전 값으로 수신부 403, 새 값으로 정상 보호 호출 200을 확인한다.
