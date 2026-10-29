# 000. 단일 Spring Boot 프로젝트 생성

작성일: 2026-10-28

## 범위

사용자 요청에 따라 단일 Spring Boot 프로젝트 하나와 Docs 분류 구조를 생성한다. 후속 MSA 공통 구성·모듈 분리는 각각의 작업에서 단계별 커밋으로 진행한다.

## 생성 내용

- Java 21, Spring Boot 4.0.9, Maven Wrapper 3.9.16.
- com.example.grouppurchase.GroupPurchaseApplication 진입점.
- 기본 spring-boot-starter와 테스트 의존성, 컨텍스트 로딩 테스트.
- Docs 하위 분류와 기존 설계 문서 15개 이동, 상대 링크 갱신.
- ERD·SA·인프라 설계도는 저장 위치와 상태 관리 기준을 마련했다. 실제 도식 작성은 아직 대기다.

## 검증

상태: 완료.

- 환경: Microsoft OpenJDK 21.0.12.1, Maven Wrapper 3.9.16.
- 실행: `./mvnw -Dmaven.repo.local=.tmp/maven-repository verify` (Windows에서는 mvnw.cmd). 검증 시 Maven 캐시는 .tmp 아래로 지정했다.
- 결과: BUILD SUCCESS.
- 기본 컨텍스트 테스트: 1개 실행, 실패 0·오류 0·건너뜀 0.
- 실행 가능한 Boot jar 패키징 완료.
- 다중 모듈·MSA 연결은 이번 검증 범위에 포함하지 않았다.

## 출처

- [Spring Initializr](https://start.spring.io/): 기본 프로젝트 생성.
- [Spring Boot 4.0 시스템 요구사항](https://docs.spring.io/spring-boot/4.0/system-requirements.html): Java·Maven 지원 범위 확인.
- [Maven Wrapper](https://maven.apache.org/tools/wrapper/): 재현 가능한 Maven 실행.

<a id="scope-decision"></a>

## 생성 범위 결정 (ADR-0001)

- 날짜: 2026-10-28
- 상태: 채택 — 사용자 요청

### 배경

사용자는 저장소에 구축 과정을 단계별 커밋으로 남기려 한다. 첫 생성에서 서비스·공통 기반 모듈까지 한꺼번에 만들면 이후 단계의 변경 범위가 섞인다.

### 결정

단일 Spring Boot 프로젝트만 생성한다. 서비스 모듈·MSA 공통 구성은 후속 작업에서 단계별로 추가한다. 문서는 프로젝트의 Docs 안에서 설계·구현·설명·판단·산출물·진행 원칙으로 분류한다.

### 영향

기존 MSA 설계의 서비스 경계는 목표 구조로 유지한다. 현재 디렉터리와 실행 상태를 최종 MSA 구성으로 해석하지 않는다. 이후 커밋·원격 반영은 사용자가 요청한 작업 범위에 따른다.

이 기록은 2026-10-28의 생성·검증 사실이다. 이후 빌드 도구는 Gradle로 변경하기로 했으며 [001 전환 계획](plan/001-multi-module-build.md)을 검토 중이다.
