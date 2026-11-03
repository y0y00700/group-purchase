# 공동구매 프로젝트 인계

갱신일: 2026-11-03 KST

- 현재: **005 DB·secret·권한 및 JDBC·Flyway 연결 스텝 완료. 005 전체는 진행 중**.
- 먼저 읽기: `AGENTS.md` → 이 문서 → [005 실행 요약](implementation/plan/005-postgresql-foundation.md#execution-summary) → [최신 설계 대조·보완 결과](implementation/005-postgresql-foundation.md#design-alignment-results)·[연결 직접 테스트](implementation/005-postgresql-foundation.md#jdbc-flyway-manual-tests). 상세 계약은 계획의 JDBC/Flyway 절에 있다.
- 위치: `C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase`. 브랜치 `feat/postgresql-foundation`, 첫 DB 스텝 커밋 `c69cf8d16a56a6b6efca72641221e709f0acebf3`. 기존 연결 구현·설계·검증 변경을 보존한 채 사용자의 후속 커밋·push·PR 요청을 진행한다.
- PR 기준 재확인: PR #4 MERGED/base main, #5 MERGED/base feat/api-gateway. main `26c04834345f1d6edfaffee37755be729fbc29f8`의 [CI 두 job 성공](https://github.com/y0y00700/group-purchase/actions/runs/41564604744). main에는 첫 DB 스텝이 아직 없어 현재 브랜치에서 main 대상 새 PR에 DB 기반·앱 연결을 함께 포함한다. 기존 HEAD의 별도 check-run/workflow는 없음. 새 head의 CI는 기존 main 성공과 구분한다. merge/rebase·브랜치 변경·PR 병합 없음.
- 구현: 공동구매만 JDBC/Flyway/드라이버, Config의 환경별 URL·풀·Flyway 정책, docker 필수 configtree와 자기 secret 하나, V1의 app 설명/PUBLIC 권한 정리. Boot/Cloud/Java/Gradle 버전 유지.
- 최신 보완: 테스트 Spring 환경의 외부 시스템 프로퍼티/환경변수 대체값 배제, V1 전체 행 단일성·성공·checksum 검사 강화, fixture 준비 실패 정리 범위 보완. 제품의 파일 출처 강제 코드는 보류 유지.
- 검증: 보완 후 실제 격리 DB의 공동구매 22개 실패 0(Config 11·Eureka 2·연결 9), 2분 44초. 기존 전체 build 36개·Compose 결과는 영향 없어 재사용했다. local/docker·SQL/V1/재기동·구분된 기동 실패·비노출 검증 유지. 새 근거 `.tmp/005-design-alignment-tests.txt`.
- Compose: 새 공동구매 이미지 적용 후 다섯 healthy. application 권한 검사·DB 재생성 후 probe/V1 이력 불변·앱 재시작 이력 불변·발견/Gateway 회귀 통과. 초기화 격리 검사는 database 단계로 통과했다.
- 데이터 경계: 일반 `group-purchase_postgres-data` 보존, 기존 secret 다섯 파일 SHA-256 불변. 일반 DB에는 의도한 app/V1 적용, 검사 probe는 정리. 오류 migration/checksum은 별도 임시 DB에서만 검증. 자동 clean/repair·이력 변조·일반 볼륨 삭제 금지.
- 재실행: Docker Desktop Linux 엔진·JDK 21/JAVA_HOME 필요. 제한 환경의 Gradle 캐시/Docker pipe 접근은 권한 있는 실행이 필요했다. 실제 암호를 argv·로그에 넣지 않는다. 근거는 Git 제외 `.tmp/005-*.txt`와 모듈별 HTML 결과.
- 다음: 장시간 DB 중단 후 **동일 앱 PID**의 health 503→200·새 SQL 연결·Eureka 수렴, Windows local JAR 전체 연동, 전체 down/up 데이터 유지, 005 최종 검증. 이번 연결 스텝을 005 전체 완료로 표시하지 않는다.
- 검사 선택: 앱 Flyway 적용 후 `python scripts/check-postgres.py --stage application`; 초기화 직후 임시 DB는 기본 database. 일반 DB에 database 검사를 실행하면 app 미생성 조건 때문에 실패한다.
- 유지할 기존 경계: 고정 Config 주소·프로필별 등록 주소·클라이언트별 발견 캐시 대기·Eureka 별도 프로세스/출력 스레드 종료. 설계 충돌이면 근거·영향·추천 대안을 알린다.
