# 005. PostgreSQL 기반 — DB·secret·권한 결과

검증일: 2026-11-03 KST · 상태: **첫 스텝 완료, 005 전체는 진행 중**

[계획·채택한 추천안](plan/005-postgresql-foundation.md) · [인계](../handoff.md)

## 구현 범위

단일 `postgres:18.6-bookworm`에 `member/member_app`, `group_purchase/group_purchase_app`, `payment/payment_app`, `notification/notification_app`을 만들었다. 계정은 자기 DB 소유자이며 관리자 권한·다른 역할 멤버십을 갖지 않는다. 업무 DB와 `postgres`, `template1`의 PUBLIC DB 권한 및 업무 DB의 PUBLIC `public` 스키마 권한을 회수했다. `app` 스키마·업무 테이블·Flyway 이력은 생성하지 않는다.

- `compose.yml`: loopback 기본 5432, 내부 `postgres:5432`, `postgres-data` → `/var/lib/postgresql`, 다섯 secret, 초기화 마운트와 수락 healthcheck. `POSTGRES_PORT`는 격리 검사 시 충돌 회피용이다.
- `infrastructure/postgres/init/010-service-databases.sh`: 모든 secret의 파일·비어 있음·단일 printable line 검사 후 역할/DB/권한 생성. SQL은 `ON_ERROR_STOP`, 비밀번호는 환경변수→psql `\getenv`→인용한 SQL 리터럴로 전달한다. 파일은 LF이며 공식 entrypoint가 실행 권한 없는 `.sh`를 source하는 방식도 사용한다.
- `scripts/prepare-local-secrets.ps1` / `prepare-ci-secrets.py`: Git·Docker build 제외 `secrets/*_password`에 32바이트 난수를 hex로 저장. 없는 파일만 생성하고 기존 파일의 값은 유지하며 빈/잘못된 값은 실패한다. 비밀번호를 출력하지 않는다.
- `check-postgres.py`: 별도 Compose client에서 TCP/SCRAM SQL·거절 원인·권한을 검사한다. `--persistence`는 난수 probe 스키마/테이블/토큰을 만들고 DB 컨테이너만 재생성한 뒤 읽기·해당 probe 삭제·권한 재검사를 수행한다.
- `check-postgres-initialization.py`: 실행마다 새로운 `gp-db-check-<난수>` 프로젝트·볼륨·secret과 임의 loopback 포트를 사용한다. 누락·빈 값·부분 초기화·특수문자 비밀번호를 검사하고 자신이 만든 자원만 정리한다.
- CI Compose job에 secret 생성·권한/영속성 검사·job 전용 볼륨/secret 정리를 추가했다. 기존 CI가 새 secret 누락으로 실패하지 않도록 필요한 연동만 반영했다. 원격 실행은 미실시다.

공동구매를 포함한 네 앱의 코드·의존성·Config 설정·DB 기동 의존 조건은 변경하지 않았다. JDBC·Flyway·Testcontainers·앱 DB 장애/복구·Windows JAR DB 연결은 후속 범위다. 전체 Gradle 재빌드는 이번 변경 범위에 없어 실행하지 않았다.

## 실제 검증과 근거

환경: Windows PowerShell, Python 3.13.15, Docker Engine 29.8.1, Compose v5.5.1, Linux containers. 이미지 pull 성공, 실제 digest:

```text
postgres@sha256:5c4783a8cc3f0074912164f94dfa1be4e043438f0f707cf968d7af9a0d31a2c8
```

| 실제 실행 | 결과 |
|---|---|
| secret 준비 두 번·기존 파일 해시 대조 | 생성 후 재실행에서 다섯 파일 내용 불변. 별도 임시 경로의 빈 기존 파일도 실패·보존 확인 |
| `docker compose config --quiet`, Python compile | 통과 |
| `docker compose up -d --no-deps --wait --wait-timeout 120 postgres` | 새 일반 볼륨 초기화·healthy·loopback 5432 확인 |
| `python scripts/check-postgres.py --persistence` | 자기 DB 4쌍 성공, 타 업무 DB 12쌍·시스템 DB 8쌍 CONNECT 권한 거절, 다른 서비스 SET ROLE 12쌍 거절, 오암호 4쌍 인증 거절, 다섯 SCRAM verifier 확인 |
| 같은 검사 내 DB 컨테이너 강제 재생성 | probe 토큰 유지·probe 스키마 삭제·접근 검사 재통과 |
| `python scripts/check-postgres-initialization.py` | 누락/빈 secret 초기화 중단, 부분 초기화 중단 및 재시작 시 자동 복구되지 않음, SQL/셸 특수문자 비밀번호 인증·권한 검사 통과, 임시 자원 정리 |
| `python scripts/check-discovery.py`, `python scripts/check-gateway.py` | 등록 주소 UP/도달성·Gateway ping 200·비공개 경로/메서드 404 통과 |
| `docker compose ps` | 기존 네 서비스와 PostgreSQL 모두 healthy |

검사 구현 중 Windows `text=True` 파이프의 CRLF 변환과 subshell의 비밀번호 환경변수 범위를 수정한 뒤 위 검사를 통과했다. 누락 secret은 이 Docker 환경에서 디렉터리로 마운트되어 Compose `up -d` 자체는 성공할 수 있었다. 초기화에서 일반 파일 여부까지 검사해 중단한다. 다른 Docker 환경에서 Compose가 먼저 거부하는 경우도 정상 실패로 판정한다.

`pg_isready`는 서버 수락 검사다. 유효한 계정·완성된 초기화의 증거는 TCP 권한 검사다. 부분 초기화 실패 후 재시작하면 서버만 healthy일 수 있음을 별도 격리 검사에서 실제 확인했다.

착수 기준: PR #4 OPEN, head `b94dcd412a31fa303f04b695c73903da205159c0`의 [CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41437130117), main `a2a88dce374c1ee7d93cac16fe35c0a047456f19`의 [CI 두 검사 성공](https://github.com/y0y00700/group-purchase/actions/runs/41402512856)을 재확인했다. 이 head에서 `feat/postgresql-foundation`을 생성했다. 기존 미커밋 문서 변경을 보존하고 관련 상태만 갱신했다. 커밋·push·PR 생성/병합은 하지 않았다.

<a id="manual-tests"></a>

## 사용자 직접 테스트

준비: Docker Desktop Linux 엔진·Python 3·PowerShell. 아래 작업 디렉터리에서 실행한다. 기본 DB 포트 5432가 필요하며 기존 네 앱을 실행하면 8000·8080·8761·8888도 사용한다. 호스트 psql/JDK 설치는 DB 검사에 필요 없다. 최초 이미지는 인터넷에서 받는다. `COMPOSE_FILE`·`COMPOSE_PROJECT_NAME`·`POSTGRES_PORT`를 별도로 설정했다면 대상 프로젝트/포트가 달라짐을 확인한다.

```powershell
Set-Location 'C:\Users\권민혁\Desktop\공부\개인프로젝트\group-purchase'
.\scripts\prepare-local-secrets.ps1
docker compose config --quiet
docker compose up -d --no-deps --wait --wait-timeout 120 postgres
python scripts/check-postgres.py
```

기대: 첫 준비는 Created, 재실행은 Preserved. DB healthy 후 자기 DB 네 개 PASS, 교차 12쌍과 시스템 DB 8쌍은 `permission denied for database`로 구분한다. 오암호는 `password authentication failed`여야 한다. 일반 검사에서는 DB/계정 소유권·관리 권한 없음·역할 멤버십 없음·PUBLIC 스키마 권한 정리·`app` 미생성·SCRAM 저장 방식을 확인한다. 마지막 요약이 PASS이고 종료 코드 0이어야 한다. 이 스텝 검사는 후속 Flyway가 `app`을 만들 때 변경해야 한다.

DB 재생성은 해당 DB 접속을 잠시 끊는다. 다음은 이번에 실제 실행한 영속성 검사다. 일반 볼륨을 삭제하지 않으며 자신의 난수 probe만 삭제한다.

```powershell
python scripts/check-postgres.py --persistence
```

기대: `PASS: probe survived PostgreSQL container recreation`, `DROP SCHEMA`, 권한 검사 재통과. client마다 180초, DB healthy 대기는 120초 상한이다. 정리 실패가 있으면 오류를 고친 뒤 일반 검사를 재실행하고, 남은 `probe_<난수>`만 관리자가 확인해 제거한다. 다른 스키마를 일괄 삭제하지 않는다.

주요 실패 재현은 다음 격리 검사를 사용한다. **기존 secret을 비우거나 정상 볼륨을 삭제하지 않는다.** 이 명령은 실행 시 새로 만든 프로젝트의 볼륨만 `down -v`로 제거한다.

```powershell
python scripts/check-postgres-initialization.py
```

기대: missing/empty secret 초기화 중단, partial init 중단 및 재기동 후 누락 DB 유지, quoted secrets 인증·권한 PASS. 각 실패 기동 종료 대기는 45초, 재시작 healthy 대기는 60초 상한이다. 이 검사는 실패를 기대하는 시나리오에서도 스크립트 전체가 성공하면 종료 코드 0이다. 강제 종료로 정리가 중단되면 `docker compose ls -a`와 `docker volume ls`에서 **그 실행의 `gp-db-check-<난수>` 이름**을 확인해 해당 프로젝트만 정리한다. 일반 프로젝트 `group-purchase`에 `down -v`를 실행하지 않는다.

기존 앱이 실행 중이면 아래 회귀를 확인한다. 이번에는 실행 중인 네 앱에 대해 실제 통과했다. 앱까지 새로 빌드하는 전체 명령은 이번 스텝에서 재실행하지 않았다.

```powershell
python scripts/check-discovery.py
python scripts/check-gateway.py
docker compose ps
```

DB만 종료/재시작하려면:

```powershell
docker compose stop postgres
docker compose up -d --no-deps --wait --wait-timeout 120 postgres
python scripts/check-postgres.py
# 전체 종료가 필요할 때: DB 데이터 볼륨과 secret 파일을 보존한다.
docker compose down
```

정상 종료·재시작 명령은 사용자용 절차다. 이번 영속성 실측은 `--force-recreate`이며 전체 `down/up`을 따로 재측정하지 않았다.

## 초기화 실패·비밀번호 변경과 다음 작업

초기화는 빈 데이터 디렉터리에서만 실행된다. 기존 볼륨에서는 init 스크립트·secret 파일 변경으로 DB/계정/암호가 갱신되지 않는다. 누락·빈 파일은 준비 단계에서 고치고, 이미 실패한 볼륨은 로그와 SQL 상태를 확인해 관리자가 빠진 DB/권한을 복구한다. 자동 재실행·기본 비밀번호 대체는 없다. 재현 검증은 새 임시 프로젝트로 한다. 정상 데이터가 있는 볼륨은 삭제하지 않는다.

비밀번호 회전이 필요하면 관리자 세션 `docker compose exec postgres psql -U postgres -d postgres`에서 `\password member_app`처럼 대화형 변경을 수행하고, 같은 새 값을 해당 `secrets/member_password` 파일에 출력·Git 기록 없이 갱신한다. 후속 앱이 연결되면 앱 secret도 동기화하고 해당 앱을 재시작한다. 관리자 암호 변경도 DB 변경과 `postgres_password` 파일 갱신이 모두 필요하다. 회전 절차 자체는 이번 스텝에서 실행하지 않았다.

공식 근거: [PostgreSQL 이미지의 18+ 볼륨 경로·빈 디렉터리 초기화](https://hub.docker.com/_/postgres), [PUBLIC·CONNECT 기본 권한](https://www.postgresql.org/docs/18/ddl-priv.html).

다음 작업은 공동구매 JDBC·드라이버·Flyway와 `app` 스키마/이력, 서비스별 secret 주입, 실제 PostgreSQL 테스트 fixture와 기존 Config/Eureka 회귀, 앱 기동 실패/실행 중 복구, Windows JAR 및 원격 CI 실행이다. 이번 결과를 005 전체 완료로 취급하지 않는다.
