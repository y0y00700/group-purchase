# 공동구매 프로젝트 인계

갱신일 2026-11-26 KST

- **001~012 로컬 구현·사용자 작성·검증 완료**, **013 로컬 구현·검증·임시 표식 정리 완료**. 이번 013의 직접 작성은 사용자 요청으로 생략했다. [최종 결과·직접 테스트](implementation/013-message-consumption-reliability.md#final-results) · [계획](implementation/plan/013-message-consumption-reliability.md#execution-summary).
- 사용자 요청으로 검증한 여섯 본문을 복원하고 참고/흐름 주석·LEARN 표식·작성 대기 예외를 제거했다. 다섯 파일 내용과 제품/inbox probe/Outbox probe JAR 해시가 기준과 같다. 사용자 작성 완료로 기록하지 않는다.
- 정리한 활성 코드의 관련 22개를 캐시 없이 새 실행해 실패/오류/skip 0, JAR 격리 통과. 전체 유효 136개·Kafka 22개·제품 Outbox·Compose/Windows HTTP·소비 수명 28개는 동일 코드/JAR의 기존 근거를 유지한다.
- PR 준비 최신 확인: [PR #13](https://github.com/y0y00700/group-purchase/pull/13) 병합, main `356bcd5005ac984327abd98881dcc4aa7d03f435` [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/45837640728), 이전 PR head CI도 성공. 기존 기준과 main의 트리가 같아 `feat/message-consumption-reliability`를 main으로 fast-forward했다.
- 사용자 요청으로 구현 커밋 `24853ed`를 push하고 main 대상 [PR #14](https://github.com/y0y00700/group-purchase/pull/14)를 생성했다. 최종 head는 [PR commits](https://github.com/y0y00700/group-purchase/pull/14/commits)를 따른다. 이 인계의 PR 기록을 추가 커밋·push한 뒤 [Checks](https://github.com/y0y00700/group-purchase/pull/14/checks)를 한 번 조회해 당시 상태만 채팅에 안내한다. 조회 전에 작성한 이 문서는 013 CI를 성공으로 표시하지 않는다. 완료 대기·반복 조회·병합은 하지 않는다.
- 미커밋 설계 문서도 포함해 보존·커밋한다. 학습/설명 폴더 Git 제외 정책과 원문·기준 소스/JAR·검증 로그 `.tmp/013-baseline/`을 유지한다. [적용 설명](explanations/message-consumption-reliability.md).
- V5 네 테이블·업무/소비/Outbox 원자성·DB 후 명시 커밋·영속 재시도·DB 선보관 DLT·독립 HALTED·재시작/재할당·보존 범위 검사 완료. 제품 소비 기본 비활성·실제 핸들러 없는 활성화 기동 실패를 유지한다. 결제/Saga 016·운영 복구 API 018 범위는 남아 있다.
- 전용 `group-purchase-013`은 종료·볼륨 보존 상태다. 이번 재검증은 격리 Testcontainers에서 실행했고 해당 컨테이너도 정리되었다. 다른 프로젝트·일반 DB/토픽을 중단/삭제하지 않았다.
- 다음은 014 PG Mock 상세 계획 검토다. 새 요청 전에는 후속 구현에 착수하지 않는다. 원격 CI 결과는 사용자가 확인하며 문제가 전달되면 해당 범위를 진행한다.
