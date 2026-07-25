# syntax=docker/dockerfile:1

# ─────────────────────────────────────────────────────────────
# Stage 1: Build the fat jar with Maven
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy the Maven wrapper + pom first so dependency resolution is cached
# and only re-runs when the pom changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Now copy sources and build (tests are run in CI, not in the image build)
COPY src ./src
RUN ./mvnw -B -q clean package -DskipTests

# ─────────────────────────────────────────────────────────────
# Stage 2: Grab a static Docker CLI (used to run code-exec sandboxes
# against the host daemon via the mounted socket)
# ─────────────────────────────────────────────────────────────
FROM docker:27-cli AS dockercli

# ─────────────────────────────────────────────────────────────
# Stage 3: Slim runtime image
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# Docker CLI so CodeExecutionService can shell out to `docker run`
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker

COPY --from=build /app/target/realtime-editor-*.jar app.jar

EXPOSE 8080

# JVM container ergonomics: honor cgroup memory limits
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
