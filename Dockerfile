# Multi-stage build: compile stage (with JDK) + runtime stage (JRE only)
# This allows Render to build the JAR from source during deployment.

# Stage 1: Build the JAR
FROM eclipse-temurin:26-jdk AS builder
WORKDIR /build

# Copy only gradle wrapper and settings first (for layer caching)
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./

# Copy source code
COPY ctms-common/ ctms-common/
COPY ctms-persistence/ ctms-persistence/
COPY ctms-security/ ctms-security/
COPY ctms-clinical/ ctms-clinical/
COPY ctms-trials/ ctms-trials/
COPY ctms-documents/ ctms-documents/
COPY ctms-analytics/ ctms-analytics/
COPY ctms-ethics/ ctms-ethics/
COPY ctms-gis/ ctms-gis/
COPY ctms-safety/ ctms-safety/
COPY ctms-app/ ctms-app/

# Build the JAR
RUN ./gradlew :ctms-app:bootJar -x test

# Stage 2: Runtime with JRE only
FROM eclipse-temurin:26-jre
WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /build/ctms-app/build/libs/ctms-app-*.jar app.jar

# Graceful shutdown (server.shutdown: graceful in application.yml) needs SIGTERM to reach the
# JVM directly rather than a shell PID 1 swallowing it.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
