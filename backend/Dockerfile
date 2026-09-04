# Mini App build stage
# reproducibility-base: stage=miniapp-build tag=node:20-bookworm-slim index=sha256:2cf067cfed83d5ea958367df9f966191a942351a2df77d6f0193e162b5febfc0 linux-amd64=sha256:3d0f05455dea2c82e2f76e7e2543964c30f6b7d673fc1a83286736d44fe4c41c
FROM node:20-bookworm-slim@sha256:2cf067cfed83d5ea958367df9f966191a942351a2df77d6f0193e162b5febfc0 AS miniapp-build
WORKDIR /workspace/miniapp

ARG VITE_BACKEND_PUBLIC_URL=http://localhost:8080
ENV VITE_BACKEND_PUBLIC_URL=$VITE_BACKEND_PUBLIC_URL

COPY miniapp/package.json miniapp/package-lock.json ./
RUN npm ci

COPY miniapp ./
RUN npm run build

# Backend build stage
# reproducibility-base: stage=build tag=eclipse-temurin:21-jdk index=sha256:85f00967bcc624fc19fa9c2cf124ea426a5363898e267141726f31f358c2e14b linux-amd64=sha256:a16bfc04b28f66c1d27218ffeefc8ae6a2621de423e01597156ba813ca5fa668
FROM eclipse-temurin:21-jdk@sha256:85f00967bcc624fc19fa9c2cf124ea426a5363898e267141726f31f358c2e14b AS build
WORKDIR /workspace
ARG GRADLE_JVM_ARGS="-Xmx2048m -XX:MaxMetaspaceSize=768m"
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=${GRADLE_JVM_ARGS} -Dkotlin.daemon.jvm.options=${GRADLE_JVM_ARGS}"

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY backend/build.gradle.kts backend/build.gradle.kts
COPY backend/app/build.gradle.kts backend/app/build.gradle.kts

RUN chmod +x ./gradlew
RUN --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew --version --no-daemon
RUN --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.gradle/caches \
    ./gradlew :backend:app:dependencies --configuration runtimeClasspath --no-daemon -Pkotlin.daemon.jvmargs="${GRADLE_JVM_ARGS}"

COPY backend backend

RUN --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.gradle/caches \
    ./gradlew :backend:app:installDist --no-daemon -Pkotlin.daemon.jvmargs="${GRADLE_JVM_ARGS}"

# Runtime stage
# reproducibility-base: stage=runtime tag=eclipse-temurin:21-jre index=sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037 linux-amd64=sha256:d1d7f43094ea37e9ae77ea3ca40026bc1de1bf1dc2cad37b08d66b9a016a2ea6
FROM eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037 AS runtime
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
