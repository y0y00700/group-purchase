# 목표 달성형 공동구매 문서

<a id="project-goal"></a>

## 목표와 현재 상태

판매자가 상품과 기준 수량을 등록하고, 참여 모집 후 기한 안의 유효 결제가 기준 수량을 채우면 구매를 확정하는 서비스다. 미달로 무산되면 취소·환불한다. 마감 경합·장시간 업무·보상 복구를 구현·검증할 수 있어 한정 수량 주문·좌석 예약·B2B 발주 후보를 비교한 뒤 이 주제를 선택했다.

취업용 개인 프로젝트로, 구현 → 정합성·장애·부하 검증 → 관측·분석 → 대안·트레이드오프 비교 → 설계·구현 변경 → 같은 조건 재검증까지 근거를 남기는 것이 목표다.

- 현재 구현: Java 21·Spring Boot 4.0.9·Cloud 2025.1.4·Gradle Wrapper 9.8.2의 멀티 프로젝트에 Config Server와 공동구매 초기 웹 앱을 둔다. 중앙 YAML 설정 수신과 두 서비스의 Compose 기동을 구현했다.
- 최근 완료: [002: Config Server와 첫 클라이언트 연동 결과](implementation/002-config-server.md). 자동 테스트 13개와 로컬 이미지·Compose·장애/복구를 검증했다. 2026-11-02 [PR #2](https://github.com/y0y00700/group-purchase/pull/2)를 생성했고 구현 커밋의 GitHub CI 두 검사가 성공했다. [사용자 직접 테스트](implementation/002-config-server.md#manual-tests)도 포함한다.
- 다음 계획: Eureka Server의 상세 계획 작성·검토. 후속 서비스도 각 단계의 이미지·Compose 정의를 누적해 전체 일괄 기동 구성을 확장한다.
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

- [프로젝트 인계](handoff.md): PR·브랜치·검증 근거·직접 실행·다음 Eureka 단계의 시작점.
- [구현 계획과 기록](implementation/README.md): 계획 작성 → 사용자 검토 → 수정 반영 → 요청한 단계 구현·검증 → 결과 기록.
- [전체 구현 로드맵](implementation/plan/roadmap.md): 공통 구성과 도메인을 하나씩 구현하는 순서 제안.
- [설계 재검토 목록](decisions/design-review.md): R-01~R-22의 선택·재검토 조건·대안·트레이드오프와 문제 분석 양식.
- [초기 프로젝트 생성·범위 결정](implementation/000-initial-project.md): 당시 빌드·검증 결과와 ADR-0001.
- [설계 산출물](artifacts/README.md): ERD·아키텍처·인프라 도식의 작성 기준과 상태.

## 문서 관리

파일명은 영문 kebab-case로 짓고 내용은 한국어로 작성한다. 안내는 이 문서와 구현 안내에 모으고 빈 폴더용 README는 만들지 않는다. 구현 계획·결과와 결정 근거는 목적이 달라 구분해 관리한다. 실제 수행·검증한 범위만 완료로 표시한다.

협업·학습 원칙과 최신 세션 인계는 로컬 전용 `Docs/process/collaboration.md`에서 관리하고, 설명 요청이 생기면 `Docs/explanations/`에 주제별 노트를 만든다. 두 경로는 기존처럼 Git에서 제외한다. 문서 통합·이동 기록은 로컬 `Docs/process/document-migration.json`에 남긴다.
