# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY backend backend

RUN chmod +x ./gradlew
RUN ./gradlew :backend:app:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre
ENV APP_HTTP_PORT=8080
WORKDIR /app

RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=build /workspace/backend/app/build/install/app ./app

USER appuser

EXPOSE 8080

CMD ["/app/app/bin/app"]
