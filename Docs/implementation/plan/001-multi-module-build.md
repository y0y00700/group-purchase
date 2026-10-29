# 001. Gradle 멀티 모듈·YAML 설정 전환

작성일: 2026-10-29

상태: 완료 — 로컬 빌드·기동과 PR의 GitHub CI 검증 완료. main 병합은 별도다.

진행 합의 범위: Gradle 멀티 모듈·`application.yml`·기존 CI 전환과 검증·결과 기록. 전체 일괄 기동은 후속 단계에서 Docker Compose로 구성한다. 2026-10-29 사용자 진행 요청으로 착수했다. [구현 결과](../001-multi-module-build.md)에서 실제 진행·검증 상태를 관리한다.

## 목표와 현재 상태

착수 전 루트 `pom.xml`과 `src/`는 Maven 기반 단일 Spring Boot 앱이다. 사용자 수정 의견에 따라 Gradle Wrapper 기반 멀티 모듈로 전환하고, 기존 앱은 공동구매 서비스 모듈로 이동한다. 애플리케이션 설정은 YAML 형식으로 전환한다.

GitHub Actions의 `.github/workflows/maven.yml`이 추가되어 PR·main push에서 Maven 검증을 실행하도록 설정되어 있다. 이번 전환에는 CI의 Gradle 검증 명령·캐시 변경도 포함한다. 워크플로 파일의 존재와 실제 GitHub 실행 성공은 구분하며, 실행 결과는 구현 시 확인한다.

이번 단계의 완료는 Gradle 빌드·YAML 설정 전환과 기존 앱의 동작 유지다. 현재 앱에는 진입점·이름 설정·컨텍스트 테스트만 있으며, 공동구매 업무 기능과 HTTP 서버는 후속 계획에서 추가한다. 사용자가 요청한 전체 일괄 기동은 후속 서비스들의 Compose 구성을 연결해 완성한다.

관련 설계: [실행 환경과 초기 설정](../../design/operations.md#runtime), [초기 프로젝트 범위 결정](../000-initial-project.md#scope-decision).

## 제안 구조

```text
group-purchase/
├── settings.gradle                 # 모듈 등록·저장소 기준
├── build.gradle                    # 공통 빌드·플러그인 기준
├── gradlew / gradlew.bat
├── gradle/wrapper/                 # Gradle 버전·배포본 고정
├── services/
│   └── group-purchase-service/
│       ├── build.gradle            # 실행·테스트 의존성과 Boot 태스크
│       └── src/
│           ├── main/java/          # 기존 진입점 이동
│           ├── main/resources/application.yml
│           └── test/java/          # 기존 컨텍스트 테스트 이동
└── Docs/
```

Gradle DSL은 Groovy(`.gradle`)를 초안으로 제안한다. 후속 Config·Eureka·Gateway는 `infrastructure/`, PG Mock은 `test-support/`, 다른 업무 서비스는 `services/` 아래에 두는 방향을 유지한다. 이번에는 실제 존재하는 `group-purchase-service`만 등록하고 `projectDir`로 `services/group-purchase-service`에 연결한다. 이후에도 서비스 이름을 Gradle 프로젝트 경로로 사용한다.

사용자 확인에 따라 서비스 설정은 `application.yml`, 여러 서비스의 일괄 기동은 Docker Compose로 관리한다.

## 작업 범위

1. `settings.gradle`에 모듈·프로젝트 경로·저장소 기준을, 루트 `build.gradle`에 공통 빌드·플러그인 버전 기준을 둔다. 루트는 실행 앱으로 패키징하지 않는다.
2. 현재 `src/`를 `services/group-purchase-service/src/`로 이동한다. 패키지와 클래스명·애플리케이션 이름은 유지한다.
3. 실행 모듈의 `build.gradle`에 Java 21 toolchain, 기존 starter·테스트 의존성, Spring Boot BOM 기준 의존성 관리, JUnit Platform과 Boot 실행·패키징 태스크를 설정한다. 실행 플러그인은 필요한 모듈에만 적용한다.
4. 호환되는 Gradle의 정확한 버전을 정해 Wrapper 스크립트·jar·properties와 배포본 체크섬을 포함한다. 전환 검증 후 `pom.xml`·`mvnw`·`mvnw.cmd`·`.mvn/`을 제거해 빌드 진입점을 정리한다.
5. `application.properties`의 기존 애플리케이션 이름을 YAML 설정으로 옮기고 실제 로딩을 확인한다. 환경별 상세 값은 각 후속 서비스 계획에서 추가한다.
6. `.gitignore`에 Gradle 캐시·빌드 산출물 제외를 추가하고 Wrapper 파일이 추적 가능한지 확인한다. 루트 README의 구조·전체 빌드·단일 모듈 빌드·실행 명령을 갱신하고 구현 결과를 계획과 연결한다.
7. 기존 GitHub Actions 워크플로를 `.github/workflows/ci.yml`로 옮기고 Maven 검증 명령을 `bash ./gradlew build --no-daemon`으로, 캐시를 Gradle로 변경한다. `main` 대상 PR·main push·수동 실행 조건과 `Build and test` 검사 이름은 유지한다. 로컬 검증 후 PR의 실제 CI 결과를 확인한다.

Java 21·Spring Boot 4.0.9는 현재 프로젝트의 기준으로 유지한다. Boot 4.0 Gradle 플러그인의 공식 지원 범위는 Gradle 8.14 이상인 8.x 또는 9.x이며, 정확한 Gradle 패치는 전환 시 확인·고정한다. Cloud 의존성과 연동 검토는 Config 단계에서 다룬다. 도메인 엔티티나 공통 업무 로직을 공유하는 모듈은 이번 빌드 전환의 범위에 포함하지 않는다.

## YAML 설정과 전체 일괄 기동

- 서비스의 기본 실행 설정은 `application.yml`로 관리한다. 환경별 파일은 `application-local.yml`, `application-docker.yml`을 제안하며 프로필 구분과 실제 설정값은 사용하는 단계에서 추가한다.
- Config Server를 추가할 때 공통·서비스별 중앙 설정도 YAML로 관리한다. Config 서버 주소와 서비스 이름 등 최초 접속에 필요한 값은 각 앱의 시작 설정에 둔다.
- 전체 프로세스 기동은 루트 `compose.yml`에 애플리케이션·DB·Kafka 등의 실행 정의를 연결하고 `docker compose up --build -d` 한 명령으로 재현하는 것을 목표로 한다.
- 각 서비스를 구현할 때 해당 이미지 빌드·Compose 항목·필요한 설정을 함께 추가하고 이미 완성한 서비스들의 묶음 실행을 확인한다. 최종 단계에 전체 기동 구성을 한꺼번에 처음 작성하지 않는다.
- 컨테이너 내부 서비스 주소·호스트 실행 주소를 프로필로 구분하고, 준비 상태는 `healthcheck`와 필요한 `depends_on` 조건으로 확인한다. 애플리케이션의 연결 재시도·실행 중 복구도 해당 서비스에서 검증한다.

첫 단계에서는 빌드와 기존 앱의 설정 전환을 검증한다. 첫 서버인 Config 구현 단계부터 이미지·Compose 실행 정의를 추가하고, 전체 서비스의 일괄 기동 완료는 실제 필요한 모듈과 인프라가 연결된 뒤 검증한다. YAML 설정 파일 하나를 작성한 것만으로 여러 서비스가 실행되는 것으로 기록하지 않는다.

## 선택 근거와 대안

Gradle 사용은 사용자 요청으로 정해졌다. 구조 제안은 루트의 단일 Wrapper와 멀티 프로젝트 설정에서 공통 빌드를 관리하고 기존 앱만 이동하는 방식이다. 서비스별 의존성과 실행 파일을 분리하고 루트에서 전체·선택 빌드할 수 있다. 소스 위치·빌드 파일·실행 명령이 바뀌므로 기존 안내를 함께 수정한다.

대안은 서비스마다 별도 Gradle 빌드·Wrapper를 두는 방식이다. 각 빌드의 독립성은 높지만 버전·플러그인 설정과 전체 빌드 절차가 중복된다. 단일 저장소와 전체 실행 목표에 맞춰 공용 Wrapper·멀티 프로젝트 구조를 제안한다. 빌드 DSL은 Groovy 초안이며 Kotlin DSL로 변경할 수 있다.

공통 기반부터 구현하더라도 기존 앱의 이동은 빌드 구조 정리로 취급한다. 임시 실행 모듈을 새로 만들었다가 제거하는 절차를 줄이고, 업무 기능은 공동구매 도메인의 차례에 구현한다.

## 변경 예정 파일

| 경로 | 예정 변경 |
|---|---|
| `settings.gradle`, `build.gradle` | Gradle 모듈·저장소·공통 빌드 설정 추가 |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/**` | 버전·체크섬을 고정한 Wrapper 추가 |
| `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/**` | Gradle 전환 검증 후 기존 Maven 빌드 파일 제거 |
| `src/**` → `services/group-purchase-service/src/**` | 기존 진입점·설정·테스트 이동 |
| `services/group-purchase-service/build.gradle` | 실행 모듈 빌드 추가 |
| `src/main/resources/application.properties` → `services/group-purchase-service/src/main/resources/application.yml` | 모듈 이동과 함께 기존 설정을 YAML로 전환 |
| `.gitignore` | `.gradle/`·`build/` 등 캐시·산출물 제외 |
| `.github/workflows/maven.yml` → `.github/workflows/ci.yml` | Gradle 빌드·테스트와 캐시로 전환. 기존 실행 조건·검사 이름 유지 |
| `README.md` | 구조와 빌드·실행 명령 갱신 |
| `Docs/implementation/001-multi-module-build.md` | 실제 변경·명령·검증 결과 작성 |
| `Docs/implementation/README.md`와 계획·프로젝트 개요 | 결과 링크·진행 상태 반영 |

## 검증 방법과 완료 기준

아래는 구현 후 수행할 계획이며 현재 실행 결과가 아니다.

| 검증 | 예정 명령·판정 |
|---|---|
| Wrapper와 버전 | `.\gradlew.bat --version`이 고정한 Gradle·Java 버전으로 실행됨 |
| 전체 빌드 | 루트에서 `.\gradlew.bat clean build`. 등록 모듈이 빌드되고 기존 컨텍스트 테스트 통과 |
| 선택 모듈 테스트·빌드 | `.\gradlew.bat :group-purchase-service:test :group-purchase-service:build`. 선택 모듈의 테스트·패키징 성공 |
| 개발 실행·설정 로딩 | `.\gradlew.bat :group-purchase-service:bootRun`. YAML에 둔 기존 앱 이름 등 설정이 실제 반영되고 컨텍스트 시작 |
| 앱 실행 | 생성된 모듈 jar를 `java -jar`로 실행해 기존 앱처럼 컨텍스트가 정상 시작하는지 확인 |
| 패키징 경계 | 루트에 실행 jar가 생기지 않고 서비스의 `build/libs/`에 실행 가능한 Boot jar가 생성되는지 확인 |
| GitHub Actions | PR에서 `Build and test`가 Gradle 명령으로 실행되어 성공하는지 확인. 실행 로그·커밋을 결과 기록에 연결하고 실제 확인 전에는 CI 통과로 표시하지 않음 |
| 안내 재현 | README의 작업 디렉터리·명령·파일 경로와 실제 결과 일치 |

현재 starter 앱은 웹 서버를 실행하지 않으므로 HTTP 요청 성공은 이번 완료 기준에 포함하지 않는다. 빌드 실패나 기동 실패가 있으면 원인과 해결·재검증 결과를 구현 기록에 남긴다. 기존 테스트를 이동해 회귀를 확인하며 파일 이동 자체를 확인하는 별도 테스트는 추가하지 않는다.

## 검토 의견과 반영

- 2026-10-29: 사용자 요청에 따라 하나의 공통 구성·도메인 기능씩 계획 검토 후 구현하는 흐름으로 초안 작성.
- 2026-10-29 사용자 수정 의견: Maven 대신 Gradle을 사용하고 YAML로 설정을 관리하며 나중에 전체를 한 번에 기동하고 싶다는 요청 반영.
- 반영 내용: Gradle Wrapper·멀티 프로젝트·전환 검증으로 재작성. 애플리케이션 설정의 YAML 전환과 서비스 추가 시 Compose 연결 계획 추가.
- 2026-10-29 사용자 확인: 서비스 설정은 `application.yml`, 전체 일괄 기동은 Docker Compose를 뜻한 것으로 확정. 파일명 확인 대기와 별도 설정 이름 지정 방안을 제거.
- 2026-10-29 CI 추가 후 계획 검토: 사용자가 생성한 Maven용 GitHub Actions 워크플로를 확인했다. 첫 전환 범위에 Gradle CI 명령·캐시 변경과 실제 실행 결과 확인을 추가했다. GitHub의 Ruleset 적용 여부와 CI 성공 여부는 아직 확인하지 않았다.
- 2026-10-29 구현 착수: 사용자의 "ok 진행하자" 요청으로 001의 Gradle 멀티 모듈·YAML·기존 CI 전환, 검증·결과 기록 범위 진행에 합의했다. Groovy DSL과 기존 앱의 서비스 모듈 이동을 적용한다. 후속 Config·업무 기능 구현과 main 병합은 이번 단계에 포함하지 않는다.
- 2026-10-29 구현 완료: Gradle 9.8.2로 고정하고 전체·선택 빌드, 기존 테스트의 YAML 검증, bootRun·실행 JAR, PR의 Ubuntu CI를 확인했다. 검증 커밋·실행 링크는 [001 결과](../001-multi-module-build.md)에 기록했다.

합의한 Gradle 멀티 프로젝트·Groovy DSL과 기존 앱의 `services/group-purchase-service/` 이동을 적용한다. 환경별 프로필과 단계별 Compose 연결은 사용하는 서비스의 후속 계획에서 구체화한다.

## 참고

- [Gradle 멀티 프로젝트 빌드](https://docs.gradle.org/current/userguide/multi_project_builds.html)
- [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- [Spring Boot 4.0 Gradle 플러그인](https://docs.spring.io/spring-boot/4.0/gradle-plugin/index.html)
- [Spring Boot 4.0 외부 설정](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html)
- [Docker Compose up](https://docs.docker.com/reference/cli/docker/compose/up/)
- [Compose 시작 순서와 준비 상태](https://docs.docker.com/compose/how-tos/startup-order/)

[전체 구현 순서](roadmap.md) · [계획 목록](../README.md#workflow)
