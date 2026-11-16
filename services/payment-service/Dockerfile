# syntax=docker/dockerfile:1

# ───────────── Stage 1: build the JAR ─────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependencies first: this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

# Then the source. Tests run in CI, not here (they'd need Docker inside the build).
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ───────────── Stage 2: the runtime image ─────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run as root: an exploit would land as a powerless user.
RUN groupadd --system app && useradd --system --gid app --no-create-home app

COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080

# Size the heap from the container's memory limit, not the host machine's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]