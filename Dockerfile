# Multi-stage build: compile the app in a JDK image, then run the jar in a
# smaller JRE image. The final image contains only the runtime + the app jar.

# --- Stage 1: build the bootable jar ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy the Gradle wrapper + build scripts first so dependency download can be
# cached as a layer when only source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

# Then the sources, and build. We skip tests here — CI runs the full suite
# separately (a Docker image build is not the place to spin up Testcontainers).
COPY src ./src
RUN ./gradlew bootJar --no-daemon \
    && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)" /app/app.jar

# --- Stage 2: minimal runtime ---
FROM eclipse-temurin:25-jre
WORKDIR /app

# Run as a non-root user (good container hygiene).
RUN useradd --system --uid 10001 appuser
COPY --from=build /app/app.jar /app/app.jar
USER appuser

# Documentation only; the app actually listens on $PORT (Render sets it) or 8080.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
