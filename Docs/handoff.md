# 공동구매 프로젝트 인계

갱신일: 2026-11-04 KST

- 현재: **005 로컬 구현·최종 검증 완료, [후속 PR #7](https://github.com/y0y00700/group-purchase/pull/7) OPEN/main 대상**. 006은 시작하지 않았다.
- 먼저 읽기: `AGENTS.md` → 이 문서 → [005 실행 요약](implementation/plan/005-postgresql-foundation.md#execution-summary) → [최종 결과](implementation/005-postgresql-foundation.md#runtime-final-results)·[직접 테스트/복구](implementation/005-postgresql-foundation.md#runtime-manual-tests).
- 위치: `C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase`, 브랜치 `test/postgresql-runtime-recovery`를 최신 `origin/main` `3d601bd50a1c2d6bd829940b3f26202ea125d9c3`에서 생성했다. 기존 브랜치와 main의 파일 내용이 같음을 확인하고 모든 미커밋 구현·사용자 규칙 변경을 보존했다. merge/rebase 없음.
- 원격 최신 확인: [PR #6](https://github.com/y0y00700/group-purchase/pull/6) MERGED/base main, [기존 head CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/41572899963), main `3d601bd50a1c2d6bd829940b3f26202ea125d9c3`의 [CI 성공](https://github.com/y0y00700/group-purchase/actions/runs/41585524931). 새 변경은 `9ed94ed`로 커밋·push했다. 정확한 최종 head와 새 변경의 CI는 [PR #7 Checks](https://github.com/y0y00700/group-purchase/pull/7/checks)를 기준으로 한다.
- 새 구현: 제품과 분리된 opt-in `runtimeProbeJar` 두 개와 `scripts/check-runtime-recovery.py`. 앱 DataSource/context·각 DiscoveryClient·Gateway supplier를 관찰하며 일반 제품 JAR에는 관찰 코드가 없다.
- 실제 검증: DB 중단 120.033초, 같은 앱 PID/context health 503→200·새 물리 SQL 연결·Eureka DOWN→UP·각 캐시/전달 수렴. 일반 Windows 제품 JAR 두 개도 local 환경변수·Flyway·등록/전달 통과.
- 전체 down/up: probe·Flyway 전체 행·볼륨 inspect 불변, 전후 계정 접근 검사 통과. 전체 실행 465.084초/종료 0. 근거 `.tmp/005-final-runtime.{txt,json}` 및 final probe/product 로그.
- 회귀: 기존 공동구매 22개·전체 build/격리 오류 migration·초기화·비노출 근거 재사용. 최종 루트 Gradle 포장 보완 후 build/관찰 JAR 4초·33 task UP-TO-DATE, 두 Docker 이미지 빌드·실행/DB·발견·Gateway 재확인 통과. 제품 JAR 관찰 코드 미포함·Python 문법/형식 검사 통과.
- 종료 상태: local 검사 PID·probe 제거, 부모 환경/secret 다섯 파일 hash 동일. 일반 `group-purchase_postgres-data` 유지, 다섯 Compose 서비스 healthy·docker 발견/전달 정상으로 복구.
- 경계: 자동 clean/repair·이력 변조·일반 `down -v` 금지. 오류 SQL/checksum은 기존 격리 테스트에서만 검증한다. ping은 DB를 사용하지 않으며 장애 중 Gateway 특정 코드 보장 없음.
- 실행: Docker Desktop Linux 엔진·JDK 21 JAVA_HOME·Python 3 필요. 이 환경에서는 Docker/네트워크/Gradle 실행에 권한 있는 명령이 필요했다. 재실행은 위 직접 테스트 문서를 따른다.
- 학습: [PostgreSQL·Flyway 역할과 복구](explanations/postgresql-flyway.md) 생성(기존 정책대로 Git 제외).
- 다음: [후속 PR 기록](implementation/005-postgresql-foundation.md#runtime-pr)과 PR #7의 최종 head Checks를 기준으로 검토한다. 사용자 요청으로 커밋·push·PR을 진행했으며 PR 병합·006 구현은 요청 범위 밖이다.
