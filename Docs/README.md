# Docs

프로젝트 관련 문서와 산출물은 이 디렉터리에서 관리한다. 기존 설계 기록 15개를 아래 위치로 옮기고 문서 간 상대 링크를 갱신했다.

| 경로 | 내용 |
|---|---|
| [design](design/README.md) | 요구사항·도메인·결제·API·인증·실행 환경·검증 설계 기록 |
| [implementation](implementation/README.md) | 단계별 구현 범위·변경 내용·실행 방법·검증 결과 |
| explanations (로컬 전용: `Docs/explanations/README.md`) | 요청받은 개념 설명과 학습 기록 |
| [decisions](decisions/README.md) | 선택 근거·대안·트레이드오프·재검토·ADR |
| [artifacts](artifacts/README.md) | ERD·SA·인프라 설계도 원본 및 열람용 출력 |
| process (로컬 전용: `Docs/process/README.md`) | 협업·설계 진행 원칙과 문서 이동 기록 |

[프로젝트 개요](프로젝트%20개요.md)에서 최신 범위와 합의 상태를 확인한다. 현재 코드는 단일 Spring Boot 초기 프로젝트이며, 기존 문서의 MSA 구성은 목표 설계다. 구현 완료·실행 검증과 설계 합의를 구분한다.

설계도는 아직 작성 대기다. 각 산출물을 작성할 때 원본·출력·관련 설계 기록·구현 반영 여부를 연결한다.

학습·설명 문서(Docs/explanations/)와 협업 진행 문서(Docs/process/)는 .gitignore로 제외해 로컬에서 관리한다.
