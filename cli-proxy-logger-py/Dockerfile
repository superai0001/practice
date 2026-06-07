# cli-proxy-logger (Python) — pure standard library, no pip install needed.

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

# ---- app image ----
FROM python:3.12-slim

WORKDIR /app

COPY cli_proxy_logger ./cli_proxy_logger
COPY public ./public
COPY README.md ./

# Bundled kernel binaries (empty dir if WITH_KERNEL=0); auto-discovered at <app>/vendor.
COPY --from=kernel /vendor ./vendor

# Expose on all interfaces inside the container so published ports are reachable.
ENV BIND_ADDR=0.0.0.0 \
    PROXY_PORT=8788 \
    UI_PORT=8789 \
    LOG_DIR=/app/logs \
    PYTHONUNBUFFERED=1

EXPOSE 8788 8789

CMD ["python", "-m", "cli_proxy_logger"]
