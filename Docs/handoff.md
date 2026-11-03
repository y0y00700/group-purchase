# 공동구매 프로젝트 인계

갱신일: 2026-11-03 KST

- 현재: **005 첫 DB·secret·서비스별 DB/계정·권한 스텝 완료, 해당 범위 PR 진행**. JDBC·Flyway 연결 상세 설계는 검토 대기이며 005 전체는 진행 중이다.
- 다음 읽기: [005 실행 요약](implementation/plan/005-postgresql-foundation.md#execution-summary) → [이번 결과·직접 테스트](implementation/005-postgresql-foundation.md#manual-tests). 필요한 설계·코드만 추가 확인한다.
- 다음 검토: [JDBC·Flyway 연결 상세 설계](implementation/plan/005-postgresql-foundation.md#jdbc-flyway-step)의 설정/secret·스키마/기동·실제 DB 테스트 세 묶음. 관련 테스트 fixture·기존 Config/Eureka 회귀·DB 검사 단계/Compose CI 정의 조정을 포함한다. 상세 구현은 미착수다.
- 이후: 동일 앱 PID의 DB 중단/복구·Eureka 수렴, Windows JAR·전체 down/up·005 최종 검증·원격 CI. 현재 `app` 미생성 검사는 database/application 단계로 나눠 초기화 검사와 호환시키는 안이다.
- 작업 브랜치: `feat/postgresql-foundation`, 착수 기준 `b94dcd4`. 기존 문서 변경과 첫 스텝 코드·결과·다음 연결 설계를 함께 보존해 이번 요청으로 커밋·push·PR을 진행한다. 로컬 전용 학습/협업 기록·secret·캐시는 제외한다.
- PR 기준: Gateway PR #4가 OPEN이므로 base는 `feat/api-gateway`. #4를 먼저 병합한 뒤 최신 main과 대조해 base를 전환한다. 005 브랜치의 수동 CI로 최종 head를 확인하며 #4는 자동 병합하지 않는다.
- 원격 착수 확인: PR #4 OPEN, head `b94dcd4` [CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41437130117). main `a2a88dc` [CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41402512856). 이번 변경의 원격 CI는 미실행이다.
- 실제 검증: 자기 DB 4쌍·교차 거절 12쌍·시스템 DB 거절 8쌍·SET ROLE 거절 12쌍·오암호·SCRAM·컨테이너 재생성 영속성·격리 초기화 실패/특수문자 secret·기존 발견/Gateway 회귀 통과. 상세 근거는 결과 문서가 소유한다.
- 실행 상태: 기존 네 앱과 PostgreSQL healthy. 일반 `group-purchase_postgres-data`와 로컬 secret을 유지한다. 격리 검사 프로젝트/볼륨/secret은 정리했다.
- 경계: 앱은 아직 DB에 연결되지 않는다. init은 빈 볼륨 최초 DB/계정/권한, 후속 Flyway는 서비스 스키마/이력이다. 서버 healthy만으로 초기화 완료를 판단하지 않는다.
- 복구: secret/init 수정은 기존 볼륨에 자동 반영되지 않는다. 부분 초기화 실패 후 자동 재실행을 기대하거나 정상 볼륨을 삭제하지 않는다. [직접 테스트·복구](implementation/005-postgresql-foundation.md#manual-tests)를 따른다.
- 유지할 경계: 고정 Config 주소·환경별 등록 주소, 발견 캐시별 대기, 테스트 Eureka 별도 프로세스·출력 스레드 종료. 해당 코드 변경 시 [003 결과](implementation/003-eureka-server.md)·[004 결과](implementation/004-api-gateway.md)만 추가 확인한다.
- 진행 규칙: [설계·구현 분리와 토큰 관리](implementation/README.md#context-workflow). 이후 작업도 요청 범위의 구현·검증·기록까지 완료한다.
