# 공동구매 프로젝트 인계

갱신일 2026-11-28 KST

- **001~014 로컬 구현·검증 완료**. 014는 핵심 6개 사용자 작성·묶음 리뷰/재검증·임시 표식 정리를 마쳤다. 013 직접 작성은 사용자 요청으로 생략했으며 사용자 작성 완료로 기록하지 않는다.
- [014 최종 결과·검증](implementation/014-pg-mock.md#final-results) · [현재 소스 PowerShell 테스트·종료/복구](implementation/014-pg-mock.md#manual-tests) · [014 계획](implementation/plan/014-pg-mock.md#execution-summary) · [PG 적용 설명](explanations/pg-mock.md).
- PR 착수 때 최신 main `8eb3ac8e759ae583c66dcf063cced32422bd9ff8`, PR #14 merged와 [main CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/46041909583)을 다시 확인했다. 014 브랜치의 기존 원격 PR/브랜치는 없었다. 기준 main 변경/재병합은 필요 없었다.
- 브랜치는 **feat/pg-mock**, PR 대상은 main이다. 사용자의 PR 게시 요청을 반영한다. [014 브랜치 PR](https://github.com/y0y00700/group-purchase/pulls?q=is%3Apr+head%3Afeat%2Fpg-mock) · [브랜치 CI](https://github.com/y0y00700/group-purchase/actions?query=branch%3Afeat%2Fpg-mock). 게시 후 최종 head/직접 PR·Checks 링크와 조회 당시 상태는 최종 응답으로 안내하고 CI 완료를 기다리거나 반복 조회하지 않는다.
- 사용자 6개 실행 본문에 오류가 없었다. 원본을 `.tmp/014-user-review/user-source/`에 보존했고 실행 코드를 유지한 채 임시 주석/표식·주석 처리한 예외·들여쓰기만 정리했다. 정리한 6개 파일은 기준과 내용이 완전히 같다.
- 전체 build 통과, PG 25개 캐시 없는 새 실행·실패/오류/skip 0, Compose HOST 6묶음(실제 승인/환불 응답 유실 포함) 통과. 유효 전체 161개이며 변경 없는 기존 136개 결과를 재사용한다. PG 소스/도구·제품/probe JAR가 기준과 같아 수명 14묶음·Windows·기존 회귀 근거를 재사용했다.
- 이번 리뷰/검증/해시 근거는 `.tmp/014-user-review/`, 최초 기준 소스/JAR/진단은 `.tmp/014-baseline/`에 보존했다. 기존 미커밋 계획·인계·목록 변경도 포함한다. 학습/설명 폴더 Git 제외 정책을 유지한다.
- 전용 **group-purchase-014는 down 종료**, PG/업무 DB·Kafka named volume 3개와 기존 013/014 진단을 보존했다. 일반 프로젝트·DB·토픽·볼륨은 초기화하지 않았다.
- 독립 PG/전용 DB·자체 YAML/고정 URL·영속 원장/복구·사전 조건/실제 유실을 제공한다. PostgreSQL 예약 역할명 때문에 계정만 `mock_pg_app`이며 업무/API 계약은 유지한다.
- 제품 Inbox 기본 비활성·실제 핸들러 없는 활성화 실패를 유지한다. 결제 결과 불명 추적은 015, Saga/기한은 016, 운영 복구 API는 018이다.
- 다음 행동: 사용자가 014 PR/CI 결과를 확인한다. 오류를 알려주면 해당 범위에서 수정한다. 015 결제 도메인은 상세 계획과 별도 진행 요청을 따른다.
