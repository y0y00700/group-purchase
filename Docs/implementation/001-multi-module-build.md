# 001. Gradle 멀티 모듈·YAML·CI 전환

작성일: 2026-10-29

상태: 로컬 구현·검증 완료. PR의 GitHub Actions 확인 대기.

관련 계획: [001 전환 계획](plan/001-multi-module-build.md). 시작 기준은 `641f7eb`이며 작업 브랜치는 `chore/gradle-multi-module`이다.

## 구현 범위

Maven 단일 앱을 Gradle 멀티 프로젝트의 공동구매 서비스 모듈로 옮겼다. Java 21·Spring Boot 4.0.9·패키지·진입점·애플리케이션 이름과 기존 starter 의존성은 유지했다. 실제 실행 모듈은 `group-purchase-service` 하나다.

| 경로 | 변경과 책임 |
|---|---|
| `settings.gradle` | 루트 이름, 모듈 경로, 플러그인 저장소와 Maven Central을 등록. 모듈별 저장소 중복 선언 제한 |
| `build.gradle` | 루트에는 base 플러그인만 적용. Boot 플러그인 버전과 하위 Java 모듈의 group·version·Java 21 toolchain·UTF-8·JUnit Platform 기준 관리 |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle 9.8.2와 배포본 SHA-256을 고정한 공식 생성 Wrapper |
| `services/group-purchase-service/` | 기존 Java 소스·테스트 이동. 서비스에서만 Java·Boot 플러그인을 적용하고 실행 JAR 생성 |
| `services/group-purchase-service/build.gradle` | Boot BOM을 Gradle의 native platform으로 적용. 기존 starter·테스트 의존성과 JUnit Platform launcher 명시 |
| `services/group-purchase-service/src/main/resources/application.yml` | 기존 `spring.application.name=group-purchase`를 YAML로 전환 |
| 기존 컨텍스트 테스트 | 테스트 수 1개를 유지하고 Environment의 애플리케이션 이름 검증 추가 |
| `.github/workflows/ci.yml` | 기존 Maven 워크플로를 이동하고 Gradle 캐시·`bash ./gradlew build --no-daemon`으로 변경. `build-test` ID·`Build and test` 이름·실행 조건 유지 |
| `.gitignore`, `.gitattributes`, `.editorconfig` | Gradle 캐시·모듈 build 제외, Windows 배치 파일의 CRLF 기준 추가 |
| README와 Docs | 현재 구조·명령·설계 상태와 계획·결과 연결 갱신 |

Gradle 전체 빌드 성공 후 `pom.xml`·`mvnw`·`mvnw.cmd`·`.mvn/`을 제거했다. 공통 업무 코드·추가 서비스·HTTP API·DB·Docker Compose는 다음 구현 단계에서 다룬다.

## 선택과 버전

- Groovy DSL과 루트 Wrapper 하나를 사용하는 멀티 프로젝트를 적용했다. 서비스별 빌드 스크립트는 실행·의존성을 관리하고 루트는 공통 빌드 기준을 관리한다.
- Spring Boot 4.0 공식 Gradle 플러그인의 지원 범위인 Gradle 9.x에서 9.8.2를 고정하고 현재 앱으로 빌드·기동을 검증했다.
- BOM은 Boot 플러그인의 `BOM_COORDINATES`로 참조해 플러그인과 의존성 관리의 Boot 버전을 일치시킨다. 이번 단계에는 별도 dependency-management 플러그인이나 공유 코드 모듈을 추가하지 않았다.
- 배포본 SHA-256: `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`.
- Wrapper JAR SHA-256: `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`.
- 검증한 공식 배포본의 `:wrapper` 태스크로 파일을 생성하고, 배포본과 생성 JAR의 SHA-256을 공식 배포 메타데이터와 대조했다.

## 로컬 검증

환경: Windows 11, Microsoft OpenJDK 21.0.12.1, Gradle 9.8.2. 명령은 저장소 루트에서 실행했다. 검증 과정에서는 Maven Wrapper 캐시를 `.tmp/maven-home`, `GRADLE_USER_HOME`을 `.tmp/gradle-home`으로 지정했다. 사용자별 절대 경로와 검증용 캐시 설정은 빌드 파일에 넣지 않았다.

| 검증 | 실제 명령·확인 | 결과 |
|---|---|---|
| 전환 전 기준 | `.\mvnw.cmd --batch-mode --no-transfer-progress verify` | BUILD SUCCESS, 테스트 1개·실패/오류/건너뜀 0 |
| Wrapper 버전 | `.\gradlew.bat --version` | Gradle 9.8.2, launcher JVM 21.0.12.1 |
| 전체 빌드 | `.\gradlew.bat clean build --no-daemon --console=plain` | BUILD SUCCESSFUL, 서비스 컴파일·테스트·패키징 완료 |
| 선택 모듈 | `.\gradlew.bat :group-purchase-service:test :group-purchase-service:build --no-daemon --console=plain` | BUILD SUCCESSFUL, 직전 전체 빌드의 동일 입력에 대한 태스크 UP-TO-DATE |
| 테스트 결과 | `build/test-results/test/TEST-com.example.grouppurchase.GroupPurchaseApplicationTests.xml` | 테스트 1개·실패/오류/건너뜀 0. 컨텍스트와 YAML의 앱 이름 검증 |
| 개발 실행 | `.\gradlew.bat :group-purchase-service:bootRun --no-daemon --console=plain` | `Started GroupPurchaseApplication`, 앱 이름 `group-purchase`, 종료 코드 0 |
| 실행 JAR | Java 21로 `java -jar services/group-purchase-service/build/libs/group-purchase-service-0.0.1-SNAPSHOT.jar` | `Started GroupPurchaseApplication`, 앱 이름 `group-purchase`, 종료 코드 0 |
| 패키징 경계 | Boot JAR의 manifest·설정 파일과 루트 출력 확인 | JarLauncher·기존 Start-Class 확인, `BOOT-INF/classes/application.yml` 포함, properties 없음, 루트 실행 JAR 없음 |

현재 앱은 웹 starter가 없는 초기 앱이므로 컨텍스트 시작 후 정상 종료한다. HTTP 요청 성공이나 업무 기능·MSA 연결·성능을 이번 검증 결과로 간주하지 않는다.

초기 Maven 검증 시 기본 사용자 캐시 쓰기 제한과 다운로드 네트워크 제한이 있었다. Wrapper 캐시 위치를 작업 디렉터리로 지정하고 다운로드가 가능한 실행 환경에서 재실행해 기준 빌드가 통과했다. Gradle 검증 로그는 로컬 전용 `.tmp/001-*.log`에 남겼다.

## GitHub CI

워크플로는 `main` 대상 PR·`main` push·수동 실행을 지원한다. Java 21·Gradle 캐시에서 `bash ./gradlew build --no-daemon`을 실행하며, 검사 이름은 `Build and test`다.

PR 생성 후 실제 실행 링크·검증 커밋·결과를 이 절에 기록한다. 현재 이 기록만으로 원격 CI 성공이나 Ruleset 적용을 의미하지 않는다. Ruleset 설정·main 병합·자동 배포는 이번 변경에서 수행하지 않는다.

## 다음 작업

Config Server의 상세 계획을 작성·검토한다. 후속 단계에서 Spring Cloud 호환 버전·중앙 YAML 설정·첫 클라이언트·Docker 이미지·Compose 기동과 설정 누락 시 동작을 구체화한다.

## 참고

- [Spring Boot 4.0 Gradle 플러그인](https://docs.spring.io/spring-boot/4.0/gradle-plugin/index.html)
- [Spring Boot의 Gradle BOM 관리](https://docs.spring.io/spring-boot/4.0/gradle-plugin/managing-dependencies.html)
- [Gradle Wrapper와 배포본 검증](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- [Gradle 공식 배포 메타데이터](https://services.gradle.org/versions/current)

[구현 목록](README.md) · [전체 순서](plan/roadmap.md)
