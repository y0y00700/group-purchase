# 목표 달성형 공동구매 문서

[전체 진행 정리 · 2026-11-03](progress-summary-2026-11-03.md): 목표·업무 규칙·설계 결정·001~004 결과·검증 이력·현재 상태와 005의 스텝별 진행 순서.

<a id="project-goal"></a>

## 목표와 현재 상태

- 이전 완료: [011 Kafka 결과·직접 작성·테스트](implementation/011-kafka-message-contract.md). 기준 구현 검증 후 여섯 구간의 **사용자 작성·묶음 리뷰·로컬 재검증을 완료**했다. [실제 설정·코드 역할](explanations/kafka-message-contract.md) · [메시지/커밋 로그 관찰](learning/questions/kafka-011-observe-message-flow.md). 최신 브랜치·PR/CI는 [인계](handoff.md)를 따른다.
- 최근 완료: [012 Outbox 발행](implementation/plan/012-outbox-publishing.md#execution-summary), 2026-11-25 사용자 작성·묶음 리뷰·관련 재검증·임시 표식 정리 완료. [최종 결과·직접 테스트](implementation/012-outbox-publishing.md#final-results) · [실제 적용 설명](explanations/outbox-publishing.md).
- 최근 완료: [013 메시지 소비 안정성](implementation/plan/013-message-consumption-reliability.md#execution-summary), 2026-11-26 사용자 요청으로 직접 작성 생략·활성 코드 복원·관련 재검증·임시 주석 정리 완료. [최종 결과·직접 테스트](implementation/013-message-consumption-reliability.md#final-results) · [적용 설명](explanations/message-consumption-reliability.md). 제품 소비는 기본 비활성, 실제 결제/Saga 016·운영 복구 API 018 경계를 유지한다. PR #14 병합을 확인했으며 최신 CI는 [인계](handoff.md)를 따른다.
- 현재 구현: [014 PG Mock](implementation/plan/014-pg-mock.md#execution-summary), **로컬 구현·핵심 6개 사용자 작성·묶음 리뷰/재검증·임시 표식 정리 완료**. [최종 결과·PowerShell 직접 테스트](implementation/014-pg-mock.md) · [실제 적용 설명](explanations/pg-mock.md). 원격 CI는 [인계](handoff.md)와 PR Checks에서 구분한다.

판매자가 상품과 기준 수량을 등록하고, 참여 모집 후 기한 안의 유효 결제가 기준 수량을 채우면 구매를 확정하는 서비스다. 미달로 무산되면 취소·환불한다. 마감 경합·장시간 업무·보상 복구를 구현·검증할 수 있어 한정 수량 주문·좌석 예약·B2B 발주 후보를 비교한 뒤 이 주제를 선택했다.

취업용 개인 프로젝트로, 구현 → 정합성·장애·부하 검증 → 관측·분석 → 대안·트레이드오프 비교 → 설계·구현 변경 → 같은 조건 재검증까지 근거를 남기는 것이 목표다.

- 현재 구현: Java 21·Spring Boot 4.0.9·Cloud 2025.1.4·Gradle Wrapper 9.8.2의 멀티 프로젝트에 Config Server·Eureka Server·API Gateway·공동구매 초기 웹 앱을 둔다. 중앙 설정 수신·서비스 등록/발견·Gateway GET 전달과 네 서비스의 Compose 기동을 구현했다.
- 최근 완료: [003: Eureka Server 구현·직접 테스트](implementation/003-eureka-server.md). 자동 테스트 총 23개, 이미지·Compose·등록/발견·장애/복구·Windows JAR local 연동을 검증했다. 2026-11-02 [PR #3](https://github.com/y0y00700/group-purchase/pull/3) 병합과 최종 head·병합 main의 CI 두 검사 성공을 확인했다. 최신 인계는 [handoff.md](handoff.md)를 따른다.
- 최근 구현: [005 PostgreSQL 실행 기반](implementation/plan/005-postgresql-foundation.md)의 로컬 구현·최종 검증을 완료했다. [최종 결과](implementation/005-postgresql-foundation.md#runtime-final-results)와 [직접 테스트·복구](implementation/005-postgresql-foundation.md#runtime-manual-tests)에 동일 앱 DB 장애 복구·각 발견 캐시 수렴·Windows JAR·전체 down/up 근거를 기록했다. PR #6 병합 이후 추가한 검증의 [후속 PR·최종 head CI](implementation/005-postgresql-foundation.md#runtime-pr)는 결과 문서에서 관리한다. [006 API·로그·헬스 계획](implementation/plan/006-api-observability-baseline.md#execution-summary)도 여섯 Step 작성·현재 소스의 로컬 최종 검증을 완료했다. [006 최종 결과](implementation/006-api-observability-baseline.md#final-results)에서 실제 실행 근거를, 인계에서 최신 PR/CI 상태를 관리한다.
- 설계 합의는 목표 구조이며 전체 MSA 실행·업무 기능·성능 측정의 완료를 뜻하지 않는다. ERD·SA·인프라 도식은 작성 대기다.

<a id="design"></a>

## 설계

| 문서 | 책임 범위 |
|---|---|
| [business-rules.md](design/business-rules.md) | MVP 요구사항, 수량·기한, 공동구매·참여 상태, 취소·재참여, 상품 수정·게시 |
| [architecture.md](design/architecture.md) | 기술 선택, 서비스 책임·소유권, 외부 API·멱등성, 세션·CSRF·권한·내부 신뢰 |
| [payment-and-messaging.md](design/payment-and-messaging.md) | Saga, 수량 확보, 취소·마감 경합, Outbox·소비·DLT·운영 복구, 데이터·트랜잭션·메시지 계약 |
| [operations.md](design/operations.md) | Gradle·YAML·Compose, DB·Kafka 물리 구성, 초기 운영값·비밀값·보관·HTTP 장애 대응·로그·메트릭·운영 알림 |
| [verification.md](design/verification.md) | 기능·정합성·권한·장애 시나리오, k6 부하·초기 목표, 검증 근거·완료 기준 |

각 주제의 상세 정책은 위 문서 한곳에서 관리한다. 개요·인계에 같은 합의를 반복해서 복사하지 않고 관련 절을 연결한다. 구체적인 테이블·쿼리·필드·설정값은 해당 구현 계획에서 정한다.

## 계획·결과·선택 근거

- [프로젝트 인계](handoff.md): PR·브랜치·검증 근거·직접 실행·다음 PostgreSQL 단계의 시작점.
- [구현 계획과 기록](implementation/README.md): 계획 작성 → 사용자 검토 → 수정 반영 → 요청한 단계 구현·검증 → 결과 기록. [설계·구현 분리와 토큰 관리](implementation/README.md#context-workflow)의 공통 기준도 이 문서에서 관리한다.
- [전체 구현 로드맵](implementation/plan/roadmap.md): 공통 구성과 도메인을 하나씩 구현하는 순서 제안.
- [설계 재검토 목록](decisions/design-review.md): R-01~R-23의 선택·재검토 조건·대안·트레이드오프와 문제 분석 양식.
- [초기 프로젝트 생성·범위 결정](implementation/000-initial-project.md): 당시 빌드·검증 결과와 ADR-0001.
- [설계 산출물](artifacts/README.md): ERD·아키텍처·인프라 도식의 작성 기준과 상태.

## 문서 관리

파일명은 영문 kebab-case로 짓고 내용은 한국어로 작성한다. 안내는 이 문서와 구현 안내에 모으고 빈 폴더용 README는 만들지 않는다. 구현 계획·결과와 결정 근거는 목적이 달라 구분해 관리한다. 실제 수행·검증한 범위만 완료로 표시한다.

협업·학습 원칙은 로컬 전용 `Docs/process/collaboration.md`에서, 최신 세션 인계는 `Docs/handoff.md`에서 관리한다. 질문과 날짜별 학습 기록은 `Docs/learning/`에, 구현한 기술의 역할·적용 요약은 `Docs/explanations/`에 저장한다. `Docs/process/`·`Docs/learning/`·`Docs/explanations/`의 기존 Git 제외 정책을 유지한다. 문서 구조 변경·이동 기록은 로컬 `Docs/process/document-migration.json`에 남긴다.

## 질문과 날짜별 학습 기록

[학습 안내](learning/README.md)에서 날짜별 기록과 최상위 질문을 찾는다. `learning/daily/YYYY-MM-DD.md`는 그날 학습한 질문의 링크를 모으고, `learning/questions/`는 질문별 답변·상위 질문·후속 질문을 관리한다. 날짜가 달라도 후속 질문을 연결하고, 복습할 때는 같은 질문 문서를 다시 연결한다. 새 질문을 기록하는 상세 순서와 양식은 학습 안내를 따른다.

## 메서드 코드 해설

[2026-11-04 작성한 006 코드 해설](learning/006-api-observability.md)은 ID 메서드와 STEP-01~06의 실행 순서·코드 선택 이유·실패 예시·테스트를 한 파일에 모았다. `Docs/learning/`은 기존 Git 제외 정책을 유지하는 로컬 학습 디렉터리다.

[006→007 Spring Security·세션 흐름](learning/007-spring-security-session-flow.md)은 요청 추적/오류 처리에서 세션 인증으로 이어지는 학습 문서다. 쿠키·CSRF·컨텍스트·필터 기본 개념, 로그인/조회/내부 확인/로그아웃 흐름, 두 단계의 직접 작성 구간과 현재 007 여섯 메서드를 설명한다. 2026-11-05 현재 DDD 소스 기준이며 이번 문서 작성에서 실행 검증은 추가하지 않았다.

- 현재 구현: [007 회원·세션 인증 결과·직접 작성/테스트](implementation/007-member-session-authentication.md). DDD 구조·여섯 Step 사용자 작성·리뷰·현재 소스 재검증 완료다.
