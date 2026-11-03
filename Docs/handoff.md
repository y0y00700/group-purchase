# 공동구매 프로젝트 인계

갱신일: 2026-11-02

## 현재 위치

- 저장소: [y0y00700/group-purchase](https://github.com/y0y00700/group-purchase)
- 최근 완료 PR: [#3 Eureka Server와 첫 서비스 등록·발견](https://github.com/y0y00700/group-purchase/pull/3). 2026-11-02 15:50 KST 병합 완료를 확인했다. 병합 커밋은 `a2a88dc`다.
- 현재 브랜치는 `feat/api-gateway`이며 기준 main 커밋은 `a2a88dc`다. 004 구현과 선행 인계 문서 변경은 코드 커밋 `c16c7cb`에 함께 반영했고 [PR #4](https://github.com/y0y00700/group-purchase/pull/4)를 생성했다. 이 인계 갱신은 코드 커밋의 CI 결과를 기록하는 문서 변경이다.
- 구현 커밋 `120471f`에 Eureka·이미지·Compose·테스트·직접 테스트를 추가했고, 최종 코드 커밋 `2914c1d`에 독립 발견 캐시 대기와 테스트 서버 출력 정리를 보완했다.
- PR 최종 head `2914c1d`의 [CI #41401664562](https://github.com/y0y00700/group-purchase/actions/runs/41401664562)에서 Build and test·Images and Compose smoke test가 모두 성공했다.
- 병합된 `main`의 `a2a88dc`도 [CI #41402512856](https://github.com/y0y00700/group-purchase/actions/runs/41402512856)에서 두 검사가 모두 성공했음을 인계 시 재확인했다.
- [PR #1](https://github.com/y0y00700/group-purchase/pull/1)·[PR #2](https://github.com/y0y00700/group-purchase/pull/2)도 병합 완료다. PR #4의 코드 커밋 `c16c7cb`에서 [CI #41436116472](https://github.com/y0y00700/group-purchase/actions/runs/41436116472)의 Build and test·Images and Compose smoke test가 모두 성공했다. 문서 갱신 후 최종 head의 검사는 PR Checks에서 별도로 확인한다. 최근 근거는 [004 결과](implementation/004-api-gateway.md)를 따른다.

## 완료 범위

Java 21·Boot 4.0.9·Cloud BOM 2025.1.4·Config 5.0.6·Netflix 5.0.3·Gateway 5.0.4·Gradle Wrapper 9.8.2를 사용한다. 실행 모듈은 `config-server`, `eureka-server`, `api-gateway`, `group-purchase-service`다.

Config Server는 같은 저장소 `config-repo/`의 일반 YAML을 native 백엔드로 제공한다. 공동구매 앱 이름은 `group-purchase`이며 local/docker 설정을 받아 필수 값과 서비스·환경 일치를 검증한다. 설정 변경은 클라이언트 재시작으로 반영하고, 비밀값은 필요한 서비스에 별도 주입한다.

Eureka도 Config에서 설정을 받아 단독 등록소로 실행한다. 공동구매 앱은 Eureka가 없어도 기동하며 등록·조회 재시도로 복구한다. 정상 종료 시 등록을 해제하고, 강제 종료 시 오래된 등록이 남을 수 있다. 기본 주기와 자기 보호를 유지하며 앱 헬스·실제 발견·등록 주소 도달성을 나누어 검증했다.

Gateway는 Config의 명시적 GET ping 라우트를 받아 Eureka에서 `group-purchase`를 찾아 전달한다. 내부·관리 경로는 8000에서 404이고, 자체 관리는 별도 9000이다. 앱 없음 503, 연결 실패 502, 응답 지연 504를 구분한다. 도메인 업무 API·회원 인증·DB·Kafka는 아직 구현하지 않았다.

## 검증 근거와 유지할 사항

- 전체 자동 테스트는 28개다. Windows 전체 빌드·네 이미지/Compose·대상/Eureka 장애와 복구·Windows 두 JAR local 연동을 검증했다. 003의 최종 코드와 병합 main, 004 PR #4의 코드 커밋에서 Ubuntu CI 두 검사가 통과했다.
- 정상 종료 후 등록 제거·재등록, Eureka 없는 앱 기동과 서버 복구 후 재등록, Config 장애·프로필 오타 거절을 확인했다. 강제 종료 110초 후 잔존 UP 등록을 관측했으나 자기 보호 발동이나 정확한 만료 시간을 단정하지 않는다.
- CI 복구 테스트는 서버 목록·새 관찰 클라이언트·기존 앱의 발견 캐시 수렴을 각각 확인한다. 서로 다른 클라이언트의 갱신을 동일 시점으로 가정하지 않는다.
- 테스트 Eureka는 별도 Java 프로세스다. 서버 starter를 클라이언트 테스트 classpath에 다시 섞지 않는다. Windows 파일 잠금을 피하도록 출력은 메모리로 읽고 프로세스와 읽기 스레드를 모두 종료한다.
- Config 접속은 고정 주소이며 Eureka 조회에 의존하지 않는다. 중앙 설정·필수 값 검증 실패는 기동 실패, Eureka 접속 실패는 앱 유지·재시도 정책이다.
- 주소는 local의 `localhost:8080`, docker의 `group-purchase-service:8080`을 구분한다. Windows Gateway와 Docker 공동구매 앱을 섞으면 등록된 Docker DNS 이름을 Windows에서 해석할 수 없다. 인스턴스 UP 등록과 실제 요청 성공은 별도로 판정한다.
- 로컬 캐시는 `.tmp/gradle-home`, 검증 로그는 `.tmp/003-*.log`다. 캐시 경로를 저장소 기본 설정에 고정하지 않는다. 호스트의 실제 JAR 실행은 검증했으며 Gradle `bootRun` 명령 자체는 별도 실행하지 않았다.

## 직접 실행과 테스트

프로젝트 루트에서 Docker Desktop Linux 엔진을 실행하고 8000·8080·8761·8888 포트를 확보한다. 검사 스크립트에는 Python 3이 필요하다.

004 로컬 검증의 마지막 복구에서 네 컨테이너 healthy와 등록·Gateway 검사 PASS를 확인했다. 아래 명령으로 재개 시 실제 상태를 먼저 확인한다.

```powershell
docker compose ps
python scripts/check-discovery.py --timeout 20
python scripts/check-gateway.py --timeout 20
```

```powershell
docker compose up --build -d --wait --wait-timeout 180
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8761/actuator/info | ConvertTo-Json -Depth 5
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
python scripts/check-discovery.py
python scripts/check-gateway.py
Invoke-RestMethod http://localhost:8000/api/group-purchases/ping
```

기대 결과는 네 서비스 healthy, Gateway ping 200과 JSON의 `message=pong`이다. 등록 검사는 단일 UP 인스턴스의 실제 주소를 Config 컨테이너에서 호출해 PASS로 판정한다. Gateway 검사는 비공개 경로·메서드의 404도 확인한다. 종료는 `docker compose down`이다. JDK 21과 JAVA_HOME이 준비되어 있으면 `.\gradlew.bat build`로 28개 자동 테스트를 실행한다.

Gateway·대상 종료·Eureka 복구·Windows JAR 검증은 [004 직접 테스트](implementation/004-api-gateway.md#manual-tests)를 따른다. Config 설정 변경은 [002 결과](implementation/002-config-server.md#manual-tests), Eureka 강제 종료는 [003 결과](implementation/003-eureka-server.md#manual-tests)를 따른다.

## 다음 작업과 진행 규칙

1. `AGENTS.md`·이 인계·[004 결과](implementation/004-api-gateway.md)·[로드맵](implementation/plan/roadmap.md)을 읽고 `git status`와 최신 `main`·CI를 확인한다. 로컬 작업 트리의 기존 문서 변경과 004 구현을 보존한다.
2. 004는 로컬 구현·검증과 PR #4 생성·코드 커밋 CI 성공을 확인했다. PR은 열려 있고 병합은 별도 요청을 따른다. 이 문서 갱신 뒤 최종 head의 CI와 병합 상태는 재개 시 확인한다.
3. 다음 기능은 PostgreSQL 실행 기반이다. 상세 계획은 아직 작성하지 않았다. 회원 인증은 기존 로드맵의 별도 단계다.
4. Config 최초 접속이 Eureka 조회를 필요로 하는 순환 구조를 만들지 않는다. 서비스 이름·Config 주소·활성 프로필은 최초 접속 전에 결정한다.
5. 사용자 진행 요청을 받은 단계의 구현·검증·결과 기록까지만 수행한다. 모든 단계의 결과에 준비 사항·명령·기대 결과·종료/복구 방법을 제공한다.

기준 문서: [AGENTS.md](../AGENTS.md), [구현 진행 규칙](implementation/README.md), [로드맵](implementation/plan/roadmap.md), [설계 안내](README.md). 로컬 학습 노트·협업 기록 `Docs/explanations/`와 `Docs/process/`는 Git 제외 상태를 유지한다.

Config·Eureka·Gateway 역할 설명은 로컬 `Docs/explanations/`의 주제별 문서에 있다. 이 경로는 Git 제외 상태다.

## 새 채팅 시작 문구

> 목표 달성형 공동구매 프로젝트를 이어가자. AGENTS.md와 Docs/handoff.md, Docs/implementation/004-api-gateway.md를 읽어줘. feat/api-gateway의 로컬 구현·28개 테스트·네 이미지/Compose·Windows JAR 검증을 완료하고 PR #4를 열었어. 코드 커밋 c16c7cb의 CI 두 검사는 성공했어. 최종 head의 CI·PR 상태와 실행 중인 네 서비스를 확인하고, 다음 PostgreSQL 실행 기반은 상세 계획부터 설명해줘. 구현 결과에는 내가 직접 테스트할 방법을 포함해줘.
