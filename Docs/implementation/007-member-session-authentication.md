# 007. 회원·세션 인증 구현 기록

2026-11-05 KST. [확정 계획](plan/007-member-session-authentication.md) · [인계](../handoff.md)

## 현재 상태

**007 로컬 구현·사용자 6개 Step 작성·묶음 리뷰·재검증 완료 / [PR #9](https://github.com/y0y00700/group-purchase/pull/9) 생성.** 현재 실제 소스에 작성 대기 예외나 중복 참고 주석이 없다. Gateway 인증 연결은008로 남겼으며 병합은 수행하지 않았다.

<a id="pull-request"></a>

## PR 기록

2026-11-05 사용자 요청으로 커밋·push 후 main 대상 [PR #9](https://github.com/y0y00700/group-purchase/pull/9)를 생성했다. 병합은 요청 범위에 포함하지 않는다.

- 브랜치 `feat/member-session-authentication` → `main`. 구현 커밋 `49a376edb3eac9b655a2d5e0d23f83f1f25b66f7`에 71개 파일의 변경을 포함했다. PR 링크·인계 반영용 후속 문서 커밋도 같은 브랜치에 push하며 최종 head는 PR을 기준으로 확인한다.
- 최신 원격 확인: 열린 PR 없음, main `d5d0396474797ac734caa95b1f1805aaa4f6132b`, [main CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41989123469). fetch 후 기존 기준 HEAD와 main의 파일 트리 차이 0개 확인.
- 회원 현재 main 소스·리소스의 경로/내용 SHA-256 및 제품 JAR SHA-256이 위 최종 검증 기록과 일치했다. 기존 테스트 XML 64개·실패/오류/skip 0도 확인했다. 관련 소스 변경이 없어 전체 빌드·Compose를 반복하지 않았다.
- 로컬 학습/explanations·임시 증거·secret은 기존 Git 제외 정책을 유지한다. PR에는 서비스·설정·검사·CI·계획/결과/인계 문서를 포함한다.
- PR/push 후 Build/Smoke 완료 대기·반복 조회는 하지 않는다. PR·최종 head·Checks 링크와 확인 당시 상태를 안내한다.
- 원격 검사의 현재 결과는 [PR Checks](https://github.com/y0y00700/group-purchase/pull/9/checks)를 따른다. 위 main CI와 로컬 성공을 새 PR의 CI 성공으로 표시하지 않는다.

<a id="final-results"></a>

## 사용자 작성·묶음 리뷰·최종 검증

2026-11-05 사용자 작성 완료 통보 후 여섯 활성 본문을 함께 리뷰했다. 인증 실패·현재 상태/역할·세션 전략/컨텍스트 저장·호출자 사전 검증·마스킹 CSRF·세션 저장 후 응답 전송에 수정이 필요한 문제를 발견하지 않았다. 공백/참고 주석을 제외한 여섯 본문의 코드 토큰이 검증된 DDD 참고 구현과 일치함을 확인했다. 사용자 실행 코드를 대신 수정하지 않았다.

| 검증 대상 | 실행·결과 |
|---|---|
| 실제 사용자 활성 소스 | 프로젝트 루트 `gradlew.bat build --no-daemon` 성공. 누적64개 결과, 실패/오류/skip0. 회원 인증/JDBC13개와 패키지 의존1개는 실제 재실행했고 변경 없는 기존50개는 유효한 Gradle 결과 재사용 |
| 6개 Step 정리 | 위 검증 후 중복 참고 주석·LEARN 표식·주석 처리한 임시 예외만 제거. 정리 전후 실행 코드 보존을 확인했고 주석 정리 전 사용자 소스도 `.tmp/007-user-authored-before-cleanup`에 보관 |
| 최종 제품 | 정리한 실제 소스로 `gradlew.bat :member-service:bootJar --no-daemon` 성공. 회원 이미지 재빌드 후 기존 네 앱 이미지와 누적 Compose 여섯 서비스 healthy |
| 최종 실행 검사 | 루트의 `python scripts/check-member-auth.py` PASS. 별도 참고 사본 경로를 사용하지 않고 현재 제품 JAR로 검사. Compose 회원과 Windows JAR 두 프로세스에서 실제 HTTP 로그인/ID 회전/공유/CSRF/로그아웃 성공 |
| 회귀·보존 | 로컬 관리의 기존 계정 덮어쓰기 거절/명시 역할 갱신의 해시 보존, MEMBER 등록 주소 도달, Gateway 회원/내부 경로404, JSON 로그 로그인 ID/비밀번호/내부 secret 미노출 확인 |
| 기존 핵심 필수 검사 | 이번 회원13개 재실행에서 PostgreSQL 공유·두 인스턴스 재시작·2초 만료·현재 역할/상태·호출자 사전 SQL0회·잘못된 입력·CSRF·지연 요청·최종 저장/삭제 실패·동일 앱 DB 복구·500/503 구분·이전 JAR 세션 호환 모두 통과 |

로그: `.tmp/007-user-build.log`, `007-user-product.log`, `007-user-image.log`, `007-user-runtime.log`. 검토/정리 근거는 `.tmp/007-user-review.json`, `007-user-cleanup.json`, 최종 식별은 `007-user-final-evidence.json`에 있다. 최종 main 경로+내용 SHA-256 `eb73bee99b11b328905b2b77da54c6f6f94b1e59540c3275ceed6446bec1a309`, 제품 JAR SHA-256 `2941bf4a43f55f7a4c92285493454cdd9f01820587bb9dff388eec5b02ec89fd`.

아래의 최초 평면 구현·DDD 별도 참고 사본 기록은 이 최종 사용자 소스 검증과 구분해 보관한다. 수동 PowerShell 키 입력 절차는 아래 재현 안내이며 실제 자동 실행 검증과 구분한다. 기존 DB 경계/영속성·누적 관측 검사 중 변경 없는 유효한 근거는 반복 실행하지 않았다.

## DDD 재구성 결과 (사용자 작성 전 기록)

**새 구조의 참고 구현 검증 완료 / 당시 실제 작업 소스는 6개 Step 사용자 작성 대기.** 이동 전 여섯 본문은 모두 placeholder였으며 사용자 작성 본문은 없었다. 입력 위치를 대신 채우지 않고, Git 제외 별도 사본 `.tmp/007-ddd-verification`에서 참고 주석의 본문만 활성화해 검증했다. 따라서 아래 새 성공 근거도 당시 입력 대기 소스의 기능 성공을 뜻하지 않는다.

| 계층 | 실제 책임·타입 |
|---|---|
| `domain/member` | `Member`·`MemberId`·`MemberStatus`·`Role`·`MemberRepository`. ACTIVE/최소 역할 규칙은 `Member.canAuthenticate`, 관리 대상 검증은 `Member.managed`가 소유. Java 외 프레임워크 의존 없음 |
| `application` | `AuthenticateMemberService`, `GetCurrentMemberService`, `ManageLocalMemberService`, 비밀값 없는 `MemberResult`, HTTP 상태 없는 `MemberAuthenticationException`, `port/PasswordVerifier`. 트랜잭션 선언 외 Spring 의존 없음 |
| `interfaces` | web의 CSRF/본인 조회/내부 확인 컨트롤러와 DTO, cli의 `LocalMemberTool`. 애플리케이션 결과와 HTTP 응답을 분리하고 SQL·업무 규칙은 위임 |
| `infrastructure` | `persistence/JdbcMemberRepository`, Security provider/filter/비밀번호 어댑터, `SessionMemberIdentity`, Servlet 필터/보류 응답/관측/외부 오류 변환. `StorageFailures`로 오류→필터 역참조 방지 |
| `config` | Security와 애플리케이션 Bean·필터 등록 조립, 설정 정보, 조기 실패 로그 |
| 루트 호환 셸 | `Members`에는 `Identity`만 남음. 기존 `com.example.member.Members$Identity`, UUID 필드와 serialVersionUID=1을 유지하며 서비스·SQL·인증 로직 없음 |

기존 테이블·마이그레이션·HTTP 경로/응답·쿠키/CSRF·필터 상대 순서·30분 만료·secret 계약을 유지했다. Gateway 인증 연결은008이며 001~006 소스의 패키지는 변경하지 않았다. 클래스를 일괄 public으로 바꾸지 않았고, 조립에 필요한 JsonLoginFilter 및 조기 실패 listener 경계만 공개했다.

### 재구성 검증

- 별도 사본에서 `gradlew.bat :member-service:test :member-service:bootJar --no-daemon` 성공. 회원 HTTP/JDBC 검사13개와 계층 의존 검사1개, 실패/오류/skip0.
- 같은 사본에서 `gradlew.bat build --no-daemon` 성공. 프로젝트 전체64개 테스트 결과, 실패/오류/skip0. 회원14개는 직전 실행 결과를 재사용했고 기존50개는 이 사본에서 실제 실행했다.
- `PackageDependencyTests`가 domain의 외부 계층/프레임워크 의존, application의 HTTP/Security/JDBC/구현 의존, interfaces의 JDBC 저장소 직접 의존, infrastructure의 interfaces/config 역참조를 검사한다. 새 아키텍처 라이브러리는 추가하지 않았다.
- 재구성 전 제품 JAR이 같은 **격리 PostgreSQL**에 실제 HTTP 로그인으로 만든 세션을 새 구조 A/B가 읽고, 새 A/B 재시작 후에도 같은 쿠키로200을 확인했다. 일반 DB/세션 일괄 삭제 없음.
- 이전 JAR의 실제 `Members$Identity.class`에서 생성한 비밀값 없는 고정 UUID 직렬화 fixture `src/test/resources/legacy-member-identity.base64`를 추가했다. CI는 로컬 과거 JAR이 없어도 이 fixture 역직렬화·JDBC 저장·두 인스턴스 HTTP 인증을 항상 검사한다. 로컬 과거 JAR 프로세스 검사는 이번에 실제 실행했으며 `.tmp`의 과거 산출물이 있을 때 추가 실행된다.
- 새 구조에서도 기존12개 검사가 모두 통과했다: 회전/재시작/2초 만료/로그아웃/현재 상태·역할/내부 사전 SQL0회/CSRF/지연 요청/최종 저장·삭제 실패/동일 프로세스 DB 복구/500·503 구분.
- 별도 사본에서 회원 이미지를 빌드하고 기존 Compose에 적용해 여섯 서비스 healthy를 확인했다. 다른 네 앱 이미지는 기존 검증 산출물을 사용했다.
- `.tmp/check007ddd.py`가 기존 `check-member-auth.py`의 제품 JAR 경로만 별도 사본의 새 JAR로 지정해 실행했다. Compose·Windows 제품 JAR 두 프로세스의 회전/공유/CSRF/로그아웃, 로컬 도구의 기존 계정·해시 보존 및 역할 변경, MEMBER 등록 주소 도달, Gateway404, JSON 로그 비밀값 비노출 모두 PASS. 임의 probe 회원/세션·자식 프로세스만 정리했다.
- 실제 작업 소스는 여섯 placeholder를 유지한 채 `:member-service:compileTestJava`로 컴파일·테스트 조립만 확인했다. 현재 소스의 기능 테스트는 성공으로 표시하지 않는다.

증거는 `.tmp/007-ddd-runtime.log`, `.tmp/007-ddd-verification/.tmp/007-ddd-reference-tests.log`, `007-ddd-build.log`, `007-ddd-image.log`, `.tmp/007-ddd-evidence.json`에 보관했다. 새 참고 main 경로+내용 SHA-256 `6aaf8e53fdc7f1ed6c62a597f9b2eb278eac0121ca1c0a4cb352d2afca047b7f`, 제품 JAR `6006292e6dc5d7cdf09d5927952ec721a06f5630b2dd622d3bb6104170a59956`, 실제 이전 JAR `8febae5b463da8285d41044624070db690551086673e016c260b6ad4f6280e10`. 아래 평면 구조 검증 근거와 구분한다.

DDD 재구성 착수 시 원격 재확인: PR #8 MERGED, head `1def2d5`의 두 CI SUCCESS. main `d5d0396474797ac734caa95b1f1805aaa4f6132b` [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/41989123469). 현재 HEAD와 main은 병합 커밋1개 차이·변경 파일0개여서 기존 작업 브랜치/미커밋 작업을 유지했다. 새 커밋·push·PR·병합은 수행하지 않았다.

## 착수와 보존

- 지정 순서 `AGENTS.md` → `Docs/handoff.md` → 007 계획을 읽고 1회 검토를 반영한 추천안 전체를 사용자 요청으로 채택했다.
- 착수 시 GitHub 커넥터는 404, sandbox의 gh는 연결 실패였다. 읽기 전용 gh/Git을 sandbox 밖에서 실행해 최신 상태를 확인했다.
- 최초 007 착수 당시 [PR #8](https://github.com/y0y00700/group-purchase/pull/8)은 OPEN, head `1def2d5157204309f80313c7862ccbfad987661e`. 두 CI 검사 SUCCESS: [run 41833293323](https://github.com/y0y00700/group-purchase/actions/runs/41833293323). 이후 DDD 재구성 착수 시의 병합/main 상태는 위 절을 따른다.
- main은 `e5c87d80ed5b1411c9f18766ef082320b33460c5`, [run 41754743919](https://github.com/y0y00700/group-purchase/actions/runs/41754743919) success. 원격 ref와 gh 조회가 일치했다. 006이 main에 병합됐다고 가정하지 않았다.
- 006이 포함된 현재 HEAD에서 `feat/member-session-authentication`을 만들었다. 기준 HEAD는 그대로이며 커밋·push·PR 생성·병합은 하지 않았다.
- 기존 변경: `Docs/README.md`, `Docs/handoff.md`, 006 결과, 구현 README, roadmap의 미커밋 수정과 untracked 007 계획. 이를 보존하고 현재 상태 문구만 후속 갱신했다. 기존 사용자 Java 코드와 006 학습 구간은 수정하지 않았다.
- 기존 다섯 DB secret 파일을 보존하고 `member_internal_credential`만 배타 생성했다. 일반 DB 볼륨 삭제·DB 초기화 스크립트 재실행·Flyway clean/repair·secret 출력은 하지 않았다. 회원 Flyway V1은 member DB에 적용했다.

## 최초 기준 구현의 변경 (재구성 전)

| 위치 | 결과 |
|---|---|
| `services/member-service` | 독립 Boot MVC 모듈, Java 21/Boot 4.0.9/Cloud 2025.1.4 유지. 실제 BOM: Security 7.0.8, Session JDBC/core 4.0.5 |
| `Members`·`JsonLoginFilter` | 정규화한 ASCII 로그인 ID, UTF-8 1~72바이트 비밀번호, Delegating bcrypt, 일관된 인증 실패, UUID만 가진 직렬화 principal, 매 요청 현재 ACTIVE·현재 역할 조회 |
| `SecurityConfiguration` | JSON 인증 필터에 기존 세션 무효화/새 세션 전략·CSRF 초기화·HttpSession 컨텍스트 저장소 명시 연결. 기본 폼/Basic/remember-me/RequestCache 미사용 |
| `AuthController`·`HeaderCsrfHandler` | 마스킹 CSRF 발급, 단일 헤더만 허용, 내부 입력 → 기존 세션/현재 회원 → 원본 변경 메서드 CSRF 검사. 내부에서는 토큰 생성 없이 loadToken 사용 |
| `BoundaryFilter`·`BufferedResponse` | 세션 필터 앞 호출자/중복 쿠키/정확한 경로·메서드 검증. 다섯 인증 경로의 응답만 최대 64KiB 보류. JDBC 완료 후 상태·헤더·본문 전송. 저장/삭제 실패 시 보류한 성공·Set-Cookie 폐기 |
| `ApiErrors`·`Problems`·`web/RequestIdFilter` | RFC 9457 + code/requestId, no-store, 공개/내부 사전 거절 및 최종 저장 실패의 ID·정규화 경로·최종 상태 JSON 로그. 예외 메시지·비밀값은 오류에 포함하지 않음 |
| migration·중앙 설정 | app 회원/역할 및 Spring Session PostgreSQL 테이블·인덱스·FK. 자동 세션 DDL 금지, 30분 유휴 만료·매분 정리. GP_SESSION, Path=/, HttpOnly, SameSite=Lax, Domain 없음 |
| `LocalMemberTool`·`scripts/member-tool.py` | 명시적 local·loopback member DB만 관리. 기본 기존 계정 보존. update에서 역할/상태만 변경하고 reset-password를 명시해야 해시 변경. 숨김 입력/자식 환경 주입, 비밀값 argv 없음 |
| Docker/Compose·CI·검사 도구 | 다섯 앱 이미지/여섯 서비스, 회원 loopback 8081, 회원 전용 DB/내부 secret만 주입. DB/관측 검사 확장, 회원 검사 추가 |

Gateway 라우팅·인증 필터·신원 헤더 제거·Gateway secret 주입은 **008**이다. 회원 공개 API도 현재는 8081 직접 호출만 제공한다. Gateway/Eureka/업무 서비스의 인증 연결을 추가하지 않았다. 기존 Dockerfile에 회원 디렉터리 COPY를 추가한 이유는 Gradle settings가 새 모듈을 포함하기 때문이다.

### 구현에서 확인한 경계

- Boot 4에서는 raw Spring Session JDBC 의존성만으로 자동 구성이 포함되지 않는다. `spring-boot-starter-session-jdbc`를 사용했다. BOM 버전은 바꾸지 않았다.
- Session 4.0.5 JAR에 포함된 PostgreSQL DDL 및 javap의 SQL/필터 순서를 대조했다. PRIMARY_ID 기반 UPDATE가 SESSION_ID도 갱신하므로 로그인은 같은 행 ID 변경 대신 기존 세션 무효화/새 세션을 사용한다.
- 동시에 처음 CSRF 속성을 저장하는 요청의 유일키 경합은 PostgreSQL attribute upsert로 처리한다. 동시에 새로 생성한 서로 다른 토큰 중 마지막 저장 값이 남을 수 있으므로 변경 요청의 403에서는 토큰을 다시 발급받는다. 이 upsert는 삭제된 세션 행을 생성하지 않는다.
- 내부·Actuator 체인의 SecurityContext/anonymous/session-management와 MVC FlashMap·요청 이벤트의 암묵적 세션 읽기를 차단했다. 요청 이벤트 대신 완료 로그·Micrometer HTTP 관측을 사용한다. 내부 본문 검증보다 먼저 세션 SQL이 실행되지 않는다.
- 연결/시간 초과·SQLState 08/DB shutdown 계열과 롤백 예외에 가려진 원래 연결 장애만 503이다. 스키마 오류·직렬화 불일치는 500으로 검증했다.
- 저장 실패의 응답은 503이지만 이미 DB에 반영된 일부 변경까지 취소했다는 보장은 없다. 본인 조회로 상태를 확인하고 필요하면 CSRF 발급부터 다시 시작한다.
- HTTPS용 기본 쿠키 Secure는 true이며 local/docker HTTP에서만 false를 명시한다. 운영 HTTPS 종료·프록시 신뢰·외부 Set-Cookie 전달 실측은 008 이후다.
- 공식 근거: [Security 7.0 CSRF](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html), [컨텍스트 명시 저장](https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/persistence.html). Session DDL의 직접 근거는 해결된 4.0.5 JAR의 `org/springframework/session/jdbc/schema-postgresql.sql`이다.

## 최초 평면 기준 구현 검증 근거 (재구성 전)

| 실행 | 확인 결과 |
|---|---|
| `.\gradlew.bat build --no-daemon` | 성공. 누적 62개 테스트, 실패/오류/skip 0. 기존 50개는 변경 없는 유효한 Gradle 결과 재사용, 회원 12개 실제 실행 |
| `MemberAuthenticationTests` | 격리 PostgreSQL 18.6, 서로 다른 두 앱 컨텍스트의 실제 HTTP 로그인/쿠키. A 로그인→B 조회/내부 확인→B 로그아웃→A 거절, 두 컨텍스트 재시작 유지, ID 회전/이전 ID 거절 |
| 같은 통합 검사 | 2초 만료 및 cleanup 비활성 상태에서 거절, 현재 역할/상태/역할 없음 반영, 최소 principal 직렬화에 비밀번호/해시 없음 |
| 같은 통합 검사 | 로그인·로그아웃·내부 변경의 누락/오답/타 세션/로그인 전 토큰·중복 헤더·쿼리 대체 거절. GET/HEAD/OPTIONS와 POST/PUT/PATCH/DELETE 분기, TRACE/CONNECT/알 수 없는 메서드 거절 |
| 같은 통합 검사 | 잘못된 호출자·중복 쿠키·Actuator info의 세션/회원 SQL 0회. 잘못된 내부 본문을 세션 SQL 전에 거절. 세션 없는 내부 확인은 세션/토큰을 만들지 않음 |
| 같은 통합 검사 | PostgreSQL pause→503·caller 사전 거절403·liveness200/readiness503→같은 컨텍스트 복구. 마지막 세션 저장/로그아웃 삭제 실패 주입→503·새 쿠키/성공 본문 없음 |
| 같은 통합 검사 | 이전 세션 SELECT를 latch로 지연→다른 인스턴스 로그인/로그아웃→이전 요청 재개 후 예전 인증 재생성 없음. 동시 첫 토큰 저장 후 재발급 토큰으로 변경 확인 성공 |
| 같은 통합 검사 | 잘못된 스키마·손상한 직렬화 속성은 500, 연결 장애 503으로 숨기지 않음 |
| `docker compose up -d --build --wait --wait-timeout 180` + 최종 회원 이미지 갱신 | 다섯 앱 이미지 빌드, 여섯 서비스 healthy. 기존 DB 볼륨 유지 |
| `python scripts/check-postgres.py --stage application --persistence` | 네 서비스 DB 경계·권한·인증, PostgreSQL 재생성 후 일반 볼륨 영속성, Flyway V1 이력 보존 |
| discovery·gateway 검사 | 기존 GROUP-PURCHASE 등록 주소 도달 및 공개 ping 정상, 비공개/미지원 메서드 차단 |
| `python scripts/check-api-observability.py --db-recovery` | 회원을 포함한 health/info/metrics 노출·JSON/개인정보·probes, 기존 동일 컨테이너 DB 복구 검사 성공 |
| `python scripts/check-member-auth.py` | Compose 회원 및 Windows Java 21 제품 JAR 두 프로세스(18081/18082)의 실제 회전/공유/CSRF/로그아웃 성공. MEMBER Eureka 주소 도달·Gateway 회원/내부 경로404, JSON 로그의 로그인 ID/비밀번호/내부 secret 미노출. 로컬 도구 기본 덮어쓰기 거절·명시적 역할 update의 해시 보존 확인 |

장애 검사와 제품 JAR 검사는 DB 복구 후 순차 실행했다. 최초 병행 실행에서 제품 검사 도구가 readiness 503을 인증 Problem으로 오인한 부분을 수정한 뒤 재실행해 성공했다. 기능 오류를 테스트 기대값 완화로 통과시키지 않았다.

로컬 상세 로그는 Git 제외 `.tmp/007-build.log`, `007-postgres.log`, `007-discovery.log`, `007-gateway.log`, `007-observability.log`, `007-member-runtime.log`, `007-member-image.log`에 있다. 기준 구현 식별:

- main 소스·리소스 경로+내용 SHA-256: 494617719c92a715f2844e1b226e936e2deb0c2420f7051ad32b4ca3d5149615
- 검증 제품 JAR SHA-256: 8febae5b463da8285d41044624070db690551086673e016c260b6ad4f6280e10
- 이 digest는 작성 대기 전환 전이며 현재 소스의 digest와 다르다. `.tmp/007-baseline-evidence.json`에 같은 식별자를 보관했다.

## 완료한 6개 Step·이동 대응

여섯 구간을 동시에 제공했고 사용자 직접 작성·묶음 리뷰·현재 소스 재검증을 완료했다. 아래 표식은 학습 이력이며 현재 코드에서는 제거했다.

| Step | 이전 위치 → 현재 파일 (서비스 Java 루트 기준) | 완료한 메서드·이전 학습 표식 |
|---|---|---|
| 01 | `Members.authenticate` → `application/AuthenticateMemberService.java` | `authenticate` · LEARN-01, 조회/포트/도메인 규칙 조율 |
| 02 | 루트 SecurityConfiguration → `config/SecurityConfiguration.java` | `connectLogin` · LEARN-02, 세션/CSRF/컨텍스트 연결 |
| 03 | `Members.current` → `application/GetCurrentMemberService.java` | `get` · LEARN-03, UUID 입력으로 현재 회원 확인 |
| 04 | 루트 BoundaryFilter → `infrastructure/web/BoundaryFilter.java` | `caller` · LEARN-04, 호출자 사전 검증 |
| 05 | `AuthController.validateOriginalMethod` → `interfaces/web/InternalAuthController.java` | `validateOriginalMethod` · LEARN-05, 원본 메서드별 CSRF |
| 06 | 루트 BoundaryFilter → `infrastructure/web/BoundaryFilter.java` | `finish` · LEARN-06, 세션 저장 완료 후 전송/실패 변환 |

사용자가 여섯 본문을 모두 작성했고 리뷰·검증 후 중복 참고/임시 표식을 정리했다. 학습 설명은 [회원·세션 인증](../explanations/member-session-authentication.md)에 유지한다.

## 사용자 직접 테스트 방법

아래는 현재 완료된 활성 구현에서 실행하는 재현 절차다. 실제 자동 실행 검증은 위 최종 결과에 기록했다. PowerShell 수동 키 입력까지 자동 검증했다고 표시하지 않는다.

### 준비와 자동 검사

Docker Desktop Linux 엔진, Python 3, JDK 21의 `JAVA_HOME`, PowerShell을 준비한다. 기존 secret을 교체하지 않는다. 포트 5432/8888/8761/8080/8081/8000과 추가 JAR 18081/18082를 비운다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
python scripts/prepare-ci-secrets.py
.\gradlew.bat build --no-daemon
docker compose up -d --build --wait --wait-timeout 180
python scripts/check-postgres.py --stage application --persistence
python scripts/check-discovery.py
python scripts/check-gateway.py
python scripts/check-api-observability.py --db-recovery
python scripts/check-member-auth.py
```

각 명령 exit 0, 전체64개 테스트 실패/오류/skip 0, 검사별 PASS가 정상이다. DB 장애 검사와 제품 JAR 검사는 동시에 실행하지 않는다. 회원 검사는 고유 probe 계정·해당 세션만 정리하고 두 자식 JAR을 종료한다. 일반 계정/데이터는 삭제하지 않는다.

### 회원 준비·직접 요청

가입 API 대신 명시적으로 로컬 회원을 준비한다. 비밀번호는 숨김 프롬프트에 입력한다. 같은 ID가 이미 있으면 기본 실행은 실패하며 기존 해시를 보존한다. 아래 ID는 본인이 사용할 새 ID로 바꿀 수 있다.

```powershell
python scripts/member-tool.py learner007 --roles BUYER
```

같은 PowerShell에서 쿠키 jar에 해당하는 WebRequestSession과 토큰을 유지한다. 비밀번호·쿠키·토큰·secret을 출력하거나 명령행 인자로 넣지 않는다.

```powershell
$base = 'http://localhost:8081'
$seed = Invoke-RestMethod "$base/api/auth/csrf" -SessionVariable browserSession
$hidden = Read-Host 'Member password' -AsSecureString
$plain = [System.Net.NetworkCredential]::new('', $hidden).Password
$body = @{ loginId = 'learner007'; password = $plain } | ConvertTo-Json
try {
    Invoke-RestMethod "$base/api/auth/login" -Method Post -WebSession $browserSession `
        -Headers @{ 'X-CSRF-TOKEN' = $seed.token } -ContentType 'application/json' -Body $body
} finally { $plain = $null; $body = $null; $hidden = $null }
$fresh = Invoke-RestMethod "$base/api/auth/csrf" -WebSession $browserSession
Invoke-RestMethod "$base/api/members/me" -WebSession $browserSession
$internal = @{
    'X-Internal-Caller' = 'api-gateway'
    'X-Internal-Credential' = [IO.File]::ReadAllText((Join-Path $PWD 'secrets/member_internal_credential')).TrimEnd("`r", "`n")
    'X-CSRF-TOKEN' = $fresh.token
}
Invoke-RestMethod "$base/internal/auth/check" -Method Post -WebSession $browserSession `
    -Headers $internal -ContentType 'application/json' -Body '{"method":"GET"}'
Invoke-RestMethod "$base/internal/auth/check" -Method Post -WebSession $browserSession `
    -Headers $internal -ContentType 'application/json' -Body '{"method":"POST"}'
Invoke-WebRequest "$base/api/auth/logout" -UseBasicParsing -Method Post -WebSession $browserSession `
    -Headers @{ 'X-CSRF-TOKEN' = $fresh.token } | Select-Object StatusCode
$internal = $null; $seed = $null; $fresh = $null
```

기대 결과: CSRF 발급200, 로그인200 `{memberId,roles}`, 로그인 후 재발급200, 본인 조회/내부 GET/내부 POST 확인200, 로그아웃204·쿠키 만료. 로그인 전후 GP_SESSION이 바뀐다. 토큰을 변수로만 보관한다.

### 주요 실패·역할/상태 변경

`Invoke-RestMethod`의 비정상 응답은 예외를 던진다. `catch`에서 `[int]$_.Exception.Response.StatusCode`로 상태를 확인하거나 응답의 Problem JSON에서 `code`를 확인한다.

| 조건 | 기대 |
|---|---|
| 쿠키 없이 me 또는 로그아웃 후 me | 401 `UNAUTHENTICATED` |
| 토큰 없이 로그인/로그아웃, 내부 원본 변경 요청의 토큰 누락/오답 | 403 `CSRF_INVALID` (내부는 먼저 유효한 사용자 인증 필요) |
| 올바른 CSRF + 없는 ID/오답/비활성 | 401 `AUTHENTICATION_FAILED`, 상세 상태 미노출 |
| 내부 호출자 헤더 누락/오답/중복 | 403 `INTERNAL_CALLER_DENIED`, DB 장애 중에도 동일 |
| 중복 GP_SESSION, 내부 TRACE/CONNECT/미지정/잘못된 JSON | 400 `INVALID_REQUEST` |
| 지원하지 않는 입력 Content-Type | 415 `UNSUPPORTED_MEDIA_TYPE` (공개 로그인은 먼저 유효 CSRF 필요) |
| 알려진 경로의 다른 메서드 | 405, 실행 없음 |
| Gateway 8000의 `/api/members/me`, `/internal/auth/check` | 404 (008 미연결) |

현재 세션을 유지한 채 별도 터미널에서 다음 도구를 실행하면 다음 me/내부 확인에 현재 DB 변경이 반영된다. update는 비밀번호 해시를 바꾸지 않는다. 상태 변경 후에도 유효 CSRF로 로그아웃할 수 있다.

```powershell
python scripts/member-tool.py learner007 --update --roles BUYER,SELLER
python scripts/member-tool.py learner007 --update --roles BUYER --status DISABLED
# 상태 복구
python scripts/member-tool.py learner007 --update --roles BUYER --status ACTIVE
# 비밀번호 교체가 필요할 때만 명시하고 숨김 프롬프트 입력
python scripts/member-tool.py learner007 --update --reset-password --roles BUYER
```

두 인스턴스·짧은 만료·마지막 저장/삭제 장애·동시 요청은 `MemberAuthenticationTests`로 재현한다. 수동 sleep에 의존한 경합 성공을 근거로 쓰지 않는다.

### DB 장애·복구와 종료

직접 관찰할 때 `docker compose stop postgres` 후 me/올바른 내부 확인은503, 잘못된 호출자는403, member liveness200/readiness503이다. `docker compose start postgres` 후 같은 프로세스에서 회복을 기다린다. DB를 내린 동안 다른 검사를 실행하지 않는다. 복구 실패 시 `docker compose up -d --wait --wait-timeout 120 postgres`로 DB 상태를 먼저 확인한다. 일반 볼륨을 지우지 않는다.

전용 내부 secret 교체는 호출 중단 → `secrets/member_internal_credential`을 새 무작위 32바이트 이상 값으로 로컬 보호 파일에서 교체 → `docker compose up -d --force-recreate --wait member-service` 순서다. 준비 스크립트는 기존 파일을 덮어쓰지 않는다. 이전 값을 자식 프로세스 메모리에 보관한 검사에서403, 새 값에서 정상 사용자 확인200을 확인한다. 비밀값을 argv/문서/로그에 넣지 않는다. 008부터 Gateway도 함께 교체해야 하며 무중단 교체는 제공하지 않는다. 실제 사용자 secret 교체 자체는 이번 검증에서 하지 않았다.

검사 종료는 `docker compose stop`을 사용한다. 직접 띄운 자식 Java 프로세스는 해당 터미널의 Ctrl+C로 종료한다. `down -v`, Flyway clean/repair, DB 초기화 스크립트 재실행은 필요하지 않다. 쿠키 jar/토큰·비밀번호 임시 변수는 터미널 종료로 정리한다. 직접 준비한 learner 계정은 기본 보존된다.

## 실행 후 상태·남은 작업

- 임시 PostgreSQL 테스트 컨테이너는 Testcontainers가 정리했고, Windows 회원 자식 프로세스와 임의 probe 회원/세션도 정리했다. 일반 member schema/V1과 새 전용 secret은 의도한 구현 결과로 남긴다.
- 착수 때 엔진이 꺼져 있었고 실행 중 컨테이너가 없었다. 검증 후 이 작업에서 기동한 Compose 서비스는 stop하여 정지 상태로 돌렸다. 일반 볼륨과 secret은 보존했다. Docker Desktop 엔진은 켜져 있다.
- 현재 실제 소스의 여섯 사용자 작성 본문과 최종 제품 검증을 마쳤다. 작성 대기 placeholder·중복 참고 주석 없음. 과거 별도 사본 근거와 위 최종 결과를 구분한다.
- 007 로컬 완료. 다음 기능은008 Gateway 인증 연결이며 별도 요청을 따른다. 커밋·push·PR·병합은 수행하지 않았다.
