# 공동구매 프로젝트 인계

갱신일: 2026-11-02

## 현재 위치

- 저장소: [y0y00700/group-purchase](https://github.com/y0y00700/group-purchase)
- 진행 중 PR: [#2 Config Server와 첫 클라이언트 연동](https://github.com/y0y00700/group-purchase/pull/2)
- 브랜치: `feat/config-server`, 대상: `main`. PR 병합은 별도 작업이다.
- 구현 커밋: `b95fd76` — Config Server·첫 클라이언트·이미지·Compose·테스트·직접 테스트 규칙.
- 기존 [PR #1 Gradle 전환](https://github.com/y0y00700/group-purchase/pull/1)은 병합 완료 상태임을 2026-11-02 확인했다.
- CI의 최신 상태는 [PR #2 Checks](https://github.com/y0y00700/group-purchase/pull/2/checks)에서 확인한다. 실제 검증 기록은 [002 결과](implementation/002-config-server.md)를 따른다.
- 구현 커밋 `b95fd76`의 [CI #41373439233](https://github.com/y0y00700/group-purchase/actions/runs/41373439233)에서 Build and test·Images and Compose smoke test가 모두 성공했다. 후속 문서 커밋의 결과는 최신 Checks로 확인한다.

## 완료 범위

Java 21·Boot 4.0.9·Cloud BOM 2025.1.4·Config 5.0.6·Gradle Wrapper 9.8.2를 사용한다. 실행 모듈은 `config-server`와 `group-purchase-service`다.

Config Server는 같은 저장소 `config-repo/`의 일반 YAML을 native 백엔드로 제공한다. 공동구매 앱 이름은 `group-purchase`이며 local/docker 설정을 받아 필수 값과 서비스·환경 일치를 검증한다. 설정 변경은 클라이언트 재시작으로 반영하고, 비밀값은 필요한 서비스에 별도 주입한다.

2026-10-30 로컬 검증은 자동 테스트 13개·실패/오류 0, 두 이미지 빌드·Compose healthy, 프로필 오타 거절·Config 중단/복구·설정 변경/원복·Windows JAR local 연동까지 수행했다. 도메인 업무 API·Eureka·Gateway·DB·Kafka는 아직 구현하지 않았다.

## 직접 실행과 테스트

프로젝트 루트에서 Docker Desktop Linux 엔진을 실행하고 8080·8888 포트를 확보한다.

```powershell
docker compose up --build -d --wait --wait-timeout 180
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/info | ConvertTo-Json -Depth 5
```

기대 결과는 두 서비스 healthy, health의 status=UP, info의 service=group-purchase·environment=docker·revision=002-v1이다. 종료는 `docker compose down`이다. JDK 21과 JAVA_HOME이 준비되어 있으면 `.\gradlew.bat build`로 자동 테스트를 실행한다.

실패 재현·복구·설정 변경 및 원복은 [사용자 직접 테스트](implementation/002-config-server.md#manual-tests)를 따른다. 실행 중인 컨테이너 상태는 환경에 따라 달라지므로 작업 재개 시 `docker compose ps`로 확인한다.

## 다음 작업과 진행 규칙

1. PR #2의 최신 변경·Checks·병합 상태를 확인한다. 이 인계가 PR 병합을 승인하는 것은 아니다.
2. 다음 구현 대상은 Eureka Server다. 먼저 상세 계획을 작성·검토한다.
3. 계획에는 Config에서 일반 설정 수신, 서비스 등록·조회·재등록, 서버/클라이언트 시작 순서, 이미지·Compose 확장과 사용자 직접 테스트를 포함한다.
4. Config 최초 접속이 Eureka 조회를 필요로 하는 순환 구조를 만들지 않는다. 서비스 이름·Config 주소·활성 프로필은 최초 접속 전에 결정한다.
5. 사용자 진행 요청을 받은 단계의 구현·검증·결과 기록까지만 수행한다. 모든 단계의 결과에 준비 사항·명령·기대 결과·종료/복구 방법을 제공한다.

기준 문서: [AGENTS.md](../AGENTS.md), [구현 진행 규칙](implementation/README.md), [로드맵](implementation/plan/roadmap.md), [설계 안내](README.md). 로컬 학습 노트·협업 기록 `Docs/explanations/`와 `Docs/process/`는 Git 제외 상태를 유지한다.

## 새 채팅 시작 문구

> 목표 달성형 공동구매 프로젝트를 이어가자. AGENTS.md와 Docs/handoff.md를 읽고 PR #2의 최신 상태·Checks를 확인해줘. Config Server와 첫 클라이언트의 구현·로컬 검증은 완료했고, 다음 작업은 Eureka Server야. 구현 전에 상세 계획부터 설명하고 각 단계의 결과에는 내가 직접 테스트할 방법을 포함해줘.
