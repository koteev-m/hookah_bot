# Mini App build stage
FROM node:20-bookworm-slim AS miniapp-build
WORKDIR /workspace/miniapp

ARG VITE_BACKEND_PUBLIC_URL=http://localhost:8080
ENV VITE_BACKEND_PUBLIC_URL=$VITE_BACKEND_PUBLIC_URL

COPY miniapp/package.json miniapp/package-lock.json ./
RUN npm ci

COPY miniapp ./
RUN npm run build

# Backend build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
ARG GRADLE_JVM_ARGS="-Xmx2048m -XX:MaxMetaspaceSize=768m"
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=${GRADLE_JVM_ARGS} -Dkotlin.daemon.jvm.options=${GRADLE_JVM_ARGS}"

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY backend backend

RUN chmod +x ./gradlew
RUN ./gradlew :backend:app:installDist --no-daemon -Pkotlin.daemon.jvmargs="${GRADLE_JVM_ARGS}"

# Runtime stage
FROM eclipse-temurin:21-jre
ENV APP_HTTP_PORT=8080
ENV MINIAPP_DEV_SERVER_URL=
ENV MINIAPP_STATIC_DIR=/app/miniapp
WORKDIR /app

RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=build /workspace/backend/app/build/install/app ./app
COPY --from=miniapp-build /workspace/miniapp/dist ./miniapp

USER appuser

EXPOSE 8080

CMD ["/app/app/bin/app"]
