# cli-proxy-logger (Node) — zero runtime deps, just the Node runtime + source.
FROM node:20-slim

WORKDIR /app

# No node_modules to install (the project uses only Node built-ins).
COPY package.json README.md ./
COPY src ./src
COPY public ./public

# Expose on all interfaces inside the container so published ports are reachable.
ENV BIND_ADDR=0.0.0.0 \
    PROXY_PORT=8788 \
    UI_PORT=8789 \
    LOG_DIR=/app/logs

EXPOSE 8788 8789

CMD ["node", "src/index.js"]
