# 007. 회원·세션 인증

작성일: 2026-11-05 KST

검토일: 2026-11-05 KST — 1회 설계 검토 반영. 문서만 수정했으며 구현·실행 검증은 하지 않았다.

추가 합의: 2026-11-05 DDD 패키지·의존 방향 채택. 아래 재구성 계획을 추가했으며 이번 설계 채팅에서는 소스 이동·분리·실행 검증을 하지 않았다.

상태: **완료 — DDD 구조·사용자 6개 Step 작성·묶음 리뷰·현재 소스/제품 재검증 완료. Gateway 인증 연결은008**

[구현 목록](../README.md) · [로드맵](roadmap.md) · [프로젝트 인계](../../handoff.md)

<a id="execution-summary"></a>

## 실행 요약

- 목표: 독립 회원 서비스에서 로그인·로그아웃·세션 공유·현재 회원 인증 확인을 완성한다.
- 현재: 001~007 로컬 구현·검증 완료. [사용자 작성·최종64개 결과·직접 테스트](../007-member-session-authentication.md#final-results) 참조. 여섯 작성 구간을 리뷰·재검증하고 참고/임시 표식을 정리했다.
- 원격: 2026-11-05 착수 시 PR #8 OPEN, head `1def2d5`·main `e5c87d8` CI 성공 확인. 006이 포함된 head에서 `feat/member-session-authentication` 생성. 상세 근거는 결과 문서가 소유한다.
- 착수 기준: 구현 요청 시 최신 원격 상태를 확인하고 006 변경이 포함된 기준을 결정한다. PR #8 병합을 가정하지 않는다.
- 기존 합의: Spring Security + Spring Session JDBC, 회원 서비스의 PostgreSQL 세션, 유휴 만료 30분.
- 기존 합의: 로그인 시 세션 ID 변경, 로그아웃 시 현재 세션 무효화, 매 인증 확인에서 현재 역할·계정 상태 조회. 추천 구현은 기존 세션 무효화·새 세션 생성 전략이다.
- 기존 합의: 로그인·로그아웃을 포함한 쿠키 기반 변경 요청에 CSRF 검증을 적용한다.
- 기존 합의: Gateway·업무 서비스는 회원 DB를 읽지 않고 내부 인증 확인 API를 사용한다.
- 포함: 회원 모듈·테이블·비밀번호 검증·세션·CSRF·내부 API·호출자 자격 증명 검증.
- 포함: Config/Eureka·이미지/Compose·006 오류/ID/JSON 로그/probes/metrics·CI·직접 테스트 안내.
- 추가 합의: [DDD 패키지 재구성](#ddd-packages). 기존 `Members`의 업무/사용 사례/저장소/Security 책임을 분리하고 6개 학습 구간·기존 사용자 작성을 보존한다. 새 구조 소스와 6개 참고/입력 위치를 반영했고 별도 사본에서 검증했다. 실제 소스의 사용자 작성·묶음 리뷰·재검증도 완료했다.
- 제외: Gateway의 회원 공개 라우팅·인증 필터·외부 신원 헤더 제거·업무 서비스 신뢰 연결은 008.
- 제외: 가입·프로필 수정·비밀번호 재설정·역할 관리 UI/API·이메일·OAuth·JWT·Redis·동시 로그인 제한.
- 추천: 가입 API 없이 격리 테스트와 명시적 로컬 준비 도구로 회원을 준비해 인증에 집중한다.
- 추천: 역할은 BUYER/SELLER/OPERATOR 복수 보유, 상태는 ACTIVE/DISABLED, 공개 역할 부여 API는 만들지 않는다.
- 추천: JSON 로그인, 세션 저장 CSRF 토큰, 마스킹된 토큰 발급 JSON 응답과 공개/내부 공통 `X-CSRF-TOKEN` 헤더.
- 추천: 세션 쿠키 `GP_SESSION`, Path=/, HttpOnly, SameSite=Lax, Domain 미지정, HTTPS에서는 Secure.
- 추천: `member-service` 모듈, 앱 이름 `member`, 내부 포트 8081·로컬 loopback 8081.
- 추천: 회원 정보와 세션 스키마를 Flyway로 관리하고 Spring Session 자동 DDL은 끈다.
- 경계: 내부 API는 세션 필터보다 앞에서 전용 Gateway 자격 증명을 검증한다. 작은 인증 응답은 세션 저장 완료 후 전송한다.
- 경계: 이번 내부 호출자는 자동/수동 검사 클라이언트다. 실제 Gateway에 비밀값을 주입하는 작업은 008이다.
- 경계: 비밀번호·쿠키·CSRF 토큰·내부 자격 증명·로그인 본문은 로그/오류/지표에 기록하지 않는다.
- 학습: 핵심 6개 Step을 기준 구현 검증 후 동시에 준비하고 사용자 작성·묶음 리뷰/재검증을 따른다.
- 완료: 실제 PostgreSQL과 두 회원 인스턴스에서 공유·ID 회전·만료·로그아웃·현재 권한·CSRF·장애 거절 확인.
- 완료: 누적 Compose·CI 검사 확장, Windows 제품 JAR, 사용자 직접 테스트·결과·학습 문서·인계 기록.
- 진행 합의: 사용자가 1회 검토를 반영한 추천안 전체를 채택했다. Gateway 인증 연결은 008로 유지한다.

## 목표와 설계 근거

[회원 서비스 책임](../../design/architecture.md#회원-서비스-책임), [인증과 권한](../../design/architecture.md#인증과-권한), [실행 환경](../../design/operations.md)을 실제 회원 서비스의 경계로 옮긴다. 007은 인증 제공자를 만들고, 008은 Gateway와 업무 서비스가 그 결과를 신뢰하는 경로를 완성한다. 007 완료를 전체 외부 인증 흐름 완료로 표시하지 않는다.

006의 오류·추적 처리는 현재 각 모듈의 구현을 참고해 회원 서비스에 적용한다. 엔티티·업무 로직을 공유하지 않으며 공통 starter 추출도 이번 범위에서 제외한다.

## 추천 선택과 대안

| 항목 | 추천 | 이유·대안 |
|---|---|---|
| 회원 생성 | 테스트 fixture + 명시적 로컬 준비 도구 | 가입·멱등성·중복 가입을 별도 범위로 분리. 공개 가입이 필요하면 구현 전에 이 계획의 범위·완료 기준을 확장 |
| 저장 접근 | JDBC, 서비스 소유 트랜잭션 | 기존 JDBC 기반 유지. JPA 도입은 인증 구현에 필수 아님 |
| 비밀번호 | DelegatingPasswordEncoder의 bcrypt 저장 형식 | 해시 형식 식별 및 이후 교체 가능. 평문·복호화 저장 금지. bcrypt 입력은 UTF-8 72바이트 이내로 제한하고 조용한 절단 금지 |
| 인증 구현 | Security 필터 기반 JSON 로그인 | 프레임워크 인증 관리자·세션 전략·컨텍스트 저장 경로 재사용. 컨트롤러 수동 로그인은 누락 위험으로 제외 |
| 세션 고정 방지 | Spring Security `migrateSession` 전략 | 이전 세션을 무효화하고 새 저장 행·ID에 인증을 저장. 동일 행의 ID만 바꾸는 방식에서 늦은 이전 요청의 갱신이 ID를 되돌릴 가능성을 피함 |
| CSRF 저장 | HttpSessionCsrfTokenRepository | 세션 소유 서비스에서 토큰 검증. JS가 세션 쿠키를 읽을 필요 없음. 이중 제출 쿠키 방식은 이번에 도입하지 않음 |
| 내부 호출자 | Gateway 전용 고엔트로피 정적 secret | Compose secret/configtree와 호스트 환경변수로 전달. JWT/mTLS보다 초기 구성이 작지만 신뢰 네트워크·HTTPS가 필요하고 유출 시 교체 필요 |
| 세션 저장 | Spring Session JDBC 기본 속성 직렬화 | 의존 버전의 스키마·직렬화 호환성 확인. 임의 JSON 직렬화·조회 캐시·Redis 전환 제외 |

버전은 기존 Java/Boot/Cloud/Gradle을 유지하고 Security·Session은 Boot BOM 관리값을 사용한다. 구현 시작 때 실제 해결된 버전과 대응 문서를 확인하며 이번 문서 확인만으로 호환성 검증 완료를 주장하지 않는다.

<a id="ddd-packages"></a>

## 추가 합의: DDD 패키지 재구성

구현 반영: 2026-11-05 소스 분리·이동 및 새 참고 구현의 회귀/세션 호환성 검증 완료. 실제 배치·학습 대응표·검증 근거는 [결과 문서](../007-member-session-authentication.md)가 소유한다. 여섯 구간의 사용자 작성·묶음 리뷰·재검증까지 완료했다.

목표 구조는 [아키텍처의 패키지·의존 방향](../../design/architecture.md#package-structure)을 따른다. 기존 기능·DB 스키마·HTTP/쿠키/CSRF 계약을 유지하며 클래스 책임과 의존성을 재구성한다. 이번 설계 합의로 008 기능을 추가하거나 이미 끝난 001~006 전체를 일괄 재구성하지 않는다.

회원 서비스의 `com.example.member` 아래 목표 배치:

```text
MemberApplication
domain/member/
  Member, MemberId, MemberStatus, Role, MemberRepository
application/
  AuthenticateMemberService, GetCurrentMemberService
  ManageLocalMemberService
  port/PasswordVerifier
interfaces/
  web/AuthController, MemberController, InternalAuthController
  web/dto/  (HTTP 입력·응답)
  cli/LocalMemberTool
infrastructure/
  persistence/JdbcMemberRepository
  security/MemberAuthenticationProvider, JsonLoginFilter
  security/SpringPasswordVerifier, HeaderCsrfHandler
  web/BoundaryFilter, BufferedResponse, RequestIdFilter
  web/error/ApiErrors, Problems
config/
  SecurityConfiguration, ConfigurationIdentity, ConfigurationInfoContributor
  EarlyFailureLogging
```

예시의 타입 이름은 위 역할을 드러내도록 사용한다. 필요한 애플리케이션 결과·인증 실패 타입도 application에 둔다. 프레임워크의 인증 예외와 HTTP 상태를 가진 기존 `AuthFailure`를 domain/application에 그대로 옮기지 않고, 외부에서 기존 코드·상태로 변환한다. 비밀번호/해시가 결과 DTO·세션 principal·오류에 실리지 않게 한다.

| 현재 책임 | 재구성 |
|---|---|
| `Members`의 회원/역할 SQL | domain의 `MemberRepository`와 infrastructure의 `JdbcMemberRepository`로 분리. 계정·역할을 함께 조회하는 기존 트랜잭션 경계 유지 |
| `Members.authenticate` | application에서 조회·비밀번호 포트·도메인 상태 규칙을 조율. infrastructure의 `MemberAuthenticationProvider`가 Authentication/권한/principal 변환 |
| `Members.current` | `GetCurrentMemberService`가 회원 ID를 받아 현재 상태/역할 확인. SecurityContext와 세션에서 ID를 꺼내는 처리는 외부 계층 |
| 한 `AuthController`의 세 경로 | CSRF·본인 조회·내부 확인 컨트롤러로 역할 분리. 내부 입력 → 기존 세션 → 현재 회원 → CSRF 순서 유지 |
| `LocalMemberTool`의 SQL/트랜잭션 | 명령행 어댑터와 로컬 관리 사용 사례/저장소 호출로 분리. local/loopback·명시 갱신·해시 보존·비밀값 주입 계약 유지 |
| 세션/CSRF/응답 보류/관측 | infrastructure에 배치. 기존 필터 상대 순서·최종 JDBC 저장 후 전송·오류 분류 유지 |

저장소는 지금 필요한 계정/역할 조회·로컬 관리 연산만 제공한다. 기술별 저장소·추상 기반 클래스·범용 CRUD 계층을 추가하지 않는다. 비밀번호 검증 포트는 없는 회원의 dummy 검증도 지원해 기존 실패 경로의 비용 완화 처리를 유지한다. domain의 상태/역할 규칙을 application과 Security 어댑터에서 중복 구현하지 않는다.

세션 호환성: 저장된 principal의 `com.example.member.Members$Identity` 타입 이름·UUID 필드·직렬화 버전을 유지한다. 과도기 `Members`에는 호환용 중첩 타입만 남길 수 있으며 SQL·업무·인증 처리 로직을 다시 넣지 않는다. 어댑터에서 이 타입과 `MemberId`를 변환한다. 기존 로그인 쿠키가 재구성한 앱의 다른 인스턴스/재시작 후에도 사용되는지 검사하며, 패키지 이동을 이유로 일반 세션/DB를 일괄 삭제하지 않는다.

구현 순서와 학습 보존:

1. 구현 채팅에서 현재 6개 입력 위치와 사용자 작성 여부를 먼저 확인한다. 사용자 작성 본문을 보존하고 이동 전후의 Step 대응표를 기록한다. 설계 채팅에서는 작성 대기 코드를 채우지 않는다.
2. 모델·저장소·포트·사용 사례·어댑터로 책임을 분리하고 import·접근 범위·Bean 연결·테스트를 갱신한다. 패키지를 옮기면서 순환 참조를 만들거나 단순히 모든 타입/메서드를 public으로 바꾸지 않는다.
3. **미작성 구간만** 새 구조의 기준 구현으로 검증 후 참고/입력 구간으로 준비한다. 이미 작성한 구간은 새 구조에서도 활성 구현을 유지하며 다시 비우지 않는다. LEARN-01은 사용 사례의 비밀번호/상태 확인, 03은 현재 회원 확인으로 대응하고, 02·04·05·06의 기존 학습 목적도 유지한다.
4. 실제 HTTP·JDBC 경계·세션 호환성·계약 회귀 및 의존 방향을 확인한다. 원래 007의 나머지 필수 검증은 변경 영향에 맞게 수행한다. 이전 평면 구조의 성공 근거를 재구성 소스의 성공으로 표시하지 않는다.
5. 결과 문서의 실제 배치·6개 작성 위치·검증 대상과 관련 학습/인계를 갱신한다. 모든 구간 작성·재검증이 끝나야 007을 완료로 표시한다.

## 회원과 DB

- `member.app.members`: UUID 회원 ID, 정규화한 고유 로그인 ID, 비밀번호 해시, 계정 상태, 생성/변경 시각. 로그인 ID는 소문자 ASCII 영문·숫자·`._-`, 3~50자로 제한한다. 이메일 인증 의미는 부여하지 않는다.
- `member.app.member_roles`: 회원 ID·역할 복합 고유키와 FK. ACTIVE 회원은 역할을 최소 하나 보유하도록 준비/변경 도구의 트랜잭션에서 검증한다. 알 수 없는 역할·상태는 허용하지 않는다.
- `member.app.SPRING_SESSION`·속성 테이블: 실제 해결된 Spring Session의 PostgreSQL DDL·인덱스·FK를 해당 회원 모듈 Flyway migration에 반영한다. PostgreSQL 식별자와 currentSchema=app 설정을 맞춘다.
- `spring.session.jdbc.initialize-schema=never`, 만료 정리 초기 주기는 매분. 논리적 만료는 조회 시 적용하고 정리 스케줄 실행 전에도 만료 세션을 거절한다.
- 세션에는 비밀번호 해시·회원 엔티티를 보관하지 않는다. 직렬화 가능한 최소 principal(안정적인 회원 ID)을 사용하고 요청마다 현재 회원·역할을 조회한다. 기본 UserDetails의 비밀번호가 세션에 남지 않는지도 검사한다.
- `/api/members/me`와 내부 확인은 공통 현재 회원 조회 로직으로 회원 존재·ACTIVE·현재 역할을 확인한다. 삭제/비활성/역할 없음은 401로 거절한다. 세션에 남아 있는 과거 authorities로 `hasRole`을 판정하지 않고 이번 요청의 조회 결과로 응답·권한을 구성한다. 정상 상태 변경이 다음 요청에 반영된다는 의미이며 이미 조회를 마친 요청의 강제 철회까지 보장하지 않는다.
- 일반 마이그레이션에 기본 계정/비밀번호를 넣지 않는다. 로컬 도구는 명시적 실행·local 프로필과 loopback DB 한정·입력 비밀값 비노출·명시한 ID만 대상으로 한다. 기존 회원/해시는 기본 보존하고 변경은 명시적인 갱신 옵션에서만 수행한다. 비밀번호는 대화형 숨김 입력 또는 보호된 주입으로 받고 명령행 인자에 넣지 않는다. 자동 테스트는 격리 DB에 직접 fixture를 준비한다.
- 역할/상태 변경 검증은 테스트 fixture·문서화된 로컬 관리 절차로 수행한다. 일반 사용자의 자가 운영자 승격 API는 없다.

## HTTP 계약

이번 단계는 `http://localhost:8081` 직접 호출로 검증한다. 아래 외부용 경로도 Gateway 8000에는 아직 연결하지 않는다.

| 메서드·경로 | 입력·성공 | 주요 실패 |
|---|---|---|
| GET `/api/auth/csrf` | 익명 허용. 세션을 필요 시 생성하고 200 `{token, headerName}` | 저장소 장애 503 |
| POST `/api/auth/login` | JSON `{loginId,password}` + 세션 쿠키·CSRF. 200 `{memberId,roles}` | 입력 400, 인증 실패 401, CSRF 403, 저장소 장애 503 |
| GET `/api/members/me` | 인증 쿠키. 200 `{memberId,roles}`; 현재 DB 값을 반환 | 미인증·만료·비활성 401, 저장소 장애 503 |
| POST `/api/auth/logout` | 유효한 CSRF. 익명/비활성 여부와 관계없이 현재 세션 삭제·쿠키 만료, 204 | CSRF 403, 저장소 장애 503 |
| POST `/internal/auth/check` | 호출 자격 증명·쿠키·`X-CSRF-TOKEN` + JSON `{method}`. 200 `{memberId,roles}` | 호출자 403, 입력 400/415, 사용자 인증 401, CSRF 403, 확인 불가 503 |

실패는 006의 RFC 9457 + `code`·`requestId`와 `application/problem+json`을 따른다. 로그인 실패는 없는 ID·오답·비활성 계정을 같은 `AUTHENTICATION_FAILED`로 표현한다. 세션 없음/만료는 `UNAUTHENTICATED`, 토큰 실패는 `CSRF_INVALID`, 내부 호출자 실패는 `INTERNAL_CALLER_DENIED`, DB 확인 불가는 `AUTHENTICATION_UNAVAILABLE`로 구분한다. 상세 계정 상태나 예외 원문을 반환하지 않는다.

공개 로그인/로그아웃은 CSRF 검사가 먼저여서 토큰이 없거나 만료 세션에 속하면 403이다. 올바른 CSRF의 로그인 자격 증명이 틀리면 401이다. 로그아웃은 현재 계정의 ACTIVE/역할 조건을 요구하지 않아 비활성 계정도 세션을 정리할 수 있다. 세션이 없으면 CSRF부터 다시 발급해야 하며 CSRF 없는 로그아웃을 허용하는 의미가 아니다.

잘못된 JSON·미지원 타입은 각각 400·415로 확인하며 보안 필터 오류도 컨트롤러 오류와 같은 형식을 사용한다. DB 연결·시간 초과·세션 읽기/저장/삭제 불가는 503으로 분류하고, 직렬화 불일치·잘못된 스키마·구현 오류를 일괄 503으로 숨기지 않는다. 인증 redirect·HTML 로그인 화면·HTTP Basic·remember-me·요청 저장용 RequestCache는 사용하지 않는다. 인증/토큰의 성공·실패 응답 모두 `Cache-Control: no-store`를 적용한다.

상위 설계에서 정확한 키 적용 대상은 미정이므로, 이번 로그인/로그아웃에는 업무 접수용 `Idempotency-Key` 영속화를 적용하지 않는 안을 추천한다. 비밀번호·쿠키·토큰이나 로그인 성공 응답을 멱등성 기록에 저장/재생하지 않는다. 응답 유실/503은 세션 변경이 전혀 없었다는 보장이 아니므로 본인 조회로 상태를 확인하고 필요하면 CSRF 발급부터 다시 시작한다. 이후 공동구매·참여·결제 등 업무 변경은 기존의 업무 변경과 같은 트랜잭션에 멱등성 기록을 저장하는 원칙을 유지한다.

### 로그인·세션·CSRF 순서

1. CSRF 발급으로 익명 세션과 토큰을 얻는다. 쿠키와 토큰을 유지해 로그인한다.
2. 인증 성공 시 Spring Security의 세션 고정 방지 전략으로 이전 세션을 무효화하고 새 세션·ID를 만든 뒤 SecurityContext를 JDBC에 저장한다. CSRF 초기화 전략도 함께 실행한다. 사용자 정의 로그인 필터에 세션 전략과 HttpSession 기반 SecurityContextRepository를 명시적으로 연결하며 필터 기본값에 맡기지 않는다.
3. 로그인 후 CSRF 발급 API를 다시 호출해 새 토큰을 받는다. 로그인 전 토큰을 계속 사용하지 않는다.
4. 조회·인증 확인은 기존 세션만 사용한다. 유휴 시간은 유효한 세션 접근에서 갱신하고 없는 세션을 새 인증 세션으로 만들지 않는다.
5. 로그아웃 후 현재 세션은 다른 인스턴스에서도 거절한다. 다음 로그인은 새 CSRF 발급부터 시작한다. 이미 실행 중인 업무 취소·사용자의 다른 세션 전체 종료는 포함하지 않는다.

세션 고정 방지에 `migrateSession`을 추천하는 근거는 JDBC 저장 경합이다. 확인한 [Spring Session 4.0.x 소스](https://github.com/spring-projects/spring-session/blob/4.0.x/spring-session-jdbc/src/main/java/org/springframework/session/jdbc/JdbcIndexedSessionRepository.java)의 기본 UPDATE는 PRIMARY_ID로 행을 찾아 SESSION_ID도 갱신한다. 이 구조에서는 ID 변경 전 읽은 요청의 늦은 저장이 이전 ID를 다시 쓸 가능성이 있어 같은 행의 ID 변경만으로 충분하다고 가정하지 않는다. 이는 소스에 근거한 위험 판단이며 실제 BOM 버전의 전략·동시 요청 결과를 구현 시 검증한다.

로그인·로그아웃 시 CSRF 토큰 재발급이 필요하다는 점은 [Spring Security CSRF 문서](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html)를 따른다. 직접 인증 코드를 추가한다면 세션 전략 호출과 컨텍스트 영속화 누락 여부를 [인증 구조](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html) 및 [영속화 문서](https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/persistence.html)에 대조한다.

### 쿠키·출처

쿠키는 브라우저 세션 쿠키로 두고 서버 유휴 만료를 30분으로 한다. local/docker HTTP에서는 Secure=false를 명시하고 HTTPS 운영 설정은 Secure=true를 필수로 한다. HTTPS 종료/신뢰 프록시 구성은 008 이후 환경과 연결하며 이번에 HTTPS 실측 성공을 주장하지 않는다. 개발 중 쿠키는 포트별로 격리되지 않으므로 전용 이름을 쓰고 기존 쿠키 충돌을 검사한다.

초기 웹 클라이언트는 Gateway와 같은 출처를 전제한다. 임의 CORS 허용·와일드카드 credential 설정은 하지 않는다. 교차 출처 UI는 별도 합의가 필요하다.

### 보안 필터·관측·응답 저장 경계

- 상대 순서는 요청 ID/완료 로그 → 작은 인증 응답의 전송 보류·저장소 오류 처리 → 내부 호출자/중복 쿠키 사전 검증 → Spring Session 필터 → Spring Security → API 처리다. 호출자 거절은 세션 필터에 진입하기 전에 끝내므로 잘못된 쿠키를 붙여도 DB 조회·세션 연장이 없어야 한다. 필터의 이중 등록도 방지한다.
- CSRF/로그인/본인 조회/로그아웃/내부 확인의 작은 응답에만 전송 보류를 적용한다. JDBC 세션의 최종 저장·삭제 완료 전에는 본문·성공 상태·새 쿠키를 실제 응답으로 커밋하지 않는다. 실패 시 보류된 성공 본문·Set-Cookie를 버리고 503 오류를 작성한다. 무제한 버퍼링·업무 서비스/Gateway 응답 버퍼링은 추가하지 않는다.
- 세션 처리는 보안 필터와 MVC 예외 처리 바깥에서도 실패할 수 있다. 세션 필터의 종료 시 저장 실패까지 외부 경계에서 처리하고, 완료 로그는 최종 상태를 기록한다. [Spring Session 필터 API](https://docs.spring.io/spring-session/reference/4.0/api/java/org/springframework/session/web/http/SessionRepositoryFilter.html)의 실행 순서를 실제 해결 버전에서 확인한다.
- 정확한 메서드·경로만 허용하며 GET의 HEAD 의미도 유지한다. 공개 CSRF/로그인/로그아웃과 인증된 본인 조회, 호출자 검증을 통과한 내부 확인 외의 업무/내부 경로는 404다. 알려진 경로의 미지원 메서드는 405로 거절하며 실행되지 않는다. 기본 `/login`·`/logout`은 열지 않는다. ERROR dispatch가 저장소 오류를 다시 인증 오류로 바꾸거나 재귀 호출하지 않게 한다.
- 직접 loopback/내부 진단용 Actuator의 기존 GET/HEAD health·info·metrics와 probes는 쿠키 없이 검사할 수 있게 하며 세션을 생성/조회하지 않는다. DB 장애 시 liveness는 유지하고 readiness는 DB 실패를 반영한다. Gateway 8000에서 Actuator·내부 경로는 계속 404다.
- 006을 참고한 회원 요청 ID·정규화 경로·오류/완료 로그는 `/api/**`뿐 아니라 `/internal/auth/check`와 그 사전 거절도 포함한다. 토큰/세션·호출 자격 증명·로그인 ID/본문은 로그와 지표 라벨에서 제외한다.

### 내부 인증 확인과 008 인계 계약

- `X-Internal-Caller: api-gateway`, `X-Internal-Credential: <secret>`을 먼저 검증한다. 중복/잘못된 호출자 헤더는 거절하고 비밀값은 안전한 비교를 사용한다. 다른 서비스 자격 증명을 재사용하지 않는다.
- 기존 쿠키는 HTTP `Cookie` 헤더의 불투명 값으로 전달하며 DB의 세션 ID로 임의 변환하지 않는다. 본문은 `{method}`이고 method는 원본 HTTP 메서드다. 허용 메서드는 GET/HEAD/OPTIONS/POST/PUT/PATCH/DELETE로 한정한다. TRACE/CONNECT/미지정/알 수 없는 값은 400이며 CSRF 면제를 얻지 못한다. 중복 `GP_SESSION`은 공개/내부 모두 세션 조회 전에 400으로 거절한다.
- 원본 GET/HEAD/OPTIONS는 CSRF 검사를 생략하고, 나머지는 `X-CSRF-TOKEN` 헤더로 받은 마스킹 토큰을 검증한다. 008은 실제 원본 HTTP 메서드와 클라이언트 헤더로 내부 요청을 직접 구성한다. 클라이언트의 메서드 덮어쓰기용 헤더·본문은 신뢰하지 않는다. 토큰은 헤더 한 개만 허용하며 쿼리/폼/JSON 대체 입력은 받지 않는다.
- 내부 POST 자체의 일반 CSRF 필터 제외는 이 경로 하나로 한정한다. 전용 호출자 검증과 원본 변경 요청의 토큰 검증을 대체 구현하며 `/internal/**` 전체 면제는 하지 않는다.
- 발급 응답은 요청 속성의 마스킹된 CsrfToken을 사용하고 공개/내부 모두 `XorCsrfTokenRequestAttributeHandler`의 해석을 재사용해 저장 원본과 비교한다. 내부 확인은 `loadToken`으로 기존 토큰만 읽고 없으면 403이며, 토큰을 생성/저장하는 deferred 경로를 사용하지 않는다. 마스킹 알고리즘을 직접 구현하지 않는다. [공식 CSRF 처리 설명](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html)을 따른다.
- 없는 세션은 생성하지 않고, 유효한 기존 세션 접근은 유휴 시간을 갱신해 업무 요청 활동이 반영되게 한다. 호출자 실패는 세션 조회/연장을 하지 않는다.
- 정상 내부 확인은 새 쿠키를 발급하지 않는다. 만료/미인증에서 쿠키 정리는 008의 외부 응답 정책으로 인계한다. 회원 공개 응답의 Set-Cookie는 008에서 전달 검증한다.
- 내부 처리의 순서는 호출자 → 입력 → 기존 세션/현재 회원 → 원본 변경 요청의 CSRF다. 올바른 호출자와 입력이라면 세션 없음/만료/비활성은 먼저 401, 유효한 인증의 변경 요청에서 토큰 누락/오답은 403이다. 세션/회원 확인 불가는 503이다. 공개 로그인/로그아웃의 CSRF 우선 순서와 구분한다.
- 내부 경로의 SecurityContext 조회·자동 인증 판정이 본문 검증보다 먼저 세션을 읽지 않도록 구성한다. 공개 경로의 CSRF 필터도 내부 확인의 본문 검증/CSRF 정책을 대신하지 않는다.
- 007은 회원 측 자격 증명 수신·검증과 교체 절차를 구현한다. 008에서 Gateway secret 주입·라우팅·헤더 제거·타임아웃·업무 서비스 직접 접근 차단을 완성한다. 공유 secret은 재전송 방지 서명이 아니며 운영 전송 보호를 대신하지 않는다.
- secret은 DB 비밀번호와 별개의 무작위 32바이트 이상 값으로 만들고 준비 도구는 기존 파일을 덮어쓰지 않는다. 007의 교체는 호출 중단 → 파일 교체 → 회원 재기동 → 이전 값 거절/새 값 허용 확인으로 안내한다. 008부터 호출자 쪽도 함께 교체하며 무중단 교체는 이번 범위가 아니다.

## 변경 예정 파일과 작업 순서

| 순서 | 위치 | 변경 범위 |
|---|---|---|
| 1 | `settings.gradle`, `services/member-service/build.gradle` | 독립 MVC 모듈, Security/Session JDBC·JDBC/Flyway·Config/Eureka·테스트 의존성 |
| 2 | `services/member-service/src/main/` | 앱·회원 저장/인증·보안 필터·세션/CSRF·내부 확인·006 관측 기준·migration |
| 3 | `config-repo/member*.yml`, 회원 최초 YAML | 일반 설정·local/docker·DB/probes·세션/쿠키. secret은 별도 주입 |
| 4 | 회원 Dockerfile·`compose.yml`, secret 준비 도구 | 회원 이미지·여섯 서비스 누적 기동·loopback 8081·회원 DB/내부 자격 증명만 전달 |
| 5 | 회원 테스트·`scripts/check-member-auth.py` 등 | 격리 DB·두 인스턴스·실제 HTTP·장애/복구·비밀값 미노출 |
| 6 | 발견/DB 검사·`.github/workflows/ci.yml`·문서 | 기존 기대값을 유지하며 회원 검사 확장·수동 절차·결과/학습/인계 |

기존 DB 생성 스크립트를 다시 실행하거나 일반 데이터 볼륨을 삭제하지 않는다. 회원 migration은 기존에 비어 있는 member DB에 적용한다. 테스트에서만 생성한 격리 자원은 테스트가 정리한다.

## 핵심 코드 직접 작성: 6개 Step

| Step | 예정 구간 | 학습 목적·검증 |
|---|---|---|
| 01 | 회원 조회·비밀번호 인증 처리 | 해시 검증·일관된 실패·비활성 계정 거절 |
| 02 | 로그인 성공의 세션 전략·컨텍스트 저장 연결 | 이전 세션 무효화·CSRF 초기화·다음 요청/다른 인스턴스에서 인증 유지 |
| 03 | 현재 회원·역할 확인 | 세션 생성 시점의 역할을 계속 신뢰하지 않음 |
| 04 | 내부 호출자 검증 | 잘못된 호출자는 세션/회원 조회 전에 거절 |
| 05 | 원본 메서드에 따른 CSRF 확인 | 조회 허용·변경 토큰 검증·마스킹 규칙 일치 |
| 06 | 세션 처리 완료 후 응답 전송·실패 변환 | 로그인/로그아웃의 DB 저장·삭제 실패에서 성공 본문/쿠키 전송 방지 |

실제 메서드 경계는 기준 구현 후 정한다. 프레임워크의 세션·로그아웃 구현을 학습 목적으로 중복 작성하지 않고 전략/핸들러 연결 부분을 선택한다. 검증된 기준 구현을 같은 파일의 참고 주석과 입력 위치로 6개 동시에 제공하고, 사용자 작성 대기 상태를 완료로 표시하지 않는다. 기존 006 학습 구간은 다시 비우지 않는다.

## 검증과 완료 기준

| 범위 | 필수 확인 |
|---|---|
| 회원/비밀번호 | 정상·없는 ID·오답·비활성·잘못된 입력, 해시만 저장, fixture 역할/ID 제약 |
| 세션 | A 로그인 → B 조회/내부 확인 → B 로그아웃 → A 거절. 두 인스턴스 재시작 후 유지, ID 회전·이전 ID 거절, 짧은 테스트 만료·정리 전 거절 |
| CSRF | 로그인/로그아웃/내부 변경 확인의 누락·오답·타 세션 토큰 거절, A 발급 토큰의 B 검증, 로그인 전 토큰 폐기·로그인 후 갱신, 조회의 불필요한 강제 없음 |
| 현재 권한 | 기존 세션 유지 중 DB 역할/상태 변경 → 다음 확인에 반영, 임의 신원 헤더로 인증 불가 |
| 내부 경계 | 자격 증명 누락/오답/중복 403 및 세션/회원 SQL 0회, DB 장애에서도 같은 사전 거절, 안전/변경/잘못된 메서드 분기, 무세션 확인에서 새 세션/토큰 생성 없음, Gateway 내부 경로 404 |
| 장애/복구 | 올바른 호출자/입력에서 DB 중단 시 503, 로그인 인증 후 최종 세션 저장·로그아웃 삭제/응답 커밋 전 실패 주입도 503·성공 본문/새 쿠키 없음, 동일 앱 복구, liveness 유지·readiness DB 연동 |
| 보안 오류/관측 | 공개/내부 사전 거절과 저장 실패의 실제 HTTP ProblemDetail·요청 ID·최종 상태 JSON 로그, 민감값·원본 경로/ID의 지표 라벨 유입 없음, Actuator 검사에 세션 생성/조회 없음 |
| DDD 구조 | domain에 HTTP/Security/JDBC 의존 없음, application이 구현체에 직접 의존하지 않음, 패키지 순환 없음. 재구성 전 세션 principal/쿠키 호환·현재 역할 조회·실패 응답 회귀, 6개 학습 구간/기존 작성 보존 |
| 실행/회귀 | 전체 build·기존 검사·다섯 앱 이미지/여섯 Compose healthy·회원 Eureka 주소, Windows 제품 JAR local 기동 |

MockMvc의 인증 사용자 주입만으로 세션 검증을 대신하지 않는다. PostgreSQL·실제 HTTP 로그인/쿠키로 확인하고 서로 다른 두 회원 애플리케이션 컨텍스트/프로세스를 사용한다. 기본 직렬화의 principal/토큰 호환성과 동시 요청의 세션 속성 저장 경합을 확인한다. 특히 이전 세션 조회를 일시 정지한 뒤 다른 인스턴스에서 로그인/로그아웃하고 이전 요청을 재개해도 이전 ID/인증이 되살아나지 않아야 한다. 진행 중 요청은 이미 얻은 인증을 사용할 수 있지만 이후 확인은 무효화 결과를 따라야 한다. [JDBC 공식 문서](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)의 스키마·속성 저장·정리 방식을 실제 BOM 버전에 대조한다.

결과 문서 `../007-member-session-authentication.md`에는 준비 사항·프로젝트 작업 디렉터리·복사 가능한 PowerShell 명령·fixture 준비·쿠키 jar/토큰 유지 요청을 제공한다. CSRF 발급 → 로그인 → 재발급 → 본인 조회 → 내부 확인 → 로그아웃 순서와 각 실패의 상태/본문·다른 인스턴스 확인·DB 복구·종료 방법을 포함한다. 현재 문서는 실행 전 계획이므로 이 절차를 실측 성공으로 표시하지 않는다.

구현 후 `Docs/explanations/member-session-authentication.md`에 세션/쿠키·SecurityContext·JDBC 공유·CSRF·현재 역할 조회의 기본 개요와 실제 코드 적용 예시를 작성한다. 기존 Git 제외 정책을 유지한다. CI는 이번 단계부터 회원 검사를 누적하며 PR 생성/push 뒤 완료 대기·반복 조회는 하지 않는다.

## 검토 후 착수

2026-11-05 사용자가 1회 검토를 반영한 추천안을 채택하고 구현을 요청했다. 기준 구현·필수 검증 뒤 같은 파일의 참고 주석과 직접 입력 위치로 6개 Step을 동시에 준비했다. 사용자 작성 완료 통보 후 여섯 본문 묶음 리뷰·현재 소스/제품 재검증을 마쳤고 임시 표식을 정리했다. 007 로컬 완료이며 결과 문서의 최종 근거를 따른다.
