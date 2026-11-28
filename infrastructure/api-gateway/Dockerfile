FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY infrastructure/config-server ./infrastructure/config-server
COPY infrastructure/eureka-server ./infrastructure/eureka-server
COPY infrastructure/api-gateway ./infrastructure/api-gateway
COPY infrastructure/pg-mock/build.gradle ./infrastructure/pg-mock/build.gradle
COPY services/group-purchase-service ./services/group-purchase-service
COPY services/member-service ./services/member-service
RUN bash ./gradlew :api-gateway:bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/infrastructure/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8000 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
