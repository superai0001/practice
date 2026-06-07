# cli-proxy-logger (Python) — pure standard library, no pip install needed.
FROM python:3.12-slim

WORKDIR /app

COPY cli_proxy_logger ./cli_proxy_logger
COPY public ./public
COPY README.md ./

# Expose on all interfaces inside the container so published ports are reachable.
ENV BIND_ADDR=0.0.0.0 \
    PROXY_PORT=8788 \
    UI_PORT=8789 \
    LOG_DIR=/app/logs \
    PYTHONUNBUFFERED=1

EXPOSE 8788 8789

CMD ["python", "-m", "cli_proxy_logger"]
