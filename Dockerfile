# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
COPY src src
# The app starts from unpacked classes via JarLauncher, like the bootBuildImage (Paketo) image.
# Liquibase then records changelogs as BOOT-INF/classes/io/jmix/..., so this image works with
# databases migrated by bootBuildImage. It fails ("relation ... already exists") on a database
# migrated by bootRun or java -jar, which record io/jmix/..., and vice versa.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.vaadin \
    --mount=type=cache,target=/root/.npm \
    chmod +x gradlew \
    && ./gradlew --no-daemon bootJar -Pvaadin.productionMode=true \
    && cp build/libs/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -h /app app
WORKDIR /app
USER app

COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/entrypoint.sh

COPY --from=builder --chown=app /build/extracted/dependencies/ ./
COPY --from=builder --chown=app /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=app /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app /build/extracted/application/ ./

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["entrypoint.sh"]
