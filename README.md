# 목표 달성형 공동구매

Java 21 · Spring Boot 4.0.9 · Gradle Wrapper 9.8.2

Gradle 멀티 프로젝트 안에 공동구매 서비스 초기 앱 하나를 둔다. 애플리케이션 진입점과 컨텍스트·YAML 설정 로딩 테스트를 포함하며, 서비스 설정은 `application.yml`로 관리한다. 업무 API와 MSA 공통 구성은 후속 단계에서 추가한다.

전체 일괄 기동은 다음 Config Server 단계부터 서비스별 Docker Compose 정의를 누적해 구성한다. 현재 앱에는 웹 의존성이 없으므로 실행 시 컨텍스트를 시작한 뒤 정상 종료하며 HTTP 서버를 제공하지 않는다.

## 구조

```text
group-purchase/
├── settings.gradle
├── build.gradle
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── services/
│   └── group-purchase-service/
│       ├── build.gradle
│       └── src/
│           ├── main/java/com/example/grouppurchase/
│           ├── main/resources/application.yml
│           └── test/java/com/example/grouppurchase/
├── .github/workflows/ci.yml
└── Docs/
```

## 빌드와 실행

JDK 21을 `JAVA_HOME`에 지정하고 프로젝트 루트에서 실행한다. Gradle을 별도 설치할 필요는 없다. Wrapper가 체크섬으로 검증한 고정 버전을 내려받으며 첫 빌드에는 네트워크 연결이 필요하다.

```powershell
# 전체 빌드와 테스트
.\gradlew.bat clean build

# 공동구매 서비스만 테스트·빌드
.\gradlew.bat :group-purchase-service:test :group-purchase-service:build

# 개발 실행
.\gradlew.bat :group-purchase-service:bootRun

# 빌드한 실행 JAR 실행
& "$env:JAVA_HOME\bin\java.exe" -jar services/group-purchase-service/build/libs/group-purchase-service-0.0.1-SNAPSHOT.jar
```

macOS·Linux에서는 같은 태스크를 `bash ./gradlew clean build`, `bash ./gradlew :group-purchase-service:bootRun`처럼 실행한다. JAR 실행에는 Java 21의 `java -jar`를 사용한다.

실행 가능한 Boot JAR는 `services/group-purchase-service/build/libs/`에 생성된다. `-plain.jar`는 의존성을 포함하지 않는 일반 JAR이며, 직접 실행할 때는 위 명령의 Boot JAR를 사용한다. 루트는 빌드 구성을 관리하고 실행 JAR를 만들지 않는다.

## CI

GitHub Actions는 `main` 대상 PR과 `main` push에서 Java 21로 `bash ./gradlew build --no-daemon`을 실행한다. Actions 화면의 수동 실행도 지원한다. 검사 이름은 `Build and test`로 유지한다. PR 병합을 검사 성공에 연결하는 Ruleset은 GitHub 저장소에서 별도로 관리한다.

## 문서

- [문서·설계·현재 진행 상태](Docs/README.md)
- [구현 계획과 기록](Docs/implementation/README.md)
- [Gradle·YAML·CI 전환 결과](Docs/implementation/001-multi-module-build.md)
- [이번 프로젝트 생성 기록](Docs/implementation/000-initial-project.md)
- [설계 산출물 관리](Docs/artifacts/README.md)
