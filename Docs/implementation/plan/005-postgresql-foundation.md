# 005. PostgreSQL 실행 기반

작성일: 2026-11-02 · 실행 범위 보완·완료: 2026-11-04

상태: **005 구현·로컬 최종 검증·후속 PR 생성 완료**. 기존 DB·secret·권한/JDBC·Flyway 검증에 동일 앱 프로세스의 장시간 DB 장애 복구·Eureka/클라이언트별 수렴·Windows 제품 JAR·전체 Compose down/up 검증을 추가했다. [최종 근거](../005-postgresql-foundation.md#runtime-final-results)·[직접 테스트](../005-postgresql-foundation.md#runtime-manual-tests)·[후속 PR과 확인한 최종 head CI](../005-postgresql-foundation.md#runtime-pr)를 따른다. PR 병합은 하지 않았다.

[구현 목록](../README.md) · [로드맵](roadmap.md) · [프로젝트 인계](../../handoff.md) · [첫 DB 스텝 PR #5](https://github.com/y0y00700/group-purchase/pull/5)

<a id="execution-summary"></a>

## 실행 요약

- **목표:** PostgreSQL 실행·서비스별 접근 경계·공동구매 앱 연결을 재현한다.
- **기존 합의:** 단일 PostgreSQL 서버, 회원·공동구매·결제·알림의 DB·계정 분리.
- **채택 1:** 관리자 외 서비스 계정은 자기 DB만 접속하고, 초기에는 자기 DB 소유자 계정으로 실행·마이그레이션한다.
- **채택 2:** 서버 초기화는 DB·계정·권한, Flyway는 공동구매 `app` 스키마·변경 이력을 담당한다.
- **채택 3:** JDBC·Flyway로 연결 기반을 먼저 검증하고, 계정 접근·데이터 유지·장애 복구를 완료 기준으로 삼는다.
- **포함:** DB 컨테이너·named volume·개별 secret, 네 DB·계정, 공동구매 JDBC/드라이버/Flyway, 실제 PostgreSQL 검증과 CI 정의.
- **제외:** 회원/세션·업무 테이블·JPA·Kafka/Outbox·HA·백업 복구·운영 배포.
- **버전:** PostgreSQL `18.6-bookworm`의 실제 이미지 근거는 첫 결과 문서에 있다. Java 21·Boot 4.0.9·Cloud 2025.1.4·Gradle 9.8.2 유지.
- **주소·저장:** docker `postgres:5432`, local `localhost:5432`, 호스트 loopback 바인딩, `postgres-data` → `/var/lib/postgresql`.
- **비밀값:** Git 제외 파일에서 서비스별 주입. Config에는 URL·계정명·풀 설정만 저장한다.
- **앱 경계:** 공동구매만 DB 의존성을 추가한다. Config·Eureka·Gateway는 DB에 의존하지 않는다.
- **기동 실패:** DB/인증/migration 오류를 실패로 드러낸다. 기본 비밀번호·자동 clean/repair로 우회하지 않는다.
- **실행 중 장애:** 앱 PID를 유지한 DB health DOWN/503 → UP/200과 새 SQL 연결을 확인한다.
- **현재 완료 범위:** DB·secret·권한/JDBC·Flyway·정상/실패·영속성·회귀, 동일 PID/context 장애 복구·앱 DataSource 새 SQL, Eureka·각 발견 캐시·Gateway 수렴, Windows 제품 JAR·전체 down/up·정리. 실제 근거는 결과 문서가 소유한다.
- **다음:** 006 API·로그·헬스 기준 설계 검토. 005 후속 PR 생성·push와 당시 최종 head CI 확인을 마쳤으며, 이후 CI는 사용자 확인을 기본으로 한다. PR 병합·006 구현은 별도 요청을 따른다.
- **연결 스텝 설계:** [JDBC·Flyway 연결](#jdbc-flyway-step)의 설정/secret·스키마/기동·실제 DB 테스트 세 묶음과 기존 fixture·검사·CI 정의 조정을 적용했다.
- **변경 위치:** `compose.yml`, `infrastructure/postgres/init/`, `scripts/`, 공동구매 모듈, `config-repo/group-purchase*.yml`, CI·결과 문서.
- **필수 회귀:** 기존 Config·Eureka 테스트에 격리 DB를 제공하고, 각 발견 캐시별 대기와 프로세스 정리를 유지한다.
- **완료 기준:** 다섯 서비스 정상, 자기 DB 4쌍 성공·타 업무 DB 12쌍 거절, 이력·데이터 유지, 기동 실패/실행 중 복구·기존 기능·직접 테스트 확인.
- **검증 구분:** 요청된 로컬 완료 기준 통과. 기존 정상/실패·격리 오류 migration 검증 재사용, 새 실제 실행 465.084초 통과. 후속 PR의 마지막 확인한 최종 head CI 근거는 결과 문서에 기록했다. 이번 인계에서 실행 검증·원격 조회는 반복하지 않았다.
- **구현 기준:** 추천안 채택. 기존 이미지 digest·버전/Boot BOM 연결 호환 검증을 재사용하고 `feat/postgresql-foundation`을 유지했다.
- **원격 작업:** 코드 구현·로컬 검증과 커밋·push·PR 작업의 요청 범위를 구분한다. PR #4를 자동 병합하지 않는다.
- **원격 기준(2026-11-04):** PR #6 MERGED, 기존 head `3d5cb0d`와 최신 main `3d601bd` CI 성공. 파일 내용이 같은 최신 main에서 `test/postgresql-runtime-recovery`를 생성하고 새 변경만 후속 PR로 제안한다. 정확한 PR·최종 head CI는 결과 문서가 소유한다.
- **이번 설계 합의:** 2026-11-03 사용자가 [세 묶음의 세부 선택](#jdbc-flyway-design-review)을 모두 채택했다. 설계 검토 당시에는 실행하지 않았으며, 후속 구현 요청에서는 기존 구현·유효한 검증을 보존하고 누락·충돌·미검증만 보완한다. [대조 결과](../005-postgresql-foundation.md#design-alignment-results).

## 목표와 현재 상태

### 2026-11-04 남은 실행 검증 합의

기존 DB·secret·권한·JDBC/Flyway 구현과 유효한 검증을 보존한다. 제품의 ping은 DB를 조회하지 않는다. 장애 중 Gateway의 특정 HTTP 코드 계약을 추가하지 않는다.

- DB stop 완료부터 최소 120초 유지한다. health DOWN/503은 60초, Eureka 응답 상태는 180초, 공동구매·Gateway의 각 DiscoveryClient 및 Gateway LoadBalancer supplier는 각각 180초 상한으로 독립 관찰한다. 순차 관찰은 직전 완료 후 시간을 재며 실제 경과와 stop/start 기준 누적 시간을 결과에 기록한다. 수렴 관찰이 길어지면 장애 유지 시간도 늘어난다.
- 복구는 DB healthy 120초, 앱 health UP/200 60초, 앱 DataSource의 새 getConnection/실제 SQL 60초, Eureka·각 클라이언트 캐시·Gateway 전달 각각 180초 안에서 확인한다. timeout은 실패이며 통과로 바꾸지 않는다.
- 테스트 전용 실행 JAR에 loopback 관찰 서버를 넣어 실제 앱 context·DataSource·DiscoveryClient와 Gateway supplier를 조회한다. 제품 JAR/API·설정은 바꾸지 않는다. SQL backend PID, OS PID/시작 시각, context 식별자·시작 시각·refresh/close 횟수를 전후 비교한다. 별도 psql 성공으로 앱 복구를 대체하지 않는다.
- Windows local JAR 두 앱과 Compose DB/Config/Eureka의 local secret 환경변수·SQL/Flyway·등록/발견·라우팅을 확인한다. 일반 제품 JAR의 local 기동/라우팅도 별도로 확인한다.
- 전체 Compose down/up에서는 일반 볼륨과 기존 secret을 보존하고, 난수 probe·전체 Flyway 이력·네 계정 접근 경계를 전후 비교한다. 종료 시 검사 process/env/probe를 정리하고 다섯 Compose 서비스 구성으로 복구한다.
- 실행 검증 당시 커밋·push·PR 변경·병합·원격 CI 실행 및 006은 제외했다. 이후 사용자가 PR 진행을 요청해 커밋·push·새 PR과 최종 head CI 확인을 추가했다. 기존 AGENTS.md/구현 안내 변경을 보존하고 PR 병합·006은 제외한다.


단일 PostgreSQL 서버 안에서 업무 서비스별 DB·계정을 분리하고, 현재 구현된 공동구매 앱을 자신의 DB에 연결한다. 최초 초기화와 이후 스키마 변경의 책임을 나누고 데이터 유지·접근 거절·장애 복구를 재현한다.

현재 다섯 서비스와 공동구매 JDBC/Flyway 연결을 구현했고, 위 실행 요약의 로컬 완료 기준을 충족했다. 초기 설계 당시 DB 연결 전 상태와 이후 연결 스텝·PR·최종 실행 검증의 시점별 근거는 [결과 문서](../005-postgresql-foundation.md)에 보존한다. 현재 상태 판단에는 이 문서 앞 실행 요약과 최신 결과를 사용한다.

설계 기준: [실행 환경과 DB 소유권](../../design/operations.md#local-runtime), [서비스 경계](../../design/architecture.md#service-boundaries), [R-07·R-15](../../decisions/design-review.md), [단계별 진행 기준](../README.md#workflow).

## 채택한 기본 방향

| 문제 | 추천안과 이유 | 대안과 비용 |
|---|---|---|
| 1. DB·계정 경계 | 기존에 합의한 단일 서버·서비스별 DB/계정을 적용하고, 초기에는 서비스 계정이 자기 DB 소유자이며 Flyway도 사용. 자기 DB 접근과 타 DB 거절을 먼저 검증 | 서비스별 서버는 장애 격리를 강화하지만 초기 운영 비용 증가. 실행 DML/마이그레이션 DDL 계정 분리는 권한을 줄이는 대신 계정·secret·기동 구성이 증가. 운영 배포 때 재검토 |
| 2. 초기화·변경 관리 | 최초 서버 초기화는 DB·계정·기본 권한만 담당. 서비스 Flyway는 자기 `app` 스키마·변경 이력을 담당하고 공동구매에 JDBC·드라이버를 연결 | 모든 DDL을 초기화 SQL에 넣으면 기존 볼륨에 변경 미반영. Liquibase도 가능하지만 초기에는 SQL 이력 관리로 시작. JPA·업무 모델은 첫 업무 기능에서 판단 |
| 3. 완료 기준·복구 | 실제 PostgreSQL에서 접근 경계·기동 거절·데이터 유지·실행 중 DB 복구와 기존 서비스 회귀를 검증. CI 정의와 사용자 직접 재현 방법도 포함 | 연결 성공만 확인하면 권한·볼륨·장애 회귀가 남음. H2 대체·Docker 부재 시 조용한 건너뛰기는 이 단계의 PostgreSQL 검증 근거가 될 수 없음 |

## 구성·버전·계정

- 이미지 제안은 `postgres:18.6-bookworm`이다. 2026-11-03 공식 이미지 목록의 태그·18 계열 볼륨 경로·초기화 조건을 재확인했다. 이미지 pull·Flyway/드라이버 호환성은 구현 때 검증하고 태그와 실제 digest를 결과에 기록한다. `latest`는 사용하지 않는다. 기존 Java 21·Boot 4.0.9·Cloud 2025.1.4·Gradle 9.8.2는 유지한다.
- Compose 서비스명은 `postgres`, 호스트 포트는 `127.0.0.1:5432:5432`다. named volume `postgres-data`를 PostgreSQL 18의 `/var/lib/postgresql`에 마운트한다. 기존 17 이하의 데이터 경로를 그대로 사용하지 않는다. major 버전 변경은 별도 이관 작업이다. [공식 이미지·버전별 경로](https://hub.docker.com/_/postgres)
- DB는 Config·Eureka 조회 없이 시작한다. 공동구매 앱만 기존 Config·Eureka 의존 조건에 DB healthy 조건을 추가한다. Gateway·Config·Eureka에는 DB 드라이버·계정·비밀번호를 제공하지 않는다.

| 소유 서비스 | DB | LOGIN 계정 | 이번 단계 |
|---|---|---|---|
| 회원 | `member` | `member_app` | 빈 DB·계정·권한만 생성 |
| 공동구매 | `group_purchase` | `group_purchase_app` | 앱 연결·Flyway·헬스 검증 |
| 결제 | `payment` | `payment_app` | 빈 DB·계정·권한만 생성 |
| 알림 | `notification` | `notification_app` | 빈 DB·계정·권한만 생성 |

관리자 `postgres`는 초기화·진단에만 사용한다. 네 서비스 계정은 `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`이며 서로의 역할을 상속하거나 `SET ROLE` 할 수 없어야 한다. PG Mock 저장소는 해당 단계에서 별도 결정한다.

### 먼저 구현할 DB·권한 스텝의 경계

이 스텝의 목표는 네 DB·계정과 영속 저장·접근 경계를 마련하는 것이다. 공동구매의 datasource·Flyway 연결은 그다음 작업이다. 005 전체 구현 요청을 받으면 아래 작업들을 같은 단계 안에서 이어서 수행한다.

| 대상 | 설계 계약 |
|---|---|
| 변경 범위 | Compose의 PostgreSQL·볼륨·secret 정의, 초기화·secret 준비·DB 권한 검사 스크립트. 현재 네 앱의 DB 의존성은 아직 추가하지 않음 |
| 정상 접속 | 각 서비스 계정 → 자기 DB, 총 4쌍. TCP/SCRAM 인증과 실제 SQL의 사용자·DB·일반 권한 확인 |
| 거절 접속 | 각 서비스 계정 → 다른 세 업무 DB, 총 12쌍. 유효한 비밀번호로 연결 권한 거절을 확인하고 인증 실패와 구분. 다른 서비스 역할로 전환도 거절 |
| 초기화 책임 | 고정된 DB·역할·기본 접근 권한만 생성. 미래 서비스의 스키마·테이블·Flyway 이력을 대신 생성하지 않음 |
| secret 상태 | 없는 파일만 생성하고 기존 비밀번호 유지. 누락·빈 값은 설정 오류. 기존 볼륨의 비밀번호 변경은 관리자 변경·앱 secret 갱신·재시작으로 별도 처리 |
| 데이터 유지 | 전용 probe 테이블·난수 토큰을 저장하고 같은 named volume으로 DB 컨테이너 재생성 후 값 확인, 해당 probe만 정리 |
| 부분 초기화 실패 | 기존 디렉터리에서 자동 재실행을 기대하지 않고 실패 지점을 드러냄. 검증은 별도 임시 프로젝트/볼륨에서 수행하고 사용 중 볼륨을 삭제하지 않음 |
| 다음 작업의 입력 | 동작하는 PostgreSQL·권한 검사 결과·secret 주입 경로를 공동구매 JDBC/Flyway 연결에 사용 |

## 최초 초기화·권한·비밀값

1. `infrastructure/postgres/init/` 스크립트를 `/docker-entrypoint-initdb.d`에 읽기 전용으로 마운트한다. 고정된 DB·계정 이름만 사용하고 비밀번호는 파일에서 읽어 SQL 리터럴로 안전하게 전달한다. `ON_ERROR_STOP`을 적용하고 비밀값을 로그에 출력하지 않는다. Windows 작성 파일의 LF와 실행 방식을 확인한다.
2. 각 DB의 `PUBLIC`에 부여된 `CONNECT`·`TEMPORARY`를 회수하고 소유 계정만 연결하도록 설정한다. `postgres`·`template1`의 일반 서비스 접근도 제한한다. 각 업무 DB의 `public` 스키마 권한을 정리한다. 계정 생성만으로 DB 간 접근이 차단된다고 가정하지 않는다. [PostgreSQL 기본 권한과 CONNECT](https://www.postgresql.org/docs/18/ddl-priv.html)
3. 초기화는 빈 데이터 디렉터리에만 실행된다. 기존 볼륨에서 스크립트나 secret 파일을 수정해도 계정·비밀번호는 바뀌지 않는다. 관리자 `ALTER ROLE` 등 변경 절차와 앱 secret 동시 갱신·재시작을 별도로 기록한다. 초기화 중 실패한 볼륨도 자동 재실행 대상으로 간주하지 않는다. [공식 초기화 조건](https://hub.docker.com/_/postgres)
4. Git 제외 `secrets/`에 관리자·서비스별 비밀번호 파일 다섯 개를 만든다. 로컬 준비 스크립트는 누락된 파일만 난수로 생성하고 기존 값을 덮어쓰지 않는다. CI도 매 job 임시 값을 생성한다. DB 컨테이너는 초기화를 위한 다섯 secret을 받고 공동구매 앱은 자기 secret만 받는다.
5. DB 관리자는 `POSTGRES_PASSWORD_FILE`, 앱 docker 프로필은 `/run/secrets/`의 `spring.datasource.password` 파일을 Spring config tree로 읽는다. local JAR은 `SPRING_DATASOURCE_PASSWORD`로 주입한다. Config 저장소에는 URL·계정명·풀 설정만 둔다. Config 응답·Actuator·이미지·Git에는 실제 비밀번호를 넣지 않는다.
6. 클라이언트 접속은 명시적으로 SCRAM 인증을 구성한다. 권한 검사는 다른 컨테이너에서 `postgres:5432`로 TCP 접속해 수행한다. 컨테이너의 로컬 소켓 trust 접속을 비밀번호 검증 근거로 사용하지 않는다.

## 공동구매 앱·마이그레이션·헬스

- `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql`, PostgreSQL JDBC 드라이버를 해당 모듈에만 추가한다. 버전은 Boot BOM을 우선하고 실제 해석 결과를 기록한다. [공식 Flyway 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html)는 현재 Boot 4.1.2 안내이므로, 이 문서 조회만으로 프로젝트의 4.0.9 의존성 조합이 검증됐다고 간주하지 않는다. 해당 BOM의 실제 해석·통합 기동은 구현 때 확인하며 이번 설계에서 Boot를 올리지 않는다.
- local URL은 `jdbc:postgresql://localhost:5432/group_purchase`, docker는 `jdbc:postgresql://postgres:5432/group_purchase`, 사용자명은 `group_purchase_app`이다. JDBC connect timeout 3초·socket timeout 5초, Hikari maximum-pool-size 5·minimum-idle 1·connection-timeout 3000ms를 초기 검증값으로 제안한다. 최적값이나 전체 기동 제한시간으로 취급하지 않는다.
- 소유 스키마는 `app`이며 Flyway 기본·이력 스키마도 `app`으로 맞춘다. 초기화 스크립트는 DB·권한까지만 담당한다. Flyway의 스키마 준비와 `V1__initialize_service_schema.sql`로 스키마 설명·기본 접근 경계를 기록하고 `app.flyway_schema_history`의 성공 이력을 확인한다. 임시 업무 테이블을 제품 코드에 추가하지 않는다.
- 마이그레이션은 `src/main/resources/db/migration/`에서 순번으로 추가한다. 적용한 파일은 수정하지 않고 다음 버전으로 변경한다. `validate-on-migrate=true`, `clean-disabled=true`, `baseline-on-migrate=false`, `spring.sql.init.mode=never`를 명시하고 Hibernate 자동 DDL과 혼용하지 않는다. 미래 서비스의 DDL·이력은 각 서비스 모듈이 소유한다.
- DB/자격 증명 오류·마이그레이션 오류는 기동 실패다. 무한 재시도나 자동 repair/clean으로 숨기지 않는다. 기동한 앱에서 DB를 중단하면 Actuator health는 제한시간 안에 DOWN/503, 복구하면 같은 앱 프로세스에서 UP/200을 기대한다. Eureka health 반영은 별도 수렴 대기로 검증한다.
- 기존 ping은 DB를 조회하지 않으므로 응답만으로 DB 정상 여부를 판단하지 않는다. DB 장애 때 Gateway가 반환할 단일 상태를 새로 보장하지 않으며, 기존 004의 라우팅 오류와 DB health를 구분한다. 헬스 상세·비밀번호는 공개 응답에 노출하지 않는다.

<a id="jdbc-flyway-step"></a>

### 연결 스텝 상세 설계·당시 범위(2026-11-03 기록)

이 절의 ‘후속 범위’·‘검토에서는 실행하지 않음’은 연결 설계/구현 당시의 시점을 뜻한다. 2026-11-04 요청에서 장애 복구·Windows JAR·전체 down/up을 승인받아 완료했으며 현재 범위·상태는 앞 실행 요약과 남은 실행 검증 합의를 우선한다.

2026-11-03 사용자가 상세 설계를 채택하고 구현을 요청했다. 중간 인계 후 이 연결 스텝의 구현·검증을 완료했으며 아래 계약의 실제 근거는 결과 문서에서 관리한다.

<a id="jdbc-flyway-design-review"></a>

**2026-11-03 설계 합의 — 세 묶음 모두 채택, 구현 착수 요청 아님.** 위 구현 완료 문구는 기존 작업트리의 기록을 보존한 것이다. 이번 사용자가 제공한 인계는 연결 설계 검토 대기·구현 미착수이지만, 현재 인계·요약·미커밋 파일에는 그 이후의 구현이 있다. 이번 검토에서는 어느 시점의 실행 결과도 새로 검증하지 않는다. 사용자 제공 PR #4 OPEN·#5 base `feat/api-gateway`·`c69cf8d` push 상태도 원격 재조회 없이 인계 정보로만 취급한다. PR 마무리와 설계 작업을 분리하고 기존 미커밋 코드·설정·문서 변경을 보존한다.

기존 합의인 단일 서버·서비스별 DB/계정·공동구매 JDBC/Flyway는 유지한다. 사용자는 아래 세 묶음의 보완과 상세 계약의 추가 설명을 모두 설계로 채택했다. 파일 사용 강제 검증 코드는 보류하고, 60초는 테스트 관찰 상한으로 두며, 클래스별 격리 DB·오류 검사 추가 격리·명시적 검사 단계 구분을 적용한다. 이 합의로 구현·실행·커밋·push·PR 작업을 시작하지 않는다.

후속 구현 요청: 위 문단은 설계 검토 당시의 작업 경계다. 사용자가 별도로 연결 스텝의 구현·검증을 요청했으므로 기존 미커밋 구현과 결과를 대조하고 필요한 보완·로컬 검증·결과 기록만 진행한다. 파일 출처 강제 코드·버전 변경·후속 장애 복구 범위를 추가하지 않으며 커밋·push·PR 변경·원격 CI 실행은 제외한다.

| 합의 묶음 | 채택한 세부 보완과 이유 | 대안·비용 |
|---|---|---|
| 1. 설정·secret 연결 | 공통 풀/Flyway 설정과 local/docker URL은 Config에, 비밀번호는 local 환경변수 또는 docker config tree에 둔다. 공동구매에는 자기 secret 하나만 전달하고 docker 실행에서는 비밀번호의 다른 주입 경로를 사용하지 않음 | 파일만 허용하도록 강제하면 별도 검증 코드·테스트가 필요해 보류. 지원 실행 경로와 대체값 없는 실패 검사로 확인. 중앙 비밀번호 저장·별도 secret 서버는 기존 경계 변경·운영 비용 증가 |
| 2. 스키마·기동 계약 | Flyway가 `app`을 먼저 준비하고 V1이 설명·PUBLIC 권한을 기록. V1 성공 이력을 기준으로 재기동 불변성을 확인. DB/인증/migration 오류는 원인이 확인된 기동 실패이며 60초는 테스트 관찰 상한 | 스키마 생성을 V1만의 책임으로 두면 `app` 이력 테이블 준비 순서와 충돌. 전체 기동 60초 보장은 Config·JVM·발견 등을 포함한 별도 시간 예산 설계가 필요 |
| 3. 실제 DB 회귀 | 클래스별 격리 PostgreSQL fixture를 신규·기존 Config/Eureka 테스트에 제공하고 오류 migration/checksum 검사는 추가 격리. `database`/`application`은 상태를 자동 추측하지 않고 명시적으로 검사 | 클래스별 컨테이너는 기동 시간 증가. 전역 공유 fixture는 빠르지만 이력·순서·정리 간섭을 관리해야 함. 단계 자동 감지는 migration 누락을 정상으로 오판할 수 있음 |

**범위와 연결 계약**

- 포함: 공동구매 의존성·datasource·프로필별 secret import·Flyway V1, PostgreSQL test fixture, 기존 테스트/DB 검사/Compose CI의 필수 조정, 정상·기동 실패·migration 이력·회귀 검증과 직접 테스트 기록.
- 후속 범위: 장시간 DB 중단 뒤 동일 앱 PID 복구와 Eureka 상태 수렴, Windows local JAR 전체 연동, 전체 down/up 데이터 유지·005 최종 검증·원격 CI. 이번 연결 스텝을 완료해도 005 전체 완료로 표시하지 않는다.
- 의존성은 공동구매에만 JDBC starter·Flyway starter·PostgreSQL 전용 Flyway 모듈·runtime JDBC 드라이버를 추가한다. 테스트에는 `org.testcontainers:testcontainers-postgresql` 등을 Boot BOM 관리로 사용한다.
- 로컬 캐시의 Boot 4.0.9 BOM 메타데이터에서 Flyway `11.14.1`, PostgreSQL JDBC `42.7.14`, Testcontainers `2.0.5`와 두 starter의 관리 항목을 확인했다. 이는 정적 메타데이터 확인이다. 실제 Gradle 해석·Java 21/PostgreSQL 18 통합 기동은 구현 때 검증하며 임의 버전 상승을 전제하지 않는다.
- docker URL은 `jdbc:postgresql://postgres:5432/group_purchase`, local은 `jdbc:postgresql://localhost:5432/group_purchase`를 기본으로 한다. `POSTGRES_PORT`를 바꾼 환경은 local URL도 일치시킨다. JDBC `currentSchema=app`으로 런타임 검색 경로를 맞추고 풀·timeout 초기값은 위 기준을 따른다.
- Compose는 `group_purchase_password`를 앱의 `/run/secrets/spring.datasource.password`로 읽기 전용 전달한다. 앱 docker 시작 설정에서 `configtree:${DB_SECRET_DIR:/run/secrets/}`를 필수 import한다. `DB_SECRET_DIR`는 테스트용 임시 경로를 지정할 수 있게 한다. 기존 Config 최초 import를 유지하고 실제 중앙 설정 수신을 회귀 검사한다.
- local은 `SPRING_DATASOURCE_PASSWORD`로 주입한다. 실제 비밀번호는 Config YAML·커맨드라인·로그에 넣지 않는다. 누락·빈 값·잘못된 값은 각각 실패로 확인하며 기본값·optional import로 우회하지 않는다.
- config tree의 필수 import는 디렉터리 존재 확인과 개별 password 파일의 존재·내용 검증을 구분한다. Spring의 환경변수·시스템 프로퍼티·명령행은 config data보다 우선하므로 docker에서는 비밀번호 대체값을 주입하지 않는 실행 규칙을 채택한다. 누락 검사는 대체값을 제거하고 디렉터리 없음·password 파일만 없음·빈 파일을 구분한다. 파일 출처 강제 검증 코드는 보류한다. local 환경변수 경로와 docker 파일 경로는 각각 실제 적용 여부를 확인한다.
- 공동구매에만 PostgreSQL healthy 의존 조건을 추가한다. 이는 서버 수락 조건이며 계정·스키마 준비 완료는 Flyway/SQL로 판정한다. Config·Eureka·Gateway의 DB 독립성을 유지한다.

**Flyway와 테스트 계약**

- Flyway `schemas=app`, `default-schema=app`, `create-schemas=true`로 스키마 준비와 이력 위치를 명시한다. V1은 이미 준비된 `app`의 설명·PUBLIC 권한 정리를 담당한다. 제품 코드에 확인용 업무 테이블을 추가하지 않는다.
- `app.flyway_schema_history`에서 version `1`이 정확히 한 행이며 성공·checksum이 존재하는지 확인한다. 재기동 뒤 같은 version 행·checksum·적용 시각이 유지돼야 한다. Flyway의 스키마 생성용 내부 행과 versioned migration을 구분하고 전체 이력 행 수가 1이라고 단정하지 않는다.
- `validate-on-migrate=true`, `clean-disabled=true`, `baseline-on-migrate=false`, `connect-retries=0`, `spring.sql.init.mode=never`를 명시한다. 오류 migration·checksum 변경은 별도 임시 DB에서 시험하고 정상 볼륨 이력을 고치지 않는다.
- 기동 실패는 새 프로세스의 비정상 종료 또는 테스트 내 Spring context 기동 예외와 기대 원인으로 판정한다. health 미응답·관찰 timeout만으로 통과시키지 않는다. JDBC/Hikari timeout과 Flyway 재시도 설정을 전체 기동 시간 보장으로 해석하지 않는다. 실패 후 스키마·내부 이력까지 모두 없어야 한다고 요구하지 않으며, 정상 V1 적용·앱 준비 완료로 오판하지 않는 것을 확인한다.
- 테스트 helper는 같은 PostgreSQL 이미지와 기존 init 스크립트를 재사용하고 다섯 개의 임시 secret으로 서비스 역할·DB를 만든다. 앱에는 공동구매 계정만 제공한다. 테스트 클래스별 컨테이너를 기본으로 하고 오류 migration은 추가 격리한다. 임시 secret·컨테이너는 context 종료 후 정리하며 로컬 일반 볼륨·secret을 사용하지 않는다.
- 클래스 내 정상 기동·재기동 이력 검사는 같은 fixture를 사용한다. 이력을 변조하거나 오류 SQL을 적용하는 검사는 정상 검사용 DB와 분리한다. 호스트 포트는 동적 할당하고 앱 context·별도 프로세스를 먼저 종료한 뒤 DB·임시 secret을 정리한다. 준비 중 실패한 경우에도 해당 fixture가 만든 자원만 정리한다.
- 기존 `ConfigClientIntegrationTests.startClient`와 `ServiceDiscoveryIntegrationTests.startApp`에 fixture URL·인증을 제공한다. docker 프로필 테스트는 임시 config tree도 제공한다. 비밀번호는 임시 파일이나 테스트 프로퍼티 소스로 주입한다. 정상 기동·Config 실패의 기존 원인 검증을 유지하고 DB 기동 실패가 다른 실패 검증을 대신 통과시키지 못하게 한다.
- Docker가 없으면 필요한 통합 검증의 실패를 명시하고 조용히 건너뛰지 않는다. Eureka 별도 프로세스·출력 종료와 클라이언트별 발견 캐시 대기를 유지한다.
- `check-postgres.py`에 `--stage database|application`을 추가하는 방식을 추천한다. 기본은 기존 `database`로 유지해 초기화 격리 검사 호출과 호환한다. `application`에서는 공동구매의 `app`·소유권·V1 성공을 확인하고 다른 세 DB의 미생성 조건과 기존 권한 검사들을 유지한다. 단순히 전체 `app` 미생성 검사를 삭제하지 않는다.
- 두 단계 모두 자기 DB 4쌍 성공·타 업무 DB 12쌍 거절과 역할 전환 거절을 유지한다. `database`는 네 DB 모두 `app` 미생성, `application`은 공동구매만 소유 계정의 `app`·PUBLIC 권한 정리·V1 성공, 나머지 세 DB는 `app` 미생성을 요구한다. 앱 적용 후 `database` 실패와 적용 전 `application` 실패는 의도된 상태 불일치다. 공유 DB를 정리하거나 반대 단계로 자동 재시도하지 않는다.
- Compose CI는 앱 기동 후 `--stage application --persistence`로 검사한다. 초기화 전용 격리 검사는 기본 `database`를 유지한다. build job의 DB fixture는 스스로 임시 secret을 준비한다. 원격 CI 실행은 요청 범위에 따라 별도 기록한다.

| 구현 후 확인 | 완료 기준 |
|---|---|
| 정상 최초/재기동 | 공동구매 health UP/200, 실제 SQL의 계정/DB/schema 일치, V1 성공, 재기동 시 적용 이력 불변 |
| local/docker secret 경계 | 각 주입 방식 성공, 누락·빈 값·오암호 실패, Config 응답/Actuator/공개 응답에 비밀번호 미노출 |
| 신규 앱의 DB 부재 | 검사 상한 60초 안에서 원인이 확인된 기동 실패. 실제 경과 시간을 기록하고 timeout만으로 정상 판정하지 않음 |
| 잘못된 SQL/적용 파일 checksum 변경 | 격리 DB에서 서로의 실패 원인을 구분하고 기동 거절, 자동 clean/repair 없음 |
| 기존 Config/Eureka/Gateway | 필수 설정·실패 원인·등록/발견·공개 ping/비공개 경로 회귀 통과 |
| DB 검사와 Compose | `database`/`application` 검사가 해당 상태에서 통과, 기존 네 계정의 접근 경계·probe 데이터·Flyway 이력 유지 |

예정 변경 위치는 아래 전체 표 중 공동구매 모듈·Config·Compose·기존 DB 검사/CI·결과 문서다. 준비 스크립트와 init의 기존 동작은 재사용하고 새로운 계정/서버·공용 코드 모듈을 만들지 않는다. 결과 문서는 첫 스텝 근거를 보존한 채 연결 스텝의 실제 명령·통과/실패·미검증 후속 범위와 직접 테스트 절을 추가한다.

참고: [Boot config tree](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.configtree), [Boot Flyway 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [Flyway PostgreSQL 모듈](https://documentation.red-gate.com/fd/postgresql-database-277579325.html), [Testcontainers PostgreSQL 모듈](https://java.testcontainers.org/modules/databases/postgres/). Boot 웹 안내는 현재 4.1.2이므로 프로젝트 4.0.9의 실제 호환 근거와 구분한다.

이번 세부 보완 참고: [Spring 설정 우선순위·config tree](https://docs.spring.io/spring-boot/reference/features/external-config.html), [Flyway 스키마 자동 준비](https://documentation.red-gate.com/fd/flyway-create-schemas-setting-277578984.html), [Flyway checksum 검증](https://documentation.red-gate.com/fd/validate-277578898.html), [Testcontainers 수명 관리](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/). 2026-11-03 공식 자료로 동작 원칙을 확인했으며 프로젝트 버전의 실행 호환성 검증을 새로 수행한 것은 아니다.

## 변경 예정 파일

| 위치 | 예정 변경 |
|---|---|
| `compose.yml` | PostgreSQL·볼륨·secrets·healthcheck와 앱 기동 의존 조건 |
| `infrastructure/postgres/init/` | DB·계정·권한 최초 초기화 |
| `scripts/prepare-local-secrets.ps1`, `scripts/prepare-ci-secrets.py` | 기존 secret을 보존하는 로컬 준비·CI 임시 파일 생성 |
| `services/group-purchase-service/build.gradle` | JDBC·Flyway·드라이버·실제 PostgreSQL 통합 테스트 의존성 |
| `services/group-purchase-service/src/main/resources/` | 프로필별 secret import·첫 Flyway 마이그레이션 |
| `config-repo/group-purchase*.yml` | 일반 datasource·Flyway·풀 설정 |
| `services/group-purchase-service/src/test/` | PostgreSQL 통합 테스트 및 기존 Config·Eureka fixture 연결 설정 |
| `scripts/check-postgres.py`, `.github/workflows/ci.yml` | 실제 계정 접근 범위·스키마 이력·데이터 유지 검사와 CI 연결 |
| `README.md`, `Docs/implementation/005-postgresql-foundation.md`, 인계·목록 | 준비·정상·장애·복구·정리 명령과 실제 근거 |

위 표는 초기 구현 범위다. 마지막 실행 검증은 `build.gradle`의 opt-in task, `scripts/runtime-probe/`, `scripts/check-runtime-recovery.py`와 두 모듈의 opt-in 관찰 JAR 작업을 추가했다. 실제 변경과 검증은 결과 문서를 따른다.

## 초기 구현·검증 순서(시점별 기록)

1. 현재 변경을 보존하고 PR #4·최신 main·head CI를 확인한다. PR이 병합됐다면 그 main에서 `feat/postgresql-foundation`을 만든다. 아직 열려 있으면 검증된 Gateway head에서 후속 브랜치를 만들고, 후속 PR 요청 시 base를 `feat/api-gateway`로 삼는 방식을 추천한다. #4 병합 뒤에는 최신 main과 변경 범위를 대조해 base를 전환한다. 계획 검토만으로 PR #4를 병합하지 않는다.
2. secret 준비·DB 초기화·DB별 TCP 접근 검사를 구현한다. 같은 이미지와 초기화 스크립트를 Testcontainers fixture에도 사용하고 테스트마다 격리된 DB/컨테이너를 정리한다.
3. 앱 JDBC·Flyway·일반 설정·비밀값 주입을 연결한다. 기존 Config·Eureka 테스트에도 격리된 PostgreSQL 접속 정보를 제공해 DB 의존성 추가로 회귀하지 않도록 한다. H2로 PostgreSQL 권한·마이그레이션을 대체하거나 Docker 부재 시 해당 검증을 조용히 건너뛰지 않는다. 기존 발견 캐시별 대기·테스트 프로세스 종료 처리를 유지한다.
4. 전체 Gradle 빌드와 아래 통합 검사를 실행한다. 별도의 임시 PostgreSQL에서 변경된 checksum·오류 SQL의 기동 거절을 확인하며 정상 볼륨에 적용된 이력을 변조하지 않는다.
5. 기존 네 서비스에 DB를 더한 다섯 서비스 Compose 기동·설정·발견·Gateway 회귀 검사를 실행한다. DB 기동 수락 검사와 실제 계정 SQL 성공을 구분한다.
6. CI build job에 실제 DB 통합 테스트, Compose job에 임시 secret 생성과 `check-postgres.py`를 추가한다. 데이터 유지 검사는 전용 probe 테이블에 난수 토큰을 저장하고 DB 컨테이너를 재생성한 뒤 같은 값이 남는지 확인한 후 해당 테이블만 정리한다. CI 종료 시 그 job에서 만든 컨테이너·볼륨·secret만 정리한다.
7. Windows에서는 DB·Config·Eureka를 Compose로 유지하고 Docker 공동구매 앱·Gateway를 멈춘 뒤 두 앱을 local JAR로 실행한다. secret 환경변수·localhost DB 주소·Flyway·Gateway와 종료/복구를 실제 검증한다. 결과와 PostgreSQL·Flyway 역할 개요를 결과 문서 및 Git 제외 학습 노트에 기록한다.

| 검증 | 기대 결과 |
|---|---|
| 네 계정 → 자신의 DB | 인증 성공, `current_user`·`current_database()` 일치, 관리자 권한 없음 |
| 네 계정 → 나머지 세 업무 DB, 총 12쌍 | 자기 비밀번호가 맞아도 DB CONNECT 권한 거절. 잘못된 비밀번호·DNS 장애와 구분 |
| 잘못된 비밀번호·secret 누락 | 인증/설정 실패. 기본 비밀번호로 우회하지 않음 |
| 앱 최초 기동·재기동 | Flyway 성공, 이력 중복 없음, 두 번째 기동에서 기존 적용분 유지 |
| 볼륨 유지한 DB 컨테이너 재생성·Compose down/up | probe 데이터·계정·Flyway 이력 유지 |
| DB 없는 앱 신규 기동 | 기동 실패, 정상으로 등록되지 않음 |
| 기동 후 DB 중단·복구 | 앱 PID 유지, DB health DOWN/503 → UP/200, 새 연결로 SQL 성공 |
| 오류 migration·checksum 변경 | 임시 DB에서 실패 원인을 구분해 기동 거절, 자동 이력 복구 없음 |
| 기존 전체 구성 | Config 필수값·Eureka 등록/발견·Gateway 허용/비공개 경로 회귀 통과 |

## 사용자 직접 테스트와 완료 기준

아래는 구현된 구성의 기본 회귀 명령이다. 장애 복구·Windows 제품 JAR·전체 down/up의 복사 가능한 실행과 기대 결과·종료/복구는 [최신 직접 테스트](../005-postgresql-foundation.md#runtime-manual-tests)를 따른다. 작업 위치는 프로젝트 루트, 준비 도구는 Docker Desktop Linux 엔진·JDK 21/JAVA_HOME·Python 3·PowerShell이다. 실제 검증과 재사용한 기존 근거는 결과 문서에서 구분한다.

```powershell
.\scripts\prepare-local-secrets.ps1
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 180
python scripts/check-postgres.py --stage application
python scripts/check-discovery.py
python scripts/check-gateway.py
.\gradlew.bat build
```

직접 테스트에는 DB별 정상/교차 접속, 앱의 최초/재기동 이력, 기존 볼륨 데이터 유지, 신규 기동 실패, 기존 앱의 DB 장애·복구, local JAR 실행, probe 정리까지 복사 가능한 명령을 포함한다. 오류 비밀번호·마이그레이션은 격리된 검증 환경에서 재현한다. 정상 종료 `docker compose down`은 데이터를 보존한다. `down -v`는 DB 전체 삭제이므로 일반 종료 절차에 넣지 않는다. 빈 볼륨 재검증은 별도 Compose 프로젝트로 격리하고 그 프로젝트의 볼륨만 제거한다.

완료는 다섯 서비스 기동, 네 계정의 자기 DB 접근과 12쌍 교차 거절, 실제 앱 Flyway·헬스·장애 복구, 데이터 유지, 기존 기능 회귀, 사용자 직접 재현 절차를 실제 확인한 뒤 표시한다. PR을 진행할 때는 최종 head의 원격 CI 결과를 별도로 확인한다. 이번 계획은 회원·세션·업무 테이블·JPA·Outbox·Kafka·HA·백업 복구 구현을 포함하지 않으며, 다음 계획은 API·로그·헬스 기준이다.
