# The deployable is the fat jar (spec §5.2 — GraalVM has no Java 26 build, so native image
# is not on the table; a JRE running the jar is the whole story). Built outside the image
# rather than in a multi-stage build here, so a deploy is "build once with Gradle, ship the
# same jar everywhere" instead of rebuilding — and re-downloading every dependency — inside
# the image on every deploy.
#
# Build: ./gradlew :ctms-app:bootJar   (from backend/, produces ctms-app/build/libs/*.jar)
# Image: docker build -t ctms-app -f Dockerfile .   (from backend/)
FROM eclipse-temurin:26-jre

WORKDIR /app
COPY ctms-app/build/libs/ctms-app-*.jar app.jar

# Graceful shutdown (server.shutdown: graceful in application.yml) needs SIGTERM to reach the
# JVM directly rather than a shell PID 1 swallowing it.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
