# 공동구매 프로젝트 인계

갱신일: 2026-11-05 KST (006→007 세션 학습 문서 추가. 이번 문서 작업에서 코드 변경·실행/원격 재조회 없음)

- 현재: **001~007 로컬 구현·검증 완료. 007 여섯 Step 사용자 작성·묶음 리뷰·최종 재검증 완료**.
- 먼저 읽기: `AGENTS.md` → 이 문서 → [007 실행 요약](implementation/plan/007-member-session-authentication.md#execution-summary). 실제 변경·검증·직접 테스트·작성 위치는 [007 결과](implementation/007-member-session-authentication.md)가 소유한다.
- 사용자 합의: 1회 검토를 반영한 추천안 채택. Gateway 인증 연결·라우팅·secret 주입·신원 헤더 제거는 **008**로 유지했다.
- 추가 합의: [DDD 패키지·의존 방향](design/architecture.md#package-structure) 채택. 회원 서비스의 실제 배치·책임 분리·학습 보존·호환성/회귀 기준은 [007 재구성 계획](implementation/plan/007-member-session-authentication.md#ddd-packages)이 소유한다. 소스 분리·이동을 반영했고 별도 사본에서 회귀·의존 방향·이전 JAR 세션 호환성을 검증했다.
- 작업 브랜치: `feat/member-session-authentication`, 기준 HEAD `1def2d5157204309f80313c7862ccbfad987661e` (006 포함). 커밋·push·새 PR·병합 없음.
- 최신 원격(재구성 착수 시 2026-11-05): [PR #8](https://github.com/y0y00700/group-purchase/pull/8) MERGED·기존 head CI 성공. main `d5d0396474797ac734caa95b1f1805aaa4f6132b` [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/41989123469). 현재 HEAD와 main은 병합 커밋1개·파일 차이0개라 기존 작업 기준을 유지했다.
- 최종 검증: 현재 실제 사용자 소스의 전체 build·64개 결과(회원14개 재실행·변경 없는 기존50개 유효 결과 재사용), 실패/오류/skip0. PostgreSQL·두 인스턴스·회전/만료/현재 권한/CSRF/경합/저장 실패/동일 앱 DB 복구·이전 JAR 세션 호환 통과. 최종 사용자 제품 JAR·회원 이미지/여섯 Compose·Windows 두 프로세스·로그 비노출 확인. [최종 결과](implementation/007-member-session-authentication.md#final-results) 참조.
- 작성 결과: 01인증·02세션 연결·03현재 회원·04호출자·05CSRF·06응답 경계 모두 사용자 작성·리뷰/재검증 완료. 핵심 수정 사항 없이 실행 코드를 보존하고 중복 참고/LEARN/임시 예외만 정리했다.
- 다음:008 Gateway 인증 연결은 아직 미구현이며 별도 요청을 따른다. 007 커밋·push·PR·병합도 별도 요청이 필요하다.
- 학습: [006→007 Security·세션 흐름/직접 작성 해설](learning/007-spring-security-session-flow.md) 추가, 기존 [006 해설](learning/006-api-observability.md)·[회원 역할 요약](explanations/member-session-authentication.md)과 연결했다. 현재 DDD 활성 코드·사용자 작성 완료 기준. 기존 Git 제외 정책 유지.
- 보존: 착수 전 미커밋 문서·기존 사용자/006 코드·일반 DB 볼륨·기존 secret 유지. 회원 schema/V1과 신규 내부 전용 secret만 추가했다. DB 초기화 재실행·볼륨 삭제·Flyway clean/repair 없음.
- 실행 후: 격리 DB·임의 probe 회원/세션·자식 JAR 정리. 검증용 Compose 서비스는 stop, 일반 볼륨/secret 유지. Docker Desktop 엔진은 켜져 있다. 최종 사용자 소스/제품 재검증을 마쳤으며 placeholder 없음. 과거 참고 사본 근거와 최종 결과를 구분한다.
- 커밋·push·PR·병합은 별도 요청을 따른다. PR/push 뒤 CI 완료 대기·반복 조회를 하지 않는 기존 규칙 유지.
