# 012. Outbox 발행 구현 결과

2026-11-25 KST · **완료 — 사용자 작성·묶음 리뷰·관련 재검증·임시 표식 정리 완료**

[확정 계획](plan/012-outbox-publishing.md#execution-summary) · [적용 설명](../explanations/outbox-publishing.md) · [짧은 인계](../handoff.md)

## PR 생성

2026-11-25 사용자 요청으로 구현 커밋 `a7d3ed3`을 `feat/outbox-publishing`에 push하고 main 대상 [PR #13](https://github.com/y0y00700/group-purchase/pull/13)을 생성했다. PR 작업 직전 fetch에서 main은 `85f7be7` 그대로이며 두 CI 성공을 다시 확인했다. 기존 미커밋 계획·안내 문서도 포함했고 로컬 학습·진단·비밀값은 Git 제외 정책을 유지했다.

최종 head는 [PR 커밋](https://github.com/y0y00700/group-purchase/pull/13/commits), CI는 [PR Checks](https://github.com/y0y00700/group-purchase/pull/13/checks)를 따른다. 최종 push 후 한 번 조회한 상태는 채팅에 안내하고 완료를 기다리거나 반복 조회하지 않는다. 이 문서는 조회 전에 작성되어 CI 성공을 단정하지 않는다. GitHub 연결 도구의 PR 생성은 integration 권한 403으로 거절되어 로그인된 GitHub CLI로 생성했다.

## 작업 기준과 변경

사용자의 2026-11-25 요청을 계획의 진행 합의·구현 착수로 반영했다. 기존 미커밋 012 계획·안내 문서와 Git 제외 학습 폴더를 보존했다. PR #12는 2026-11-24 병합되었고 최종 head `2e4bd22`의 [CI](https://github.com/y0y00700/group-purchase/actions/runs/45414427720)는 성공이다. 착수 시 fetch한 main `85f7be78a4c2391354c55489dc62cba30fc865e4`의 [Build/Compose CI](https://github.com/y0y00700/group-purchase/actions/runs/45431750385)도 모두 성공이다. GitHub 연결 도구가 main workflow 목록을 빈 배열로 반환해 `gh api .../commits/<sha>/check-runs`로 실제 두 check를 확인했다. 이 main에서 `feat/outbox-publishing`을 만들었으며 push·PR 생성은 하지 않았다.

- V4가 스트림·메시지·발행 시도 테이블, 상태/형태/크기/순번 제약과 조회 인덱스를 만든다. V1~V3와 업무 API는 보존했다.
- `StoreOutgoingMessage`는 라이브러리에 의존하지 않는 입력·반환 포트다. `JdbcOutbox.store`는 기존 트랜잭션 필수 참여, 순번 행 증가, 011 봉투 검증·바이트 저장을 수행한다. 결제 결과는 입력 종류에 포함하지 않는다.
- `claim`은 짧은 READ COMMITTED 트랜잭션에서 가장 앞선 미완료 메시지 조건과 `SKIP LOCKED`로 한 건을 선점한다. 이전 미종료 이력은 EXPIRED로 닫고 새 이력을 연다.
- `OutboxPublisher`는 선점 커밋 후 DB 트랜잭션 밖에서 원본을 전송한다. 성공 DB 저장 예외를 전송 실패로 오인하지 않고 현재 선점을 보존한다.
- `complete`·`fail`은 행 잠금을 확보한 다음 DB 시각으로 선점 ID·기한을 검사한다. 잠금 대기 전에 계산한 시각으로 기한을 연장하지 않는다. 변경 1건일 때만 시도 이력을 함께 끝낸다.
- 재시도는 첫 실패 시각·원본을 보존하고 다음 시각과 횟수를 영속화한다. 포화 지수 대기 1~60초의 80~100% 무작위 값을 사용한다. 알 수 없는 오류는 자동 반복하지 않고 SUSPENDED로 남긴다.
- 제품 Kafka 클라이언트와 lazy producer를 추가했다. 빈 Outbox는 브로커에 연결하지 않는다. 종료 이벤트에서 새 선점을 멈추고 진행 건의 제한된 대기 후 producer를 닫는다. 공동구매 컨테이너 종료 유예는 70초다.
- 전용 단일 스케줄러는 고정 지연 10초, 최대 50건, 새 전송 시작 예산 10초로 실행한다. 설정은 중앙 `group-purchase*.yml`에 있으며 시간 관계·상한을 기동 시 검증한다.
- `outboxProbe`는 별도 JAR다. 표본 저장·수신·관찰과 강제 종료 동기화는 검증 도구에만 있으며 제품 JAR/HTTP API에 없다. CI Build는 통합 테스트를, Compose smoke는 정상 제품 발행을 실행한다.

## 실패 분류

| 실제 클래스/상황 | 처리 |
|---|---|
| Kafka `RetriableException` 하위(네트워크·Kafka Timeout 등), Future `TimeoutException`, `InterruptedException` | RETRY_WAIT, 발행 여부 불명 포함 |
| `ExecutionException` | 원인을 풀어 같은 정책 적용 |
| `AuthenticationException`, `AuthorizationException`/`TopicAuthorizationException`, `RecordTooLargeException`, `SerializationException`, `InvalidTopicException` | SUSPENDED |
| `ContractFailure`, 저장 메타데이터 불일치, 기타 예상하지 못한 Exception | SUSPENDED |
| DB 선점 실패 | 전송 없음, 회차 경고 후 다음 스케줄링 |
| ACK 뒤 결과 DB 실패/응답 유실 | 전송 실패로 바꾸지 않음. DB에 커밋된 상태가 기준이며 미완료만 만료 후 재선점 |

로그에는 식별자·오류 클래스 코드·다음 시각만 넣으며 본문·자격 증명을 넣지 않는다. `outbox_failure_transition_pending_commit`는 커밋 전 전이 관찰이며 실제 상태는 DB로 확인한다. `outbox_delivery_failed`의 recorded와 `outbox_ack_received`의 recorded는 조건부 반영 여부다. ACK 자체가 DB 완료를 뜻하지 않는다.

<a id="final-results"></a>

## 사용자 코드 최종 결과

2026-11-25 KST 사용자 작성 완료 후 여섯 구간을 묶음 리뷰했다. 실행 행은 들여쓰기·행 주석을 제외하고 검증한 기준 구현과 모두 일치했으며 논리 오류는 없었다. 작성한 코드는 교체하지 않았다.

| 확인 | 실제 결과·근거 |
|---|---|
| 사용자 원문·기준 대조 | 두 파일의 제출 원문을 `.tmp/012-user-review/*-user.java`에 보존. `source-review.json`에서 기준과 실행 행 일치를 확인 |
| 사용자 코드 관련 검사 | Outbox 통합 13개 + 실패 정책 2개, 실패/오류/skip 0. 실제 PostgreSQL/Kafka·경합·DB 트랜잭션 해제·강제 종료/재시작·ACK 뒤 DB 실패·COMMIT 응답 소실 포함. `.012-user-tests.log`, `.tmp/012-user-review/test-summary.json`, 같은 폴더 `test-results/` |
| 임시 표식 정리 | 참고 제목·호출 안내·직접 작성 위치·주석 처리된 대기 예외 30줄 제거. 실행 텍스트가 정리 전후 정확히 동일함을 `cleanup-review.json`으로 확인. 제품 소스에 LEARN/작성 대기 표식 없음 |
| 정리 후 산출물 | `assemble outboxProbeJar verifyKafkaIsolation` 성공. 현재 소스로 제품·두 probe JAR 재빌드, 제품 Kafka 발행기 포함·검증 도구 제외 확인. `.012-user-clean-jars.log` |
| 현재 이미지·제품 발행 | 정리한 소스로 이미지 재빌드, 전용 Compose 7 healthy/init exit 0. 정상 스케줄러의 INTERNAL 전송을 Windows HOST probe에서 원본 바이트·키로 비교하고 PUBLISHED/시도 위치 기록 확인. `.012-user-compose.log`, `.012-user-product-check.log`, `.tmp/outbox/1c1d3ca3718d4bab935386576a468907/summary.json`(7개 명령 exit 0) |

현재 사용자 코드에 대해 새로 실행한 기능 검사는 관련 15개다. 나머지 99개와 전체 build·브로커/DB 장애 복구·전체 down/up·Windows 제품 JAR·기존 HTTP/Kafka 회귀는 아래 기준 구현의 유효 근거를 재사용했다. 두 작성 파일의 실행 내용이 기준과 같고 다른 Java 파일·설정 경계에 추가 변경이 없어 같은 검사를 반복하지 않았다. 주석 정리 후에도 실행 텍스트가 같으므로 15개를 다시 중복 실행하지 않고 JAR/이미지와 실제 제품 발행을 확인했다. 총 114개 유효 결과와 012 필수 근거를 유지한다.

제품 정상 발행 검사는 첫 호출을 Compose 기동 완료 전에 시작해 준비 조건에서 중단했다(`.012-user-product-startup.log`). 7 healthy·init exit 0을 확인한 뒤 재실행했고 정상 발행 검사를 통과했다. 제품 기능 오류나 코드 수정은 없었다.

이번 제품 검사 스냅샷 SHA-256: product `e49a3291c5fa188f1064f9d10cd33cc430eb5327e78c0a8ad1263f12ee1035d3`, outbox probe `ea328b810378893c0f08618ae1e874f1a40f59b915edaeaa19867a1c3350e4f6`. 같은 run의 `artifacts.json`에 기록했다. 검증 프로젝트는 `down`으로 종료하고 DB/Kafka 볼륨을 보존했다(`.012-user-shutdown.log`). 사용자 코드 검증 당시에는 커밋·push·PR 생성 전이었으며, 이후 별도 요청에 따른 PR 현황은 위 PR 생성 절을 따른다.

두 JAR의 SHA-256은 기준 구현의 제품 검사 run과도 모두 동일했다. 정리한 사용자 소스로 재빌드한 제품·Outbox probe가 기존 검증 산출물과 바이트 단위로 같음을 확인했으므로 기존 Windows 제품 JAR·장애 복구 근거 재사용도 뒷받침한다.

<a id="verification"></a>

## 실제 검증 근거

다음은 **참고 주석 전환 전 기준 구현**의 근거다. 최신 사용자 코드 결과는 위 최종 결과와 구분하며, 아래 작성 대기 관련 기록은 당시 전환 이력이다.

| 실행 | 실제 결과·근거 |
|---|---|
| `gradlew.bat build --no-daemon` | 성공, 전체 유효 결과 114개·실패/오류/skip 0. 공동구매 71개를 새 실행했고 나머지 43개는 변경 없는 유효 결과를 Gradle이 재사용했다. `.012-final-build.log`, `.tmp/012-full-results/summary.json` 및 XML 보존 |
| 새 Outbox 통합 13개 + 실패 정책 2개 | 업무/순번/Outbox 원자성(크기·INSERT 실패·후속 예외), MANDATORY, V3 참여·멱등성 보존→V4, 동시 순번·롤백·상한·알림, 두 선점자/잠긴 앞 행, 만료/새 선점/늦은 완료·실패, 재시도/중단·이력, Kafka 대기 중 연결/잠금 반환, DB 선점 실패 뒤 다음 회차, 누락 토픽 재시도 및 실제 Kafka 원본/위치, 실제 JVM 강제 종료·복구, 실제 COMMIT 응답 소실을 확인 |
| 프로세스 검사 보강 후 재실행 | ACK 뒤 DB 완료 거절이 실제 발생했는지 로그까지 검증하도록 보강하고 장애용 선점을 15초로 조정한 뒤, 해당 통합 테스트를 재실행해 성공했다. 전체 build의 변경 없는 나머지 근거를 재사용했다. `.012-process-final.log` 및 현재 XML |
| DB COMMIT 응답 소실 | 테스트 전용 TCP 프록시가 PostgreSQL의 이미 COMMIT된 응답을 실제로 끊는다. 호출 측은 트랜잭션 예외를 받지만 DB의 PUBLISHED·시도 완료는 유지되고 새 선점은 없다. 이 단일 검사의 전송 ACK는 stub이며 실제 Kafka ACK/위치는 별도 통합·프로세스 검사에서 확인 |
| 제품·probe JAR | 제품은 Kafka 클라이언트와 `KafkaOutboxTransport` 포함, kafka/outbox/runtime probe·JUnit·Testcontainers 제외. `verifyKafkaIsolation` 성공. 실제 별도 probe JAR 실행도 성공 |
| 이미지/Compose | 다섯 앱 이미지 빌드, 전용 `group-purchase-012` 7 healthy·kafka-init exit 0. 최종 공동구매 이미지를 종료 연결 변경 후 다시 빌드·기동했다. `.012-compose.log`, `.012-image-final.log` |
| `check-outbox.py --project group-purchase-012 --lifecycle` | 성공, 63개 명령 exit 0. Docker INTERNAL 발행→Windows HOST 수신, 브로커 중단 중 RETRY_WAIT·HTTP readiness 200, 동일 제품 프로세스의 DB 장애 복구, Windows local 제품 JAR 발행, 전체 down/up 뒤 볼륨·완료 상태·원본 보존. `.012-product-check.log`, `.tmp/outbox/c6a767b713314fce938b093a387ce19e/summary.json` |
| `check-gateway-auth.py --jars` | Compose 8000과 Windows 제품 JAR Gateway 18000에서 로그인·현재 역할·게시·참여·취소·멱등 응답·호출 신뢰 회귀 성공. 표본 계정·참여 데이터 정리 완료. `.012-http-regression.log` |
| `check-kafka.py --project group-purchase-012` | 22개 검사 성공(의도한 설정/계약 거절 4개 포함), HOST/INTERNAL·그룹/커밋·프로세스 재시작 회귀 확인. `verify.0199bf1985a04e12a9fbfca249ad3eba.*` 토픽/그룹만 정리했다. `.012-kafka-regression.log`, `.tmp/kafka/0199bf1985a04e12a9fbfca249ad3eba/summary.json` |
| PowerShell 조회 예시 | 이 문서의 here-string→`psql` 조회를 실제 실행, PUBLISHED 4건·시도 이력 5건(일시 실패 1회 포함), 후속 차단 미완료 0건. `.012-manual-sql.log` |

제품 검사에 사용한 스냅샷 JAR SHA-256은 product `e49a3291c5fa188f1064f9d10cd33cc430eb5327e78c0a8ad1263f12ee1035d3`, outbox probe `ea328b810378893c0f08618ae1e874f1a40f59b915edaeaa19867a1c3350e4f6`이다. 같은 run의 `artifacts.json`에 보존했다. Windows/Compose 검사에서 외부 메시지 본문·비밀값은 출력하지 않았다.

주석 전환 전 소스·JAR·직접 작성 대상 본문 해시는 `.tmp/outbox-baseline/manifest.json`과 같은 폴더의 `source/`, `jars/`에 보관했다. 전체 114개 결과 XML은 `.tmp/012-full-results/`에, 보강 후 프로세스 결과는 `.tmp/outbox-baseline/test-results/`에 따로 보존했다. 전환 뒤에는 컴파일과 참고 주석/placeholder 일치만 검사하며 기능 검사를 통과했다고 표시하지 않는다. 검증 자원은 `docker compose -p group-purchase-012 down`으로 종료했고 DB/Kafka 볼륨 두 개는 보존했다(`.012-shutdown.log`).

전환 후 `:group-purchase-service:compileJava` 성공(`.012-learning-compile.log`), 여섯 참고 본문이 검증한 본문 해시와 일치하고 여섯 입력 위치가 모두 작성 대기 예외임을 확인했다(`.tmp/outbox-baseline/learning-check.json`). `git diff --check`도 통과했다. 기능 테스트 기대값을 낮추거나 작성 대기 메서드를 우회하지 않았다.

이 기준 구현 검증 시점에는 착수 기준 main/head CI만 확인했고 012는 push 전이었다. 이후 PR 현황은 위 PR 생성 절을 따른다. 역할/인증·DB 소유권 같은 변경 없는 경계의 과거 유효 근거도 유지한다. 장기 적체/처리량·운영 중단 해제·소비 업무 중복 방지·실제 결제/Saga는 이번 검증 완료 범위가 아니다.

초기 수정 이력: 새 격리 테스트에서 Config Server 픽스처가 설정 클라이언트를 끄므로 명시 활성화했다. 기존 HTTP 오류 테스트는 DB 없는 픽스처에 새 JDBC/트랜잭션 mock을 보완했다. 강제 종료용 3초 선점이 이미지 동시 빌드 부하에서 정상 복구 발행까지 만료되는 사례를 확인해, 재시작 후 정상 발행은 제품값 60초를 사용한다. 장애 지점의 선점만 짧게 두고 실제 만료를 기다린다.

<a id="guided-coding"></a>

## 여섯 구간 작성·정리 결과

**LEARN-01~06 사용자 작성·묶음 리뷰·관련 재검증 완료**다. 임시 표식을 제거했으며 아래 번호는 작성 이력이다. IntelliJ에서 열 수 있도록 각 링크는 행 번호 조각 없는 상대 파일 경로이고 행 번호는 설명에만 둔다.

| 작성 순서 | 현재 파일·메서드 위치 | 검증한 역할 |
|---|---|---|
| LEARN-01 | [JdbcOutbox.store, L46](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/outbox/infrastructure/JdbcOutbox.java) | 업무·순번·원본의 원자적 저장 |
| LEARN-02 | [JdbcOutbox.claim, L100](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/outbox/infrastructure/JdbcOutbox.java) | 앞 미완료 차단·선점·만료 이력 |
| LEARN-03 | [OutboxPublisher.publishOne, L21](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/outbox/infrastructure/OutboxPublisher.java) | 트랜잭션 밖 전송·ACK/DB 실패 구분 |
| LEARN-04 | [JdbcOutbox.complete, L138](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/outbox/infrastructure/JdbcOutbox.java) | 유효 선점의 조건부 완료 |
| LEARN-05 | [JdbcOutbox.fail, L153](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/outbox/infrastructure/JdbcOutbox.java) | 영속 재시도/중단·실패 이력 |
| LEARN-06 | [OutboxPublisher.poll, L47](../../services/group-purchase-service/src/main/java/com/example/grouppurchase/outbox/infrastructure/OutboxPublisher.java) | 제한된 회차·실패 후 재실행·종료 |

<a id="manual-tests"></a>

## PowerShell 직접 테스트

사용자 작성과 검증을 마친 현재 소스로 실행할 수 있다. 아래 명령은 전용 `group-purchase-012` DB·브로커에만 표본을 넣는다. 기존 개발 프로젝트의 볼륨·토픽을 사용하지 않는다.

준비: Docker Desktop Linux 엔진, JDK 21, Python 3, PowerShell. 첫 빌드는 Gradle·이미지 다운로드가 필요하다. `JAVA_HOME`은 설치 위치에 맞춘다. 기본 앱 포트 8000/8080/8081/8888/8761, 아래 DB 15432·Kafka 19094가 비어 있어야 한다. lifecycle 검사의 local 제품 JAR는 18080을 사용한다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
$env:JAVA_HOME = 'C:\Users\권민혁\.jdks\ms-21.0.12.1'
$env:COMPOSE_PROJECT_NAME = 'group-purchase-012'
$env:POSTGRES_PORT = '15432'
$env:KAFKA_PORT = '19094'
& "$env:JAVA_HOME\bin\java.exe" -version
docker info --format '{{.ServerVersion}}'
python scripts/prepare-ci-secrets.py
.\gradlew.bat build :group-purchase-service:outboxProbeJar :group-purchase-service:kafkaProbeJar --no-daemon
if ($LASTEXITCODE -ne 0) { throw '기준 코드 검사를 먼저 확인하세요.' }
docker compose up --build --wait --wait-timeout 240
docker compose ps -a
python scripts/check-outbox.py --project group-purchase-012
```

정상: Gradle 실패/skip 0, 7 healthy·kafka-init exit 0, `STATE_OK ... PUBLISHED`, 수신 `MATCHES 1`, 마지막 `OUTBOX_OK`. 제품 스케줄러가 INTERNAL 주소로 발행하고 Windows probe가 HOST로 수신해 저장 바이트와 비교한다. 소요 시간은 초기 다운로드와 환경 부하에 따라 달라진다. 진단은 `.tmp/outbox/<runId>/summary.json`, `artifacts.json`, 단계별 로그에 남는다.

주요 실패·복구 및 Windows 제품 JAR 검사(약 수 분):

```powershell
python scripts/check-outbox.py --project group-purchase-012 --lifecycle
```

브로커 중단 중 RETRY_WAIT·최초 실패/재시도 횟수 보존·기존 HTTP readiness 200, 복구 뒤 같은 메시지 PUBLISHED를 확인한다. DB 중단 뒤 같은 제품 프로세스의 폴링 복구도 확인한다. Compose 발행기를 잠시 중단하고 별도 local 제품 JAR(18080)을 띄워 HOST 전송을 확인한 뒤 원복한다. 마지막 전체 down/up은 볼륨을 보존하고 기존 완료 메시지·원본을 재확인한다. 도구가 띄운 local JAR는 finally에서 종료한다.

실제 JVM 중단·늦은 반영·원자성 등 격리 자동 검사만 따로 실행:

```powershell
.\gradlew.bat :group-purchase-service:test --tests '*OutboxIntegrationTests' --tests '*DeliveryFailureTests' --no-daemon
```

이 검사는 Testcontainers의 임시 DB·브로커를 사용한다. 저장 커밋/선점 커밋/ACK 뒤 자식 JVM을 강제 종료하고 재시작한다. ACK 뒤 종료는 같은 ID·순번·바이트의 두 수신과 EXPIRED→PUBLISHED 이력이 정상이다. PostgreSQL 실제 COMMIT 응답을 끊는 검사는 DB에 이미 완료된 메시지를 재선점하지 않는지 확인한다. 이 테스트의 짧은 장애용 선점값을 제품 설정에 복사하지 않는다.

별도 터미널에서 전용 DB 상태 조회(비밀값은 컨테이너 내부에서만 읽음):

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
$env:COMPOSE_PROJECT_NAME = 'group-purchase-012'
@'
SELECT status,count(*),min(created_at) AS oldest FROM app.outbox_messages GROUP BY status;
SELECT message_id,status,sequence,claim_id,claim_until,retry_count,next_attempt_at,last_error_code
FROM app.outbox_messages ORDER BY created_at;
SELECT message_id,claim_number,outcome,error_code,kafka_partition,kafka_offset
FROM app.outbox_delivery_attempts ORDER BY started_at;
SELECT m.message_id,m.status,m.sequence,e.message_id AS blocking_message,e.status AS blocking_status
FROM app.outbox_messages m JOIN app.outbox_messages e
ON e.direction=m.direction AND e.payment_attempt_id=m.payment_attempt_id
AND e.sequence<m.sequence AND e.status<>'PUBLISHED' WHERE m.status<>'PUBLISHED';
'@ | docker compose exec -T postgres sh -c 'export PGPASSWORD="$(cat /run/secrets/group_purchase_password)"; exec psql -X -w -h 127.0.0.1 -U group_purchase_app -d group_purchase -v ON_ERROR_STOP=1'
docker compose logs --no-color --tail=100 group-purchase-service
```

중단/복구:

```powershell
# 실패 후 진단을 보존하고, 중단된 전용 의존성과 서비스부터 복구
docker compose up -d --wait --wait-timeout 240
# 검사 종료: 전용 컨테이너·네트워크 종료, DB/Kafka 볼륨은 보존
docker compose down
Remove-Item Env:COMPOSE_PROJECT_NAME,Env:POSTGRES_PORT,Env:KAFKA_PORT -ErrorAction SilentlyContinue
```

실패한 도구의 로그와 `.tmp/outbox`는 진단용으로 남겨 둔다. SUSPENDED는 자동 재개하지 않는다. 사용자 DB를 수동 UPDATE해 운영 재개하는 절차는 제공하지 않는다. 반복 검사에는 새로운 표본 ID가 사용되며 보존한 전용 DB의 기존 완료 기록을 지우지 않는다. `down -v`는 이 전용 프로젝트의 검증 데이터를 모두 버릴 때만 별도로 선택한다. 일반 프로젝트에 실행하지 않는다.
