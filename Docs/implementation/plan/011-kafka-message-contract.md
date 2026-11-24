# 011. Kafka 실행·메시지 계약 계획

작성일 2026-11-24 KST · 상태 **완료 / 사용자 작성·묶음 리뷰·로컬 재검증 완료**

처음 읽을 때: [Kafka 개념·추가 설정·코드 확인 순서의 쉬운 설명](../../learning/questions/kafka-011-plan-explained.md)

<a id="execution-summary"></a>

## 실행 요약

- 목표는 단일 Kafka의 실행 기반과 서비스 사이의 JSON 메시지 전달 계약을 마련하고, 실제 브로커에서 발행·수신·재시작·호환성을 검증하는 것이다.
- 기존 합의인 결제 명령·결제 결과·알림 의뢰 토픽 분리, 원승인 결제 시도 ID 또는 공동구매 ID의 키, `acks=all`·프로듀서 멱등성·자동 커밋 해제를 유지한다. [기존 메시지 계약](../../design/payment-and-messaging.md#section-11)
- 2026-11-24 사용자 진행 합의·구현 요청에 따라 기준 구현과 필수 로컬 검증을 마쳤다. 여섯 구간을 한 번에 준비한 뒤 **사용자 작성·묶음 리뷰·관련 재검증까지 완료**하고 임시 표식을 정리했다. [실제 결과·작성 순서](../011-kafka-message-contract.md)
- 브로커는 공식 JVM 이미지 `apache/kafka:4.1.2`, KRaft의 broker/controller 겸용 단일 노드다. 현재 Boot 4.0.9 BOM이 관리하는 `kafka-clients:4.1.2`와 맞춘다.
- 호스트는 `localhost:9092`, Compose 내부는 `kafka:19092`를 사용한다. 컨트롤러 포트는 호스트에 공개하지 않고 데이터·메타데이터는 전용 named volume에 보존한다.
- 업무 토픽은 3개, 토픽별 파티션 3개·복제 수 1·`min.insync.replicas=1`·보존 7일이다. 설정이 다른 기존 토픽은 자동 변경하지 않고 차이를 보고한다.
- JSON 공통 봉투의 ID·종류·버전·시각·추적·업무 연결과 결제 시도/방향별 순번을 구체화한다. 업무별 본문 전체와 결제 상태 전이는 후속 결제·Saga·알림 계획에서 확정한다.
- 검증은 별도 `kafkaProbe` source set과 실행 JAR를 사용한다. 제품 앱에 Kafka 발행기·리스너나 공개 테스트 API를 추가하지 않는다.
- 실제 왕복은 실행별 검증 토픽·소비 그룹에서 수행한다. 업무 토픽에는 테스트 결제·알림 메시지를 넣지 않는다.
- 012의 Outbox 원자성·선점·재발행, 013의 소비 DB 기록·DLT·파티션 보류, 014~017의 PG·결제·Saga·알림 업무는 이번 구현 범위 밖이다. [로드맵](roadmap.md#next-implementation)
- 핵심 여섯 구간을 기준 구현·검증 후 한 번에 직접 작성하도록 준비하고, 작성 후 묶음 리뷰·재검증한다.
- 완료 기준은 계약 테스트, 실제 Kafka 통합 테스트, 일곱 상시 Compose 서비스와 초기화 작업, Windows 검증 JAR, 기존 HTTP 기능 회귀, 직접 테스트·학습 문서 기록이다.
- 착수 시 최신 010 PR/main/head CI를 조회했다. 010 PR·head CI가 없어 010 head에서 의존 브랜치를 만들었으며 실제 SHA·링크·CI 상태는 [결과의 착수 기준](../011-kafka-message-contract.md#착수-기준)에서 관리한다. 이후 [PR #12](https://github.com/y0y00700/group-purchase/pull/12)를 생성·push했으며 실제 범위와 원격 상태는 구현 결과에서 관리한다.

## 출발점과 범위

010의 참여·조회·결제 전 취소와 여섯 직접 작성 구간의 로컬 검증을 마쳤다. 로컬 브랜치는 `feat/group-purchase-participation`, 확인한 HEAD는 `d411960`이다. 현재 Compose에는 PostgreSQL과 다섯 앱이 있고 Kafka 의존성·업무 소비자는 없다. [010 최종 결과](../010-participation.md#final-results) · [인계](../../handoff.md)

이번 단계는 공통 봉투·토픽·전송 설정의 검증까지다. 참여 신청·취소에서 메시지를 발행하거나 DB 스키마를 변경하지 않는다. 업무 변경과 직접 `send()`를 연결하면 Outbox를 거치지 않는 유실 경로가 생기므로, 제품 발행 연결은 012 이후 해당 업무 단계에서 한다. Kafka와 PostgreSQL을 하나의 트랜잭션으로 묶는 구성도 도입하지 않는다.

계약 코드는 공동구매 서비스의 `infrastructure/messaging` 안에서 시작하고 도메인·application은 Kafka 및 JSON 라이브러리에 의존하지 않는다. 다른 서비스나 공유 메시지 모듈을 미리 만들지 않으며, 후속 서비스는 같은 계약 문서·JSON 표본을 기준으로 자신의 경계에서 구현한다. [패키지·의존 방향](../../design/architecture.md#package-structure)

<a id="runtime"></a>

## 버전과 실행 구성

2026-11-24 계획 작성 때 기존 Gradle 캐시의 `spring-boot-dependencies:4.0.9` POM을 읽어 `kafka.version=4.1.2`, `spring-kafka.version=4.0.8`, `testcontainers.version=2.0.5`, `jackson-bom.version=3.1.7`을 확인했다. 당시에는 의존성 해석·빌드·이미지 실행 전이었으며 이후 실제 실행 근거는 [구현 결과](../011-kafka-message-contract.md#실제-검증-근거)에 기록했다. 현재 BOM의 클라이언트와 같은 버전으로 연결 범위를 정한다. 공식 문서에서도 해당 JVM 이미지와 단일 노드 구성을 제공한다. [공식 Docker 이미지](https://kafka.apache.org/41/getting-started/docker/) · [4.1.2 단일 노드 예제](https://github.com/apache/kafka/blob/4.1.2/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml)

| 항목 | 추천 값·동작 |
|---|---|
| 브로커 | `apache/kafka:4.1.2`, `process.roles=broker,controller`, `node.id=1` |
| 리스너 | `INTERNAL://:19092`, `HOST://:9092`, `CONTROLLER://:9093`; inter-broker는 INTERNAL |
| 광고 주소 | `INTERNAL://kafka:19092`, `HOST://localhost:${KAFKA_PORT:-9092}`; 호스트 바인딩은 `127.0.0.1:${KAFKA_PORT:-9092}:9092` |
| 컨트롤러 | `controller.quorum.voters=1@kafka:9093`; CONTROLLER는 호스트 포트 매핑 없음 |
| 저장 | `kafka-data`를 `/var/lib/kafka/data`에 연결하고 `log.dirs`도 이 경로로 고정. 이미지 실행 사용자의 쓰기 권한 확인 |
| 클러스터 ID | 구현 시 한 번 생성한 개발용 고정 ID를 일반 설정으로 관리. 기존 볼륨 재시작 때 재생성·재포맷하지 않음 |
| 내부 토픽 | offsets 및 transaction-state의 복제 수 1, transaction-state 최소 ISR 1. Kafka 트랜잭션은 사용하지 않음 |
| 준비 확인 | 내부 리스너로 Kafka API 요청 성공을 확인하는 healthcheck. 단순 TCP 연결만으로 준비 완료 판정하지 않음 |
| 종료·자원 | `stop_grace_period=30s`, 초기 JVM heap 256~512 MiB. 기동·CI 실행 시간을 측정해 근거와 함께 조정 |
| 통신 | 로컬 Compose 네트워크의 PLAINTEXT. 외부 배포의 TLS·SASL·ACL은 별도 환경 설계에서 결정 |

Kafka는 Config·Eureka에 의존하지 않는다. 기존 다섯 앱에도 Kafka 기동 의존을 추가하지 않는다. `kafka-init`은 같은 고정 이미지에서 실행하는 일회성 작업으로, Kafka healthy 뒤 토픽을 준비·확인하고 성공 시 0으로 종료한다. 상시 서비스는 기존 여섯 개에서 일곱 개가 되며 초기화 컨테이너는 정상 종료 상태로 구분한다. 후속 실제 메시지 앱에 필요한 시작 의존은 해당 단계에서 추가한다.

재실행 가능한 초기화 스크립트는 없는 토픽만 만들고, 기존 토픽의 파티션·복제 수·필수 설정을 조회해 비교한다. 차이가 있으면 자동 증설·삭제·설정 변경 없이 실패한다. `auto.create.topics.enable=false`와 클라이언트의 `allow.auto.create.topics=false`로 토픽 오타가 새 토픽을 만들지 않게 한다.

단일 노드의 복제 수·최소 ISR가 모두 1이므로 `acks=all`도 다른 노드의 복제본을 기다리지 않는다. 볼륨 재시작의 보존과 통신 복구를 확인하는 구성이며, 디스크 유실·다중 브로커 장애·고가용성·매 메시지 fsync의 근거로 사용하지 않는다. [실행 환경 합의](../../design/operations.md#local-runtime) · [KRaft 공식 문서](https://kafka.apache.org/41/operations/kraft/)

<a id="topics"></a>

## 토픽·키·소비 역할

| 목적 | 토픽 | Kafka key | 후속 소비 그룹 / 처리 역할 |
|---|---|---|---|
| 승인·중단·환불 의뢰 | `gp.payment.commands.v1` | 원승인 `paymentAttemptId` | `payment-service.commands.v1` / `payment-command-handler` |
| 승인·환불 등의 결과 | `gp.payment.results.v1` | 원승인 `paymentAttemptId` | `group-purchase-service.payment-results.v1` / `purchase-payment-result-handler` |
| 알림 의뢰 | `gp.notification.requests.v1` | `groupPurchaseId` | `notification-service.requests.v1` / `notification-request-handler` |

- 각 업무 토픽의 초기 파티션 수는 3, 복제 수는 1, `min.insync.replicas=1`, `cleanup.policy=delete`, `retention.ms=604800000`(7일), `retention.bytes=-1`, `segment.ms=3600000`(1시간), `max.message.bytes=1048576`(1 MiB)로 제안한다. 7일은 초기 로컬 보관값이며 용량 측정과 복구 목표를 바탕으로 재검토한다. 로그 정리는 세그먼트 단위여서 7일 정각의 즉시 삭제를 보장하지 않는다. [토픽 설정](https://kafka.apache.org/41/configuration/topic-configs/)
- Kafka 보존 기간이 지나도 미완료 업무·Outbox·소비 기록을 지우지 않는다. 소비 지연이 보존 범위를 넘을 때의 누락 감지·자동 소비 중단·운영 복구와 보존값 재검토는 013에서 정한다. 아래 검증 소비자의 `earliest`는 실행별 새 그룹의 표본 수신용이며 제품 소비자의 누락 건너뛰기 정책으로 사용하지 않는다.
- 키는 UTF-8 소문자 하이픈 UUID 문자열이다. 환불 ID로 키를 바꾸지 않는다. 소비 시 봉투의 업무 ID와 실제 Kafka key가 일치하는지 확인한다.
- 파티션 3개는 서로 다른 키의 배치·소비 그룹 동작을 확인하기 위한 초기값이다. 단일 브로커의 가용성이나 성능 개선을 뜻하지 않는다. 최초 소비 병렬도는 인스턴스당 1로 시작한다.
- 파티션 수가 고정된 같은 토픽에서 같은 키의 전송 순서를 검증한다. 서로 다른 토픽·파티션 사이의 순서와 여러 발행기의 업무 생성 순서는 Kafka만으로 보장하지 않는다. 파티션 증설은 키 배치가 바뀔 수 있으므로 별도 변경 계획을 거친다.
- 소비 그룹은 Kafka 작업 분배·오프셋의 기준이고 처리 역할은 013에서 DB 중복 기록에 사용할 논리적 책임이다. 그룹·배포 버전 변경을 이유로 처리 역할이나 기존 업무 식별자를 새로 만들지 않는다.
- 위 업무 소비자는 아직 실행하지 않는다. DLT 토픽명·보관 내용·생성·파티션 보류·재개는 013에서 함께 정한다. 011에서 자동 재시도 토픽이나 실패 후 건너뛰기를 도입하지 않는다.

<a id="envelope"></a>

## JSON 공통 봉투와 호환성

전송 값은 UTF-8 JSON 바이트이며 Java 클래스명·Spring 타입 헤더에 의존하지 않는다. 기존 Jackson 3의 `tools.jackson`을 사용하고, HTTP 입력 전용 `ApiInput`과 메시지 오류 분류는 분리한다.

| 필드 | v1 계약 |
|---|---|
| `messageId` | 필수 소문자 하이픈 UUID. 최초 메시지 생성 때 부여하고 재발행 때 유지 |
| `messageType` | 필수 종류 문자열. 아래 토픽별 등록 목록과 일치해야 함 |
| `schemaVersion` | 필수 JSON 정수 `1`. 문자열·실수의 자동 변환을 허용하지 않음 |
| `occurredAt` | 필수 UTC 시각. `Z`와 소수점 이하 6자리의 ISO-8601 표현을 표준 출력으로 사용 |
| `correlationId` | 필수 UUID. 최초 업무 흐름에서 생성하고 연결된 명령·결과에 전파. 소비 그룹·선점 ID와 구분 |
| `causationId` | 원인 메시지의 `messageId`. 최초 HTTP 유래 메시지에는 생략하고, 메시지 처리에서 생성한 후속 메시지에는 기록 |
| `context` | 필수 JSON 객체. 모든 종류의 `groupPurchaseId`, 결제 종류의 `participationId`·원승인 `paymentAttemptId`, 환불 종류의 `refundId`를 UUID로 기록 |
| `sequence` | 결제 명령·결과에 필수인 1 이상의 signed 64-bit JSON 정수. 같은 결제 시도·전달 방향 안의 생성 순서. 알림 의뢰에는 이번 단계에서 부여하지 않음 |
| `payload` | 필수 JSON 객체. 업무별 본문은 해당 단계의 타입별 검증에서 확인 |

선택 필드는 값이 없으면 생략하며 `null`을 값 대신 허용하지 않는다. 공통 봉투 전체는 최대 64 KiB로 제한하고 JSON 중복 키·후행 토큰·UTF-8 오류·누락·잘못된 자료형·UUID·시각·양수가 아닌 순번을 거절한다. 순번은 전송·소비 도구가 생성하거나 재정렬하지 않는다. 원자적인 순번 할당은 012에서 업무·Outbox 저장과 함께 구현한다. 동일 메시지의 재발행은 ID·시각·순번·본문을 유지하며, 다른 결과 통지는 새 메시지 ID와 다음 해당 방향 순번을 사용한다.

| 토픽 | v1 종류 추천 이름 | 011의 검증 범위 |
|---|---|---|
| 결제 명령 | `PaymentApprovalRequested`, `PaymentApprovalStopRequested`, `PaymentRefundRequested` | 종류·토픽·키, 필수 업무 ID, 결제 순번 |
| 결제 결과 | `PaymentApprovalResultReported`, `PaymentRefundResultReported` | 같은 원승인 시도 연결, 결과 메시지의 별도 ID·방향별 순번 |
| 알림 의뢰 | `NotificationRequested` | 공동구매 ID 연결과 키, 결제 시도·환불 ID를 강제하지 않는 구조 |

011에서는 이 등록 목록·공통 봉투를 확정 대상으로 삼는다. `payload` 전체의 필수 필드, 승인 중단 결과의 세부 구분, PG 거래 식별자·실제 승인/환불 시각, 알림 대상·채널은 015~017에서 별도 계약을 검토한다. 검증 JSON에는 승인 의뢰의 원 단위 정수 `amount`, `currency="KRW"`, `approvalDeadline` 및 결과 확인 중·성공의 예시 본문을 사용하되, **전송 검증 표본**으로 표시하고 실제 결제 계약·승인 결과 판정이 완성됐다고 기록하지 않는다. 생성 시각을 PG 승인 시각으로 대체하지 않는다. [식별자 수명·충돌](../../design/payment-and-messaging.md#section-8-11)

호환성 기준은 다음과 같다.

1. 지원하는 종류·버전에서 알 수 없는 선택 필드는 무시한다. 이미 알려진 필드의 자료형·필수 조건은 엄격히 검사한다. 입력 본문의 원본 바이트는 별도로 유지한다.
2. 구버전 JSON과 선택 필드를 더한 JSON을 모두 읽는 고정 표본 테스트를 둔다. 삭제·이름 변경·자료형/의미 변경·새 필수 필드·enum 값 추가처럼 기존 소비자가 해석하지 못하는 변경은 같은 버전에 조용히 넣지 않는다.
3. 지원하지 않는 버전·종류·토픽/키 불일치는 서로 구분된 계약 오류다. JSON 파싱 성공만으로 처리 완료하지 않는다. 새 종류를 같은 토픽에 추가할 때도 기존 소비자의 지원·배포 순서를 먼저 계획한다.
4. 호환되지 않는 변경에는 새 `schemaVersion`과 새 `.v2` 토픽을 함께 검토하고 이관·구버전 소비 종료를 별도 계획한다. 011은 v1만 구현하며 Schema Registry는 도입하지 않는다.
5. 최종 업무 본문 검증이 추가되면 기존 표본과의 호환성을 다시 판단한다. 011의 예시 본문을 그대로 제품 업무 계약으로 승격하지 않는다.

<a id="clients"></a>

## 발행·소비 검증 도구

Kafka를 사용하는 코드는 공동구매 모듈의 별도 `kafkaProbe` source set에 둔다. Boot BOM의 버전 없는 `org.apache.kafka:kafka-clients`를 이 source set과 테스트에 적용하고, 검증용 `kafkaProbeJar`에만 포함한다. 제품 `bootJar`·이미지에 프로브·검증 메시지·Kafka 클라이언트를 포함하지 않는다. 공통 봉투의 파서·검증은 main의 인프라 패키지에 두되 앱 기동 시 Kafka 연결·토픽 생성·리스너 등록은 하지 않는다. Spring Kafka 및 제품용 producer/listener adapter의 선택·적용은 012~013에서 다룬다.

| 대상 | 추천 설정·판정 |
|---|---|
| producer | `StringSerializer` key + `ByteArraySerializer` value, `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, `retries=2147483647` |
| 발행 제한 | `request.timeout.ms=10000`, `delivery.timeout.ms=30000`, `max.block.ms=10000`, `linger.ms=0`; 메타데이터/버퍼 대기와 전송 완료의 제한을 구분 |
| 발행 성공 | `send()` 반환만으로 성공 판정하지 않고 future의 broker 확인을 기다려 topic·partition·offset을 기록. 전송 중 타임아웃은 미저장 확정으로 취급하지 않음 |
| consumer | `StringDeserializer` key + `ByteArrayDeserializer` value, `enable.auto.commit=false`, `allow.auto.create.topics=false`, `auto.offset.reset=earliest` |
| 소비 방식 | 검증 도구는 record를 순차 검증. `commitSync`에 파티션별 마지막 연속 성공 record의 `offset+1`을 명시. poll로 받은 마지막 위치를 일괄 커밋하지 않음 |
| 소비 실패 | 잘못된 record의 원본 바이트·topic/partition/offset·오류 분류를 유지하고 그 뒤 위치를 커밋하지 않음. 검증 프로세스는 실패 종료하고 같은 그룹 재시작 시 다시 수신하는지 확인 |
| 제한·정리 | 개별 전송·poll·전체 검증에 유한 제한시간 부여, 종료 시 producer/consumer/admin close. 성공·실패 모두 진단과 정리 결과 기록 |

프로듀서 멱등성은 애플리케이션이 같은 메시지를 다시 보내는 중복을 제거하지 않는다. 검증 도구는 중복 record의 존재를 확인할 뿐, 업무 중복 방지·DB 커밋·소비 기록·Outbox 내구성을 구현하거나 보장하지 않는다. 제품 소비자는 013에서 **DB 커밋 뒤** 오프셋을 반영하도록 연결한다. [프로듀서 설정](https://kafka.apache.org/41/configuration/producer-configs/) · [소비자 설정](https://kafka.apache.org/41/configuration/consumer-configs/) · [오프셋·수동 커밋 API](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)

`scripts/check-kafka.py`는 기존 검사 스크립트처럼 Python 표준 라이브러리와 하위 프로세스만으로 실행을 조정하고, Kafka 프로토콜은 Java 프로브가 담당한다. 실행별 `verify.<runId>.*` 토픽과 `verify.<runId>.*` 그룹을 만들며 이름은 도구 내부에서 생성한다. 업무 토픽은 메타데이터·설정만 조회한다. 검증 토픽에서 승인·중단·환불·결과·알림 봉투 표본을 왕복하고, 같은 키의 파티션·순서, 다른 그룹의 독립 수신과 같은 그룹의 분배를 확인한다.

Windows에서는 검증 JAR로 HOST 리스너를 확인한다. Compose 내부에서는 기존 공동구매 이미지의 Java를 이용한 일회성 `docker compose run --rm --no-deps`에 검증 JAR를 읽기 전용 마운트해 INTERNAL 리스너를 확인한다. 제품 앱 프로세스나 별도 테스트 HTTP 엔드포인트를 띄우지 않는다. 정리는 해당 실행이 생성한 토픽·그룹만 대상으로 하며, 보존 검증은 재시작을 마친 뒤 정리한다.

<a id="files"></a>

## 변경 예정 위치

| 위치 | 예정 변경 |
|---|---|
| `compose.yml` | Kafka·kafka-init·전용 볼륨·포트·healthcheck·초기화 의존 추가 |
| `infrastructure/kafka/` | 토픽 목록·고정 설정과 반복 실행 가능한 초기화/검사 스크립트. Windows CRLF의 셸 실행 문제도 확인 |
| `services/group-purchase-service/build.gradle` | kafkaProbe source set·전용 JAR task, Boot BOM 기반 client 및 `org.testcontainers:testcontainers-kafka` 테스트 의존성·테스트 연결 |
| `services/group-purchase-service/src/main/java/com/example/grouppurchase/infrastructure/messaging/` | 공통 봉투·종류별 연결 규칙·코덱·계약 오류. 도메인 클래스 의존 없음 |
| `services/group-purchase-service/src/kafkaProbe/java/` | AdminClient·발행·소비·그룹·오프셋 확인용 명령행 도구 |
| `services/group-purchase-service/src/test/` | 계약 표본과 단위 테스트, Apache 이미지용 `org.testcontainers.kafka.KafkaContainer` 기반 격리 실제 브로커 테스트(실제 artifact의 클래스명으로 보정) |
| `scripts/check-kafka.py`, `.github/workflows/ci.yml` | 호스트/내부 리스너·재시작·토픽/그룹 스모크와 CI 연결, 필요 진단·종료 정리 |
| `README.md`, 관련 문서 | 결과·직접 테스트, `Docs/explanations/kafka-message-contract.md`, 인계와 계획 상태 갱신 |

테스트용 Kafka도 `apache/kafka:4.1.2`를 사용하고 호스트 포트는 Testcontainers의 임의 매핑을 따른다. 테스트는 일반 Compose·고정 9092·기존 데이터에 의존하지 않는다. Testcontainers의 Apache 전용 컨테이너를 사용하며 deprecated 컨테이너 클래스를 새로 도입하지 않는다. [Testcontainers Kafka 모듈](https://java.testcontainers.org/modules/kafka/)

## 선택 근거와 대안

| 선택 | 이유와 비용·재검토 시점 |
|---|---|
| 기존 BOM과 같은 4.1.2 JVM 이미지 | 확인한 클라이언트와 같은 버전으로 연결 범위를 좁힘. 최신 기능·다른 브로커 버전 호환성은 이번 완료 근거에 포함하지 않으며 변경 필요 시 버전 근거·영향을 다시 기록 |
| 단일 KRaft 겸용 노드 | 기존 로컬 단일 브로커 합의와 실행 비용을 따름. 운영 규모·브로커 장애 검증 단계에서는 controller 분리·다중 복제를 비교 |
| 목적별 토픽·파티션 3개 | 명령/결과 책임과 키를 분리하고 그룹 분배를 실제로 확인. 단일 파티션보다 실패 영향·키 배치가 복잡하며, 같은 파티션의 다른 시도까지 대기하는 비용은 013에서 검증 |
| JSON 문서·표본 기반 계약 | 기존 합의와 맞고 별도 Schema Registry가 필요 없음. 스키마 자동 강제가 없으므로 필수 조건·호환성 표본을 함께 관리 |
| 공통 모듈 없이 인프라 패키지부터 시작 | 아직 하나의 실제 적용 서비스뿐이므로 공유 의존성을 미리 만들지 않음. 두 번째 적용에서 중복·독립 배포 비용을 보고 판단 |
| 별도 Java 프로브·raw bytes 소비 | 현재 HTTP 앱 기동과 제품 경로를 유지하고 잘못된 원본도 관찰 가능. Spring Kafka container·오류 처리·DB 트랜잭션 연동은 012~013의 실제 적용에서 검증 |

<a id="guided-coding"></a>

## 핵심 코드 직접 작성 여섯 구간

구현 때 아래 실제 메서드·파일 위치를 확정해 안내한다. 동작하는 기준 구현을 먼저 검증하고 같은 파일의 참고 주석·작성 구간 여섯 개를 동시에 준비한다. 작성 대기와 완료를 구분하고, 사용자 작성 후 묶음 리뷰·재검증한다. 설정·의존성·반복 CLI 코드는 AI가 담당한다. [직접 작성 기준](../README.md#guided-coding)

| Step | 직접 작성 대상 | 확인할 선택·실패 조건 |
|---|---|---|
| LEARN-01 | 공통 봉투 검증 | 정확한 자료형·UUID·UTC 시각·양의 순번, 선택 필드와 필수 필드 구분 |
| LEARN-02 | 토픽·키 결정 및 대조 | 원승인 시도/공동구매 ID 선택, 환불 ID를 key로 쓰지 않음, 종류·실제 key 불일치 거절 |
| LEARN-03 | JSON decode·버전 분기 | 중복 키·손상 JSON 거절, 지원 버전·종류 판단, 선택 필드 추가의 호환성 |
| LEARN-04 | producer 발행 완료 판정 | broker 확인과 topic/partition/offset, 예외·타임아웃을 성공으로 처리하지 않음 |
| LEARN-05 | consumer 검증·명시적 커밋 | 연속 성공 범위의 offset+1만 커밋, 실패 record 뒤 위치를 넘기지 않음 |
| LEARN-06 | 커밋 위치·재시작 검증 | 수신 위치와 커밋 위치 구분, 미커밋 재수신·커밋 뒤 재개 위치를 실제 그룹 조회와 대조 |

<a id="verification"></a>

## 구현·검증 순서와 완료 기준

| 순서 | 실행·검증 | 통과 기준 |
|---|---|---|
| 1 | 구현 착수 상태 확인·버전 기록 | 최신 010 PR/main/head CI와 작업 브랜치 기준 확인. 기존 사용자 변경 보존. 실제 dependencyInsight 및 이미지 ID/digest는 구현 결과에 기록 |
| 2 | 계약 코덱·고정 JSON 표본 테스트 | 명령/결과/환불/알림의 필수 ID·key·순번, 정상 왕복, 선택 필드 추가, 누락·null·타입·중복 키·미지원 버전/종류·과대 본문 거절 |
| 3 | 실제 격리 Kafka 연동 | 새 토픽·설정 일치, 같은 key의 파티션·순서, 같은 그룹 두 인스턴스의 파티션 분배 및 합계 한 번 수신, 다른 그룹의 독립 전체 수신. 그룹 검증은 commit 후 정상 종료 조건에서 수행 |
| 4 | 커밋·오류·재전달 | 검증 성공 후 명시적 commit과 새 프로세스 재개, commit 전 종료 후 재수신. 정상 A→잘못된 B→정상 C에서 B를 넘기는 commit이 없고 재시작 시 B를 다시 수신 |
| 5 | 초기화 반복·볼륨 보존·통신 복구 | init 반복 실행의 무변경, 설정 불일치의 실패 보고. 검증 메시지·그룹 커밋을 저장한 뒤 restart 및 down/up(볼륨 보존)에서 유지. 브로커 중단 중 발행은 제한시간 안 실패하고 복구 뒤 왕복 성공 |
| 6 | 전체 실행·플랫폼·회귀 | 전체 Gradle build와 제품 bootJar의 probe/client 비포함 확인, 신규 Kafka 포함 Compose 일괄 기동과 7개 healthy/init 0, Windows 검증 JAR의 외부 주소·일회성 컨테이너의 내부 주소. 기존 DB·발견·Gateway/회원/참여 스모크 통과 |
| 7 | 여섯 구간 작성·재검증·기록 | 사용자 소스의 계약/통합·관련 제품/검증 JAR와 Compose 확인, 결과·직접 테스트·학습 문서·인계 갱신. 변경과 관련 없는 유효한 검증은 반복하지 않음 |

재시작·down/up은 전용 `group-purchase-011` Compose 프로젝트에서 수행하고 검증용 토픽·그룹·데이터를 사용한다. 중단 중의 전송은 저장되지 않았다고 단정하지 않으며, 같은 메시지를 재전송하면 중복 record가 생길 수 있음을 별도 확인한다. 이 검사는 012의 DB 기반 장애 복구나 013의 업무 소비 멱등성 완료를 대신하지 않는다.

CI의 Build and test에는 계약·실제 Kafka 통합 테스트를 포함하고, Images and Compose smoke test에는 검증 JAR 준비·Kafka 초기화 확인·양쪽 리스너 왕복을 추가한다. 전체 down/up·브로커 중단 복구는 로컬 전용 프로젝트에서 수행하고 CI 필수 스모크와 구분한다. 기존 job 제한시간은 실측 후 필요한 경우에만 조정한다. PR/push 후 CI는 별도 대기하지 않고 링크·최종 head·당시 상태를 안내한다.

<a id="manual-tests"></a>

## 결과에 제공할 사용자 직접 테스트

아래는 계획 당시의 예정 명령이다. 실제 실행 근거와 현재 도구에 맞춘 복사 가능한 절차는 [구현 결과의 직접 테스트](../011-kafka-message-contract.md#manual-tests)를 따른다. 현재 여섯 구간의 사용자 작성·리뷰·재검증을 마쳤으며 활성 코드로 실행할 수 있다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
.\scripts\prepare-local-secrets.ps1
.\gradlew.bat :group-purchase-service:test :group-purchase-service:kafkaProbeJar --console=plain
docker compose -p group-purchase-011 config --quiet
docker compose -p group-purchase-011 up --build -d --wait --wait-timeout 240
python scripts/check-kafka.py --project group-purchase-011
docker compose -p group-purchase-011 down
```

- 준비는 Docker Desktop Linux 엔진·Java 21/`JAVA_HOME`·Python 3과 기존 앱/DB 포트 및 Kafka 9092다. 기존 secret 준비 스크립트의 동작을 유지하고 비밀값을 출력하지 않는다. 다른 프로젝트와 포트가 겹치면 종료하거나 Kafka 포트 재정의 방법을 안내한다.
- 기본 검사는 업무 토픽 설정·검증 토픽의 정상 왕복·키/순서·그룹/커밋·양쪽 리스너를 확인한다. 잘못된 JSON·버전·키의 기대 오류와 미커밋 재수신도 재현할 수 있게 제공한다.
- 브로커 중단·restart·down/up 보존 검사는 별도 명령·사전 데이터·프로세스 종료 지점·복구 순서로 제공한다. 기본 스모크 실행이 기존 사용자의 브로커를 임의로 중단하지 않게 한다.
- `down`은 볼륨을 보존한다. 검증 토픽/그룹만 정리하는 명령과 실패 후 복구를 제공하며, 전용 프로젝트 볼륨 전체 삭제는 PostgreSQL·Kafka 검증 데이터도 함께 지운다는 영향 범위를 표시해 분리한다.
- 실제로 실행한 명령·관찰한 결과와 사용자 재현용 미실행 절차를 구분한다. 이번 단계에서는 외부 HTTP 경계가 달라지지 않으며, 결과에는 Kafka 발행 확인·소비 검증·커밋 순서를 보여 주는 메시지 흐름을 기록한다.

## 구현 세션 인계

2026-11-24 기준 구현·검증을 마치고 [직접 작성 여섯 구간](../011-kafka-message-contract.md#guided-coding)을 준비했다. 이후 사용자 일괄 작성·묶음 리뷰·관련 재검증과 임시 표식 정리까지 마쳤다. [최종 결과](../011-kafka-message-contract.md#final-results). 이후 012는 현재 계약을 사용하는 Outbox의 업무 원자성·순번·선점·발행 완료·재시도를, 013은 서비스 DB의 소비 기록과 업무 커밋·오프셋·DLT/보류를 담당한다. 업무별 최종 payload와 PG·결제·Saga·알림은 각각의 후속 계획에서 확정한다.

[구현 안내](../README.md#workflow) · [전체 로드맵](roadmap.md) · [인계](../../handoff.md)
