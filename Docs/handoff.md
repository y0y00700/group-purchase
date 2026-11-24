# 공동구매 프로젝트 인계

갱신일 2026-11-24 KST

- 현재 **001~011 로컬 구현·검증 완료**. 010 [최종 결과](implementation/010-participation.md#final-results), 011 [사용자 코드 최종 결과](implementation/011-kafka-message-contract.md#final-results)를 따른다.
- 최신 병합은 009 [PR #11](https://github.com/y0y00700/group-purchase/pull/11), 최종 head `c445912`의 두 CI 성공 기록은 유지한다. 010 [요청 흐름·직접 테스트](implementation/010-participation.md#manual-tests)는 최종 결과에서 확인한다.
- **011 여섯 구간 사용자 작성·묶음 리뷰·관련 재검증 완료**다. 기능 오류 없이 계약/실제 Kafka 12개, 제품/probe JAR 분리, 이미지 재빌드·7 healthy/init 0, Windows HOST/INTERNAL 및 프로세스 커밋/재시작 22개 검사를 통과했다. 참고 주석·임시 표식은 제거했고 사용자 실행 토큰 보존을 확인했다. [계획](implementation/plan/011-kafka-message-contract.md#execution-summary) · [최종 근거·직접 테스트](implementation/011-kafka-message-contract.md#final-results)
- 기준 구현의 전체 build 99개 중 관련 12개를 다시 실행하고 변경 없는 87개는 기존 유효 결과를 재사용했다. 전체 down/up·broker 장애/복구·기존 DB/HTTP/Windows 제품 JAR 회귀도 관련 경계가 그대로여서 기준 구현 근거를 재사용했다. 현재 소스에 작성 대기 예외는 없다.
- 011의 작성·리뷰·필수 로컬 검증·결과/학습 기록을 마쳤다. 012 Outbox는 미착수이며 다음 진행 요청 시 별도 계획을 검토한다. 010 코드는 보존했다.
- 현재 브랜치 `feat/kafka-message-contract`, HEAD `d411960f430062fe53280bef1be5514e016d1daf`(010에서 분기). 기존 사용자 문서 변경을 보존했고 011 변경은 미커밋이다. PR 생성·push는 하지 않았다.
- 2026-11-24 착수 원격 조회: 010 PR 및 head CI 목록 없음. 원격 main `5e5c26eab4459448d312501f859f3fd528741ec3`, [최신 main CI](https://github.com/y0y00700/group-purchase/actions/runs/44251718781) completed/success. 이후 PR 준비 시 010 선행 병합 또는 base 분리를 확인한다. 011 원격 CI는 미실행/미확인이다.
- [Kafka 적용 설명](explanations/kafka-message-contract.md)과 [실제 로그 관찰 학습 기록](learning/questions/kafka-011-observe-message-flow.md)을 갱신했다. 로컬 학습 폴더의 Git 제외 정책을 유지했다.
- 전용 `group-purchase-011`은 검증 토픽·그룹 정리 뒤 `down`으로 종료했으며 PostgreSQL/Kafka 볼륨은 보존했다. 다른 프로젝트/사용자 데이터는 중단·삭제하지 않았다.
