# 공동구매 프로젝트 인계

갱신일 2026-11-25 KST

- **001~012 로컬 구현·사용자 작성·검증 완료**를 유지한다. [010 최종 결과](implementation/010-participation.md#final-results) · [011 사용자 코드 최종 결과](implementation/011-kafka-message-contract.md#final-results)
- **012 사용자 작성·묶음 리뷰·관련 재검증·임시 표식 정리 완료**다. [계획](implementation/plan/012-outbox-publishing.md#execution-summary) · [사용자 코드 최종 결과·직접 테스트](implementation/012-outbox-publishing.md#final-results)
- 최신 병합은 [PR #12](https://github.com/y0y00700/group-purchase/pull/12)(010·011)다. 착수 조회에서 head `2e4bd22`의 [CI](https://github.com/y0y00700/group-purchase/actions/runs/45414427720) 및 main `85f7be7`의 [두 CI](https://github.com/y0y00700/group-purchase/actions/runs/45431750385) 성공을 확인했다. 과거 PR #11/011 근거도 기존 결과 문서에 유지한다.
- 현재 `feat/outbox-publishing`, 기준/HEAD `85f7be78a4c2391354c55489dc62cba30fc865e4`. 최신 origin/main에서 생성했으며 기존 미커밋 계획·안내 문서와 학습 기록을 보존했다. 012 변경은 미커밋이고 push·PR 생성은 하지 않았다.
- 업무·Outbox 저장 원자성, 스트림 순번, 선점·재선점·만료 이력, DB 트랜잭션 밖 Kafka 원본 전송, 조건부 완료/실패, 영속 재시도·중단과 전용 스케줄러를 구현했다. 기존 참여 API는 유지하고 원자성은 격리 테스트의 조합 서비스로 확인했다.
- 기준 구현 전체 build 성공: 유효 114개·실패/오류/skip 0. 공동구매 71개 새 실행, 변경 없는 43개 결과 재사용. Outbox 13개·실패 정책 2개와 보강 후 실제 프로세스 검사 재실행까지 통과했다.
- 제품/probe JAR 경계, 다섯 이미지·7 healthy/init 0, 제품 발행·브로커/DB 복구·Windows 제품 JAR·전체 down/up, Compose/Windows HTTP 회귀, Kafka HOST/INTERNAL 22개 검사를 통과했다. 상세 명령·해시·로그는 012 결과가 소유한다.
- 사용자 여섯 구간은 기준과 실행 행이 일치했고 오류 없이 관련 15개를 새 실행했다. 나머지 99개는 변경 없는 기존 유효 근거를 재사용했다. 임시 주석 30줄만 제거했으며 정리 전후 실행 텍스트가 동일하다. 현재 작성 대기 예외는 없다. 원문·대조 근거는 `.tmp/012-user-review/`에 보존했다.
- 정리한 사용자 소스로 JAR 경계 검사·이미지 재빌드·7 healthy/init 0·제품 스케줄러 정상 발행과 원본/시도 이력을 확인했다. 012의 남은 작성 작업은 없으며 소비 안정성·실제 결제/Saga·운영 중단 해제는 후속 요청 범위다.
- [Outbox 적용 설명](explanations/outbox-publishing.md)을 추가했다. [Kafka 설명](explanations/kafka-message-contract.md)·[기존 로그 관찰 학습 기록](learning/questions/kafka-011-observe-message-flow.md)은 보존하고 로컬 학습 폴더 Git 제외 정책을 유지했다.
- 전용 `group-purchase-012`는 검증 토픽/그룹 정리 후 `down`으로 종료했다. PostgreSQL/Kafka 볼륨 두 개와 진단·기준 소스/JAR를 보존했다. 기존 `group-purchase-011` 종료·볼륨 보존 기록은 유지하며 다른 프로젝트/사용자 데이터는 중단·삭제하지 않았다.
