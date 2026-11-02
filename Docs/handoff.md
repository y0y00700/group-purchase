# 공동구매 프로젝트 인계

갱신일: 2026-11-02

## 현재 위치

- 저장소: [y0y00700/group-purchase](https://github.com/y0y00700/group-purchase)
- 최근 완료 PR: [#2 Config Server와 첫 클라이언트 연동](https://github.com/y0y00700/group-purchase/pull/2). 2026-11-02 병합 완료를 확인했다. 병합 커밋은 `56f3474`다.
- 현재 로컬 브랜치: `feat/eureka-server`. 최신 `origin/main`의 `56f3474`에서 만들었고 계획 문서 변경을 보존해 003을 구현했다. 이번 변경은 아직 커밋·push하지 않았으며 새 PR은 생성하지 않았다.
- 구현 커밋: `b95fd76` — Config Server·첫 클라이언트·이미지·Compose·테스트·직접 테스트 규칙.
- 기존 [PR #1 Gradle 전환](https://github.com/y0y00700/group-purchase/pull/1)은 병합 완료 상태임을 2026-11-02 확인했다.
- PR 최종 head `b20925e`의 [CI #41373781570](https://github.com/y0y00700/group-purchase/actions/runs/41373781570)에서 Build and test·Images and Compose smoke test가 모두 성공했음을 재확인했다. 실제 검증 기록은 [002 결과](implementation/002-config-server.md)를 따른다.
- 최근 완료: [003 Eureka 구현·직접 테스트](implementation/003-eureka-server.md). 사용자 계획 검토 완료·구현 요청에 따라 구현했고 자동 테스트 총 23개, 세 이미지·Compose·등록/발견·장애/복구·Windows JAR local 연동을 검증했다. 이번 변경의 원격 CI는 미실행이다.

## 완료 범위

Java 21·Boot 4.0.9·Cloud BOM 2025.1.4·Config 5.0.6·Netflix 5.0.3·Gradle Wrapper 9.8.2를 사용한다. 실행 모듈은 `config-server`, `eureka-server`, `group-purchase-service`다.

Config Server는 같은 저장소 `config-repo/`의 일반 YAML을 native 백엔드로 제공한다. 공동구매 앱 이름은 `group-purchase`이며 local/docker 설정을 받아 필수 값과 서비스·환경 일치를 검증한다. 설정 변경은 클라이언트 재시작으로 반영하고, 비밀값은 필요한 서비스에 별도 주입한다.

Eureka도 Config에서 설정을 받아 단독 등록소로 실행한다. 공동구매 앱은 Eureka가 없어도 기동하며 등록·조회 재시도로 복구한다. 정상 종료 시 등록을 해제하고, 강제 종료 시 오래된 등록이 남을 수 있다. 기본 주기와 자기 보호를 유지하며 앱 헬스·실제 발견·등록 주소 도달성을 나누어 검증했다. 도메인 업무 API·Gateway·DB·Kafka는 아직 구현하지 않았다.

## 직접 실행과 테스트

프로젝트 루트에서 Docker Desktop Linux 엔진을 실행하고 8080·8761·8888 포트를 확보한다. 등록 검사 스크립트에는 Python 3이 필요하다.

```powershell
docker compose up --build -d --wait --wait-timeout 180
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8761/actuator/info | ConvertTo-Json -Depth 5
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
python scripts/check-discovery.py
```

기대 결과는 세 서비스 healthy와 health의 status=UP이다. Eureka info는 eureka-server·docker·003-v1, 공동구매 info는 group-purchase·docker·002-v1이다. 등록 검사는 단일 UP 인스턴스의 실제 주소를 Config 컨테이너에서 호출해 PASS로 판정한다. 종료는 `docker compose down`이다. JDK 21과 JAVA_HOME이 준비되어 있으면 `.\gradlew.bat build`로 자동 테스트를 실행한다.

정상 종료·등록 해제·재등록·Eureka/Config 장애·프로필 오타·강제 종료와 복구는 [003 직접 테스트](implementation/003-eureka-server.md#manual-tests)를 따른다. Config 설정 변경 설명은 [002 결과](implementation/002-config-server.md#manual-tests)에 있다. 재개 시 `docker compose ps`로 실제 상태를 확인한다.

## 다음 작업과 진행 규칙

1. 003은 구현·로컬 검증 완료다. 사용자 추가 요청이 커밋·push·PR이라면 현재 변경과 테스트 결과를 확인한 뒤 해당 범위를 진행한다. 이번 인계가 원격 작업을 추가로 승인하는 것은 아니다.
2. 다음 기능은 API Gateway 기본 라우팅이다. 진행 요청을 받으면 상세 계획을 먼저 작성·검토한다.
3. Gateway 계획에는 서비스 이름 기반 전달, 공개·내부 경로 구분, 대상 장애·라우팅 실패, 이미지·Compose·CI와 직접 테스트를 포함한다. 회원 인증은 기존 로드맵의 별도 단계다.
4. Config 최초 접속이 Eureka 조회를 필요로 하는 순환 구조를 만들지 않는다. 서비스 이름·Config 주소·활성 프로필은 최초 접속 전에 결정한다.
5. 사용자 진행 요청을 받은 단계의 구현·검증·결과 기록까지만 수행한다. 모든 단계의 결과에 준비 사항·명령·기대 결과·종료/복구 방법을 제공한다.

기준 문서: [AGENTS.md](../AGENTS.md), [구현 진행 규칙](implementation/README.md), [로드맵](implementation/plan/roadmap.md), [설계 안내](README.md). 로컬 학습 노트·협업 기록 `Docs/explanations/`와 `Docs/process/`는 Git 제외 상태를 유지한다.

## 새 채팅 시작 문구

> 목표 달성형 공동구매 프로젝트를 이어가자. AGENTS.md와 Docs/handoff.md, Docs/implementation/003-eureka-server.md를 읽어줘. Eureka 구현과 로컬 검증까지 완료했고 다음 기능은 API Gateway 기본 라우팅이야. 현재 브랜치·변경·요청한 작업 범위를 확인해 이어가고, 새 기능은 계획부터 검토하며 구현 결과에는 내가 직접 테스트할 방법을 포함해줘.
