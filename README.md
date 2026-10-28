# 목표 달성형 공동구매

Java 21 · Spring Boot 4.0.9 · Maven Wrapper 3.9.16

현재 단계는 단일 Spring Boot 프로젝트 생성과 문서 정리다. 애플리케이션 진입점과 기본 컨텍스트 테스트를 포함한다. 서비스 분리·MSA 공통 구성은 후속 작업에서 단계별 커밋으로 추가한다.

## 구조

```text
group-purchase/
├── src/main/java/com/example/grouppurchase/
├── src/main/resources/
├── src/test/java/com/example/grouppurchase/
├── Docs/
├── .mvn/wrapper/
├── pom.xml
├── mvnw
└── mvnw.cmd
```

## 빌드

JDK 21을 JAVA_HOME에 지정하고 프로젝트 루트에서 실행한다.

```powershell
.\mvnw.cmd verify
```

macOS·Linux에서는 `./mvnw verify`를 사용한다. 기본 애플리케이션 실행은 `./mvnw spring-boot:run`이다. 현재 기본 starter만 포함하므로 HTTP 서버·API는 다음 구성 단계에서 추가한다.

## 문서

- [문서 안내](Docs/README.md)
- [프로젝트 개요](Docs/프로젝트%20개요.md)
- [이번 프로젝트 생성 기록](Docs/implementation/000-initial-project.md)
- [설계 산출물 관리](Docs/artifacts/README.md)
