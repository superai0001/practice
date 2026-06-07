# cli-proxy-logger (Java / Spring Boot) — multi-stage: build fat jar, then run on a JRE.
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /src
# Cache dependencies first (re-resolved only when pom.xml changes).
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- optional kernel stage: bake official xray / sing-box Linux binaries in ----
# Controlled by --build-arg WITH_KERNEL=1 (default). Set WITH_KERNEL=0 to skip the
# ~80MB download and ship without a kernel (plain http/socks upstream still works).
FROM debian:stable-slim AS kernel
ARG WITH_KERNEL=1
ARG TARGETARCH=amd64
RUN if [ "$WITH_KERNEL" = "1" ]; then \
      apt-get update && apt-get install -y --no-install-recommends curl ca-certificates unzip tar && rm -rf /var/lib/apt/lists/*; \
      case "$TARGETARCH" in \
        amd64) XA=64; SA=amd64 ;; \
        arm64) XA=arm64-v8a; SA=arm64 ;; \
        *) echo "unsupported TARGETARCH=$TARGETARCH"; exit 1 ;; \
      esac; \
      mkdir -p /vendor; \
      curl -fL --retry 3 -o /tmp/xray.zip "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-${XA}.zip"; \
      unzip -o /tmp/xray.zip -d /tmp/xray; cp /tmp/xray/xray /vendor/xray; \
      TAG="$(curl -fsSL https://api.github.com/repos/SagerNet/sing-box/releases/latest | grep '"tag_name"' | head -1 | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')"; \
      VER="${TAG#v}"; \
      curl -fL --retry 3 -o /tmp/sb.tgz "https://github.com/SagerNet/sing-box/releases/download/${TAG}/sing-box-${VER}-linux-${SA}.tar.gz"; \
      tar -xzf /tmp/sb.tgz -C /tmp; cp "/tmp/sing-box-${VER}-linux-${SA}/sing-box" /vendor/sing-box; \
      chmod +x /vendor/xray /vendor/sing-box; \
    else \
      mkdir -p /vendor; \
    fi

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /src/target/cli-proxy-logger-*.jar app.jar

# Bundled kernel binaries (empty dir if WITH_KERNEL=0); the app probes ./vendor (cwd=/app).
COPY --from=kernel /vendor ./vendor

# Spring Boot binds all interfaces by default; proxy + UI share one port (8788).
ENV LOG_DIR=/app/logs
EXPOSE 8788

ENTRYPOINT ["java", "-jar", "app.jar"]
