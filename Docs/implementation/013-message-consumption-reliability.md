# 013. 메시지 소비 안정성 구현 결과

2026-11-26 KST · **013 로컬 구현·검증 완료**. 사용자가 이번 단계의 직접 작성을 생략하고 주석 정리·커밋·PR을 요청해, 검증된 여섯 본문을 활성 코드로 복원했다. 사용자 직접 작성 완료로 기록하지 않는다.

[합의한 계획](plan/013-message-consumption-reliability.md#execution-summary) · [적용 설명](../explanations/message-consumption-reliability.md) · [짧은 인계](../handoff.md)

## 출발 기준과 보존

- PR #13은 착수 조회 때 open/unmerged였다. head `0e6ccbd1a53e12022bdd723e8a3891b31ecaa8a4`의 [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/45624698724), fetch한 최신 main `85f7be78a4c2391354c55489dc62cba30fc865e4`의 [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/45431750385)을 확인했다.
- 012 코드에 의존하므로 해당 head에서 로컬 `feat/message-consumption-reliability`를 만들었다. 최초 요청은 로컬 구현까지였으며, 이후 사용자가 직접 작성 생략·주석 정리·커밋·push·PR 생성을 요청했다. 병합은 요청하지 않았다.
- 기존 미커밋 `Docs/README.md`, `Docs/handoff.md`, 구현 목록·로드맵·013 계획을 이어서 갱신했다. 변경 전 patch와 013 원문은 `.tmp/013-baseline/`에 보존했다. `Docs/learning/`, `Docs/explanations/`의 기존 Git 제외 정책을 유지했다.

## 실제 변경과 경계

V5는 소비 완료, 파티션 진행/보류, 실패 원본, 시도 이력의 네 테이블을 추가한다. V1~V4는 변경하지 않는다. 원본 key/value는 null·빈 값과 길이를 구분한 SHA-256 지문을 사용하고, headers는 중복 이름·순서·nullable 바이너리를 보존한다.

`JdbcInbox.register`는 역할+메시지 ID의 명시적 충돌 대상만 중복으로 다룬다. 같은 ID·다른 원본과 다른 SQL 오류를 성공으로 숨기지 않는다. `InboxProcessor`는 파티션 행 잠금과 owner token을 확인한 업무 트랜잭션에서 소비 기록·업무·후속 Outbox·HANDLE 성공·완료 위치를 함께 커밋한다. 서로 다른 ID의 같은 업무는 검증 핸들러의 업무 식별자 잠금·상태 전이로 따로 막았다. 실제 참여 서비스와 후속 알림 Outbox를 호출하는 조합도 test에만 둔다.

소비 루프의 한 스레드가 poll·pause·seek·명시적 commit·close를 소유한다. DB 성공 뒤 현재 할당의 안전한 prefix만 커밋하고, 타임아웃이면 DB 업무를 즉시 다시 호출하지 않는다. 실패 뒤 같은 poll에 포함된 후속 건을 건너뛰며, 재전달은 완료 기록을 대조한다. lost 콜백은 로컬 진행만 버린다. 다른 스레드는 종료 요청의 wakeup만 호출한다.

최초 포함 5회 예산·시도 시작·다음 실행 시각을 DB에 남긴다. 중단된 HANDLE은 완료 기록 확인과 함께 INTERRUPTED로 정리하며, 명확한 DB 공통 장애 시도는 예산에서 제외한다. DB 장애 때 해당 인스턴스의 모든 할당을 pause하고, 복구 뒤 각 파티션의 token·HALTED/OFFSET_GAP·재시도 기한을 다시 읽는다.

영구 오류·예산 소진은 **실패 원본+파티션 HALTED를 DB에 먼저 함께 확정**한다. DLT 작업자는 60초 선점 뒤 DB 트랜잭션 밖에서 Kafka에 보내고, 현재 선점 ID·유효 기한으로만 결과를 반영한다. DLT 성공과 실패는 원본 `next_offset`·파티션 보류를 변경하지 않는다. 4MiB에서 key/배치 여유 1KiB를 뺀 전송 한도를 초과하면 원본을 자르지 않고 DLT_BLOCKED로 남긴다. 시도 본문은 최근 20개와 집계로 제한하며 전체 이력은 DB에 유지한다.

할당 준비와 30초 주기 검사에서 topic ID·파티션 수·보존 시작/끝·Kafka 커밋·DB 완료 위치를 대조한다. 새 초기화는 명시적 from-origin, B=0, K 없음, 기대 토픽 ID/파티션 수 일치가 필요하다. 기본은 disabled다. K가 없거나 보존 시작보다 작아도 DB prefix가 유효하면 D까지만 복원한다. K>D·D<B·D>E·토픽 재생성·역할 이관 불일치는 OFFSET_GAP으로 남기며 자동 정정하지 않는다. 자연스러운 offset 숫자 공백은 손실로 단정하지 않는다.

제품 `app.inbox.enabled=false`를 유지했다. 실제 승인·환불 결과 핸들러 두 종류가 없는 활성화는 기동 실패다. 011 payload 표본과 probe 상태 모델은 제품 결제 계약이 아니다. 실제 결제 결과/Saga는 016, 운영 권한·재처리·보류 해제 API는 018, 운영 알림 전달은 019 범위다. 외부 HTTP 처리·인증 경계는 변경하지 않았다.

제품 진단은 `/actuator/info`의 `inbox.enabled/status`, 구조화 로그와 `inbox.processed`, `inbox.failures`, `inbox.retries`, `inbox.held.partitions`, `inbox.dlt.pending`, `inbox.dlt.oldest.seconds`, `inbox.commit.failures`, `inbox.poll.interval`, `inbox.offset.gaps`다. 지표 태그에 ID·offset을 넣지 않는다. 기존 DB readiness 기준을 유지한다.

## 기준 구현 검증 근거

긴 실행 로그는 Git 제외 `.tmp/013-baseline/`과 `.tmp/inbox/<runId>/`가 소유한다. 전체 build와 이후 변경에 따른 관련 검증을 구분한다. 검증 대상은 커밋되지 않은 기준 소스와 해당 JAR이며, 후속 사용자 작성 대기 소스와 동일하게 취급하지 않는다.

| 구분 | 실제 근거 |
|---|---|
| 전체 Gradle build | `.tmp/013-baseline/full-build.log`, 유효 136개·실패/오류/skip 0. 공동구매 93개 새 실행, 변경 없는 다른 모듈 43개 재사용 |
| 소비 기반 통합·정책 | `InboxIntegrationTests` 20개 + `ConsumptionFailurePolicyTests` 2개. 원자성·동시 중복·업무 ID 전이·충돌·원본 보관·5회 예산·실제 COMMIT 응답 소실·실제 두 소비자·보존 경계/토픽 재생성·실제 JVM 강제 종료 포함 |
| 최종 관련 재검증 | `.tmp/013-baseline/final-inbox-tests.log`: 마지막 커밋 인증 오류/lost 정리·실행 결과 타입 보강 뒤 관련 22개와 `verifyKafkaIsolation` 통과. 변경 없는 기존 114개는 전체 build 근거를 재사용 |
| Kafka 커밋 확인 불명 | 실제 브로커의 commitSync 성공 직후 클라이언트 반환을 타임아웃으로 바꾸는 주입과 MockConsumer 타임아웃/lost 검증. Kafka wire 응답을 네트워크 프록시로 잘랐다고 주장하지 않음 |
| DB COMMIT 확인 불명 | 기존 PostgreSQL wire proxy로 서버의 실제 COMMIT 완료 응답을 유실시켜 완료 기록·업무·Outbox 각 1회와 DLT 오판 방지를 확인 |
| 운영 복구 경계 | test 전용 드라이버에서 같은 원본의 재처리 성공 전 후속 차단, 성공 후 재개 확인. 제품 운영 복구 API의 완료 근거가 아님 |
| 이미지·기본 비활성 | 다섯 제품 이미지 빌드 후 최종 공동구매/의존 이미지 갱신. 7 healthy·kafka-init exit 0, `/actuator/info`에서 제품 DISABLED. Docker/Windows 제품 JAR의 SHA-256 동일 |
| 기존 Kafka·Outbox | `kafka-regression.log`의 22개 검사와 `.tmp/kafka/b406ea65c7054c40a87a35f23a76b1be/` 통과. DLT 4MiB 설정도 대조. `outbox-regression.log`·`.tmp/outbox/731b473d0ee14e4d9392f6eae6dcabaa/`의 제품 INTERNAL 발행→HOST 원본 수신 통과 |
| 기존 HTTP·Windows 제품 | `http-regression.log`: Compose Gateway 8000과 Windows Gateway/공동구매/회원 제품 JAR 18000/18080/18081에서 게시·참여·취소·재전달·현재 역할·호출자 신뢰 검사 통과 |
| 최종 소비 수명 검사 | `.tmp/inbox/97793824574b4187a4abed677fd0bdc6/manifest.json`의 28개 exit 0·`INBOX_CHECKS_OK 28`. HOST/INTERNAL, 같은 프로세스의 DB/브로커 복구, 두 실제 소비자 가입/이탈, 전체 down/up 뒤 진행·HALTED 유지. `final-inbox-lifecycle.log` |
| 직접 작성 생략 후 활성 코드 | `pr-reexecuted-tests.log`: 사용자 요청으로 여섯 본문 복원·중복 참고/흐름 주석·표식/UOE 제거 후 관련 22개를 캐시 없이 새 실행, 실패/오류/skip 0. JAR 격리 통과 |
| 종료·보존 | 검증 토픽 네 개·그룹 두 개 정리 성공. `shutdown.log`에서 전용 013 컨테이너/네트워크 down, `group-purchase-013_postgres-data`·`group-purchase-013_kafka-data` 보존. 011/012·일반 프로젝트를 중단/삭제하지 않음 |

기준 산출물은 `.tmp/013-baseline/jars/`, 핵심 소스는 `reference-source/`, 원문·SHA-256은 `reference-manifest.json`, 전체/최종 XML은 `full-test-results/`, 실제 자식 JVM 중단 로그는 `process-boundaries/`에 보존했다. 원격 CI는 이번 요청에서 실행하지 않았다.

| 기준 JAR | SHA-256 |
|---|---|
| Windows 제품 = Docker `/app/app.jar` | `b4c8ac34b62260f7ade980341fe80db6076339e15d23d481c3f703488b351568` |
| inbox probe | `c650c3f8ada3067b19fef8f497307a25df5c7d7d9519ae20519603d8b0135a53` |

주요 검증 중 보정: 새 할당에서 reset=none의 커밋 부재 예외가 pause 뒤에도 발생하므로 DB 준비 경로를 계속 진행하도록 했다. DB 커밋 뒤 종료한 프로세스의 재시작 probe는 새 record를 기다리지 않고 증명된 D를 복원한다. 첫 Compose 수명 검사는 down/up 후 kafka-init 종료 직전의 상태를 읽어 도구의 판정이 실패했다. 초기화 컨테이너 종료 코드를 기다리도록 고친 뒤 최종 수명 28개를 새로 실행해 모두 통과했다.

<a id="guided-coding"></a>

## 직접 작성 생략과 정리

2026-11-26 사용자가 “오늘은 직접 작성쪽 주석 제거 바로 하고 커밋 &PR 올려”라고 요청했다. 이번 013은 직접 작성을 생략한다. 사용자 작성 코드는 없었고, 비워 둔 여섯 본문을 검증된 원문으로 복원한 뒤 중복 참고 주석·호출 흐름 주석·LEARN 표식·작성 대기 예외를 제거했다. 일반적인 직접 작성 규칙은 변경하지 않았다.

복원 대상은 `JdbcInbox.register/restorePartition`, `InboxProcessor.execute`, `InboxConsumerLoop.commitCompleted`, `ConsumptionFailurePolicy.classify`, `DltPublisher.publishOne`이다. 다섯 파일의 전체 내용이 기준 소스와 일치한다. 제품·inbox probe·Outbox probe JAR의 SHA-256도 기준과 같다. 관련 22개를 `test --rerun`으로 새 실행하고 JAR 격리 검사를 통과했다. 이미 유효한 기존 114개·Compose/Windows HTTP·Kafka·Outbox·수명 28개는 동일 실행 코드/JAR 근거로 재사용했다.

<a id="final-results"></a>

## 최종 결과와 PR 준비

- **013 로컬 구현·필수 검증·임시 표식 정리 완료**. 사용자 직접 작성은 명시적 요청으로 생략했으며 작성 완료로 표시하지 않는다.
- PR 준비 최신 확인에서 [PR #13](https://github.com/y0y00700/group-purchase/pull/13) 병합과 main `356bcd5005ac984327abd98881dcc4aa7d03f435`의 [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/45837640728)을 확인했다. 기존 PR head CI도 성공이다.
- main의 코드 트리가 기존 기준 head와 같음을 확인하고 `feat/message-consumption-reliability`를 main으로 fast-forward했다. 미커밋 설계·구현·제외 학습 자료를 보존했다.
- 구현 커밋 `24853ed`를 push하고 main 대상 [PR #14](https://github.com/y0y00700/group-purchase/pull/14)를 생성했다. 최종 head는 [PR commits](https://github.com/y0y00700/group-purchase/pull/14/commits)를 따른다. 인계 문서의 PR 기록을 추가 push한 뒤 [Checks](https://github.com/y0y00700/group-purchase/pull/14/checks)를 한 번 확인해 당시 상태만 채팅에 안내한다. 이 문서는 조회 전 작성했으므로 013 원격 CI 성공을 주장하지 않는다. 완료를 기다리거나 반복 조회·병합하지 않는다.
- 제품 소비 기본 비활성·실제 핸들러 없는 활성화의 기동 실패, 실제 결제/Saga 016·운영 복구 API 018 경계를 유지한다. 원격 CI 조회 당시 상태와 최종 PR/head/Checks는 채팅 및 짧은 인계에서 안내한다.

<a id="ci-member-route-recovery"></a>

## 2026-11-27 PR #14 CI 실패 확인·보정

[실패한 CI](https://github.com/y0y00700/group-purchase/actions/runs/45839840971/job/139978293040)의 대상 head는 `5b8e8dcb7b7b1d50f846dc3d16b1f1c9aceac585`다. Build and test는 성공했고, Compose의 Kafka·Outbox·Inbox 검사도 성공했다. 실패 지점은 `check-member-auth.py`의 Gateway 익명 회원 조회를 즉시 401로 기대한 검사다. 이후 공동구매 인증·관측 검사는 실행되지 않았다.

직전 `check-postgres.py --persistence`가 PostgreSQL 컨테이너를 재생성했다. 로그에서 회원 서비스는 14:38:22 UTC에 DOWN, 14:38:55에 UP으로 등록됐지만 Gateway는 14:39:10에 `No servers available for service: member`와 `member-me` 503을 기록했다. 회원 직접 호출과 두 제품 JAR의 세션 검사는 이미 통과했다. DB·직접 서비스·Eureka UP·Gateway가 사용하는 대상 목록은 복구 확인 지점이 서로 다르다. 기존 discovery 검사는 공동구매 등록만, ping 검사는 공동구매 라우트만 확인하므로 회원 라우트의 복구를 증명하지 못한다.

`check-member-auth.py`에 `wait_for_gateway_member`를 추가했다. 부작용 없는 익명 `GET /api/members/me`를 호출해 최대 180초 동안 실제 Gateway 회원 라우트의 복구를 확인한다. 일시적인 502/503/504·연결 오류만 재확인하고, 401과 `UNAUTHENTICATED`를 확인한 뒤 기존 로그인·세션 공유·CSRF·로그아웃 검사를 그대로 실행한다. 예상하지 않은 상태, 잘못된 401 코드, 오류 응답 계약 위반, 제한시간 초과는 실패다. 임의의 고정 sleep으로 성공을 가정하거나 503을 성공으로 허용하지 않는다. 제품 코드·인증 정책·발견 캐시 설정·013 소비 기반은 변경하지 않았다.

이번 수정의 실행 근거는 Git 제외 `.tmp/013-ci-recovery/`에 남겼다. 아래 검사는 2026-11-27 수정한 스크립트와 기존 013 제품 이미지/JAR에서 실제 실행했다. 기존 전체 Gradle·Inbox 검증은 실패한 CI head에서도 성공한 근거를 재사용하며, 제품 소스·JAR를 바꾸지 않은 이번 Python 검사 변경으로 전체 빌드를 반복하지 않았다.

| 검사 | 실제 결과·진단 |
|---|---|
| 복구 확인 분기 여섯 개 | 즉시 정상, 502→503→504→401 복구, 연결 오류 후 복구, 제한시간 소진, 예상 밖 200, 잘못된 401 코드의 통과/실패를 확인. `readiness-cases.log` |
| 실제 DB 장애·발견 지연 재현 | 전용 DB를 중단해 실제 Eureka MEMBER DOWN·Gateway 503 SERVICE_UNAVAILABLE까지 확인한 뒤 PostgreSQL을 재생성했다. 직접 회원 헬스가 UP이어도 Gateway 503인 구간을 재현했고, 수정한 확인이 앱 재시작 없이 **65.9초 후 401**을 확인했다. `db-route-reproduction-final.log` |
| CI 순서의 DB·발견·라우팅 | `check-postgres.py --stage application --persistence`, `check-discovery.py`, `check-gateway.py` exit 0. `postgres-persistence.log`, `discovery.log`, `gateway-routing.log` |
| 실패했던 회원 인증 | `check-member-auth.py` exit 0. Compose 회원·두 Windows 제품 JAR·Gateway의 세션 회전/공유·CSRF·로그아웃, 로그 개인정보 검사와 회원 등록·내부 경로 차단 통과. `member-auth.log` |
| CI에서 건너뛴 후속 검사 | `check-gateway-auth.py`, `check-api-observability.py --db-recovery` exit 0. 공동구매/참여·호출자 신뢰·오류/로그/지표·동일 앱의 DB 복구 통과. `gateway-auth.log`, `observability.log` |

전용 `group-purchase-013`의 컨테이너·네트워크는 검증 뒤 down으로 종료했고 데이터 볼륨을 보존했다(`shutdown.log`). 다른 프로젝트·일반 DB·토픽은 정리하지 않았다. 원격의 새 CI 결과는 수정 커밋 push 후 한 번 조회한 당시 상태만 안내하며, 위 로컬 성공을 새 CI 성공으로 표시하지 않는다.

### 사용자 직접 재현

아래는 **준비된 전용 `group-purchase-013` 환경**에서 실행한다. 기존 직접 테스트 절의 JDK 21·Python·secret·제품 JAR와 일곱 서비스 준비 조건을 따른다. 일반 개발 프로젝트에 적용하지 않는다. PostgreSQL 재생성으로 전용 환경의 DB 연결이 잠시 끊기지만 데이터 볼륨은 유지된다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
$env:JAVA_HOME='C:\Users\권민혁\.jdks\ms-21.0.12.1'
$env:COMPOSE_PROJECT_NAME='group-purchase-013'

python scripts/check-postgres.py --stage application --persistence
if ($LASTEXITCODE -ne 0) { throw 'DB 재생성 검사 실패' }
python scripts/check-member-auth.py
if ($LASTEXITCODE -ne 0) { throw '회원 인증·라우팅 복구 검사 실패' }
python scripts/check-gateway-auth.py
if ($LASTEXITCODE -ne 0) { throw '공동구매 인증 회귀 실패' }
python scripts/check-api-observability.py --db-recovery
if ($LASTEXITCODE -ne 0) { throw '관측·DB 복구 회귀 실패' }
```

기대 결과는 `Gateway member route recovered; anonymous request is 401`, 기존 세 경로(Compose 회원·두 제품 JAR·Gateway)의 세션 회전/공유·CSRF·로그아웃 성공과 후속 인증/관측 검사 성공이다. 잘못된 인증 결과나 복구되지 않는 회원 라우트는 계속 실패해야 한다. 검사 도구가 만든 계정·세션·자식 JAR는 도구가 정리한다. 실패 시 `docker compose up -d --wait --wait-timeout 240`으로 해당 전용 서비스를 복구하고 로그를 확인한다. 종료는 기존 절처럼 `docker compose down`으로 볼륨을 보존하며, 마지막에 이번에 설정한 `COMPOSE_PROJECT_NAME` 환경변수를 원복한다.

<a id="manual-tests"></a>

## 사용자 직접 테스트

**정리한 활성 코드에서 실행한다.** 아래 절차는 직접 작성 생략 후에도 그대로 사용할 수 있다. 아래 명령은 PowerShell 기준이다. Docker Desktop Linux 엔진, JDK 21, Python 3, 기존 로컬 secret이 필요하다. 기존 secret 파일을 덮어쓰지 않는다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
$env:JAVA_HOME='C:\Users\권민혁\.jdks\ms-21.0.12.1'
$env:COMPOSE_PROJECT_NAME='group-purchase-013'
$env:POSTGRES_PORT='35432'
$env:KAFKA_PORT='39092'

.\gradlew.bat :group-purchase-service:test --tests '*InboxIntegrationTests' --tests '*ConsumptionFailurePolicyTests' :group-purchase-service:verifyKafkaIsolation :group-purchase-service:outboxProbeJar --no-daemon
if ($LASTEXITCODE -ne 0) { throw '소비 기반 검증 실패: Gradle 보고서를 확인하세요.' }

# 선택한 프로젝트만 기동한다. 8888/8761/8080/8081/8000도 비어 있어야 한다.
docker compose up --build --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { throw 'Compose 기동 실패' }
python scripts/check-inbox.py --project group-purchase-013
if ($LASTEXITCODE -ne 0) { throw '소비 상태 검사 실패: .tmp/inbox/<runId>/ 로그 확인' }
```

기대 결과는 Gradle의 관련 22개 실패/오류/skip 0, 7 healthy·kafka-init exit 0, 제품 `inbox.enabled=false/status=DISABLED`, HOST/INTERNAL 각각 `INBOX_STATE_OK processed=2 business=2 outbox=2 held=1`이다. p0는 같은 ID 두 번 전달에도 업무/Outbox 한 번·커밋 2, p1은 실패 뒤 후속 업무 0·커밋 0·HALTED, p2는 정상 업무/커밋 1이다. DLT는 DB failureId·원본 value·중복/nullable headers를 복원한다.

같은 프로세스의 DB/브로커 장애 복구·실제 두 소비자·전체 down/up까지 확인하려면 다음을 실행한다. **이 전용 프로젝트의 DB·Kafka와 서비스들이 중단**되며, 기존 011/012 프로젝트에는 사용하지 않는다.

```powershell
python scripts/check-inbox.py --project group-purchase-013 --lifecycle
if ($LASTEXITCODE -ne 0) { throw '장애 복구 검사 실패: .tmp/inbox/<runId>/manifest.json 확인' }
```

기대 결과는 DB 중단 중 무더기 DLT 없음, 같은 소비 프로세스가 복구 뒤 새 정상 메시지를 한 번 반영, 이전 p1 HALTED 유지, 두 소비자 재할당 뒤 후속 메시지 정상 처리, 브로커 복구 후 재개, down/up 뒤 진행/보류 보존이다. 마지막 `INBOX_CHECKS_OK`와 manifest의 모든 실행 exit 0을 확인한다. 각 실행은 임의의 `verify.inbox.<runId>.*` 토픽/그룹만 만들고 종료 시 그 이름들만 정리한다. 실패 원본·업무·이력은 전용 DB에 보존한다. 정리 실패 여부는 같은 폴더의 cleanup.log를 확인한다.

실제 강제 종료 네 구간(DB 전/후·DLT 전송 전/ACK 후), 영속 재시도/만료 선점/늦은 ACK, 엄격한 보존 범위·topic ID 변경은 Gradle 통합 테스트가 격리 Testcontainers에서 자동 실행한다. 프로세스 검사 로그 위치는 테스트의 `INBOX_PROCESS_BOUNDARIES_OK diagnostics=...`에 남는다. 일반 업무 DB에 운영 복구용 UPDATE·Kafka offset reset을 실행하지 않는다.

기존 HTTP와 양쪽 Kafka 리스너를 직접 확인할 수 있다.

```powershell
python scripts/check-kafka.py --project group-purchase-013
python scripts/check-outbox.py --project group-purchase-013
python scripts/check-gateway-auth.py --jars
```

종료·복구는 아래와 같다. `down`은 이 전용 프로젝트의 컨테이너/네트워크만 정리하고 PostgreSQL/Kafka 볼륨은 유지한다. 다른 프로젝트를 종료하거나 `down -v`를 사용하지 않는다.

```powershell
# 장애 검사 실패 시 전용 DB/브로커와 제품 서비스부터 복구
docker compose up -d --wait --wait-timeout 240
# 확인 후 종료, 볼륨 보존
docker compose down
Remove-Item Env:COMPOSE_PROJECT_NAME,Env:POSTGRES_PORT,Env:KAFKA_PORT -ErrorAction SilentlyContinue
```

JVM은 검사 도구가 시작한 자식만 종료한다. 실패한 실행의 정확한 토픽/그룹 이름과 JAR 해시는 `.tmp/inbox/<runId>/manifest.json`을 따른다. 다음 검사는 새 runId를 사용한다. 018 전에는 제품의 영구 보류를 자동 해제하지 않는다.
