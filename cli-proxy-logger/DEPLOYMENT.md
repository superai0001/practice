# 部署手册 — cli-proxy-logger（Node 版）

本文件是 **Node 版** 的独立部署手册，自包含、与 Python/Java 版无关。涵盖：配置项速查、各环境实施步骤（本机 / 离线内网 / Linux systemd / Windows nssm / Docker / 单文件 exe）、内核出站的构建与启用。

- **运行时**：Node ≥ 18（实测 v20.19.0），**零第三方依赖**（仅用 Node 内置模块，无需 `npm install`）
- **端口**：代理 `8788`、Web UI `8789`（均可改）
- **监听地址**：默认 `127.0.0.1`，需对外/容器内访问设 `BIND_ADDR=0.0.0.0`

---

## 1. 配置项速查（环境变量，全部可选）

| 变量 | 默认 | 说明 |
|------|------|------|
| `PROXY_PORT` | `8788` | 代理监听端口 |
| `UI_PORT` | `8789` | Web UI 端口 |
| `BIND_ADDR` | `127.0.0.1` | 代理 + UI 监听地址；对外/容器内访问设 `0.0.0.0` |
| `LOG_DIR` | `./logs` | JSONL 日志目录（相对启动目录） |
| `REDACT_AUTH` | 开启 | 落盘脱敏 `x-api-key`/`authorization`；设 `0` 关闭 |
| `ANTHROPIC_UPSTREAM` | `https://api.anthropic.com` | Anthropic 上游 |
| `OPENAI_UPSTREAM` | `https://api.openai.com` | OpenAI 上游 |
| `MAX_BODY_BYTES` | `2000000` | 单条 body 落盘上限，超出截断 |
| `ANTHROPIC_COMPAT` | 关闭 | 设 `chat` 开启协议翻译（Anthropic `/v1/messages` → OpenAI `/v1/chat/completions`） |
| `MODEL_MAP` / `MODEL_MAP_FILE` | 空 | 模型名映射（JSON 或逗号分隔 / 文件路径） |
| `PROVIDERS` / `PROVIDERS_FILE` | 空 | 多供应商池（配了即启用故障转移 + 熔断） |
| `BREAKER` / `BREAKER_FAILURES` / `BREAKER_COOLDOWN_MS` / `BREAKER_HALFOPEN_MAX` | 见 README | 熔断器参数 |
| `FAILOVER_STATUSES` | `429,500,502,503,504` | 触发故障转移的上游状态码 |
| `RECTIFY` / `RECTIFY_SIGNATURE` / `RECTIFY_BUDGET` | 关闭/开启 | Anthropic thinking 整流 |
| `TOOL_NAME_CASE` / `TOOL_NAME_*` / `TOOL_NAME_MAP(_FILE)` | 见 README | 工具名规范化 |
| `FILTERS` / `FILTERS_FILE` | 空 | 请求过滤器/规则 |
| `UPSTREAM_PROXY` | 空 | 出站代理：普通 `http(s)://`/`socks5://`（可带 `user:pass@`），或**高级协议分享链接**（见 §3） |
| `PROXY_KERNEL` | `auto` | 内核选择：`auto`/`xray`/`sing-box` |
| `PROXY_KERNEL_CONFIG` | 空 | 完整原生内核配置 JSON 路径（设了即启用内核） |
| `XRAY_BIN` / `SING_BOX_BIN` | 空 | 内核二进制路径；不设则按 PATH 与同级 `vendor/` 查找 |
| `PROXY_KERNEL_SOCKS_PORT` | 随机 | 内核本地 socks inbound 端口 |

> 字段全集与详细语义见 [README.md](./README.md)。

---

## 2. 各环境实施步骤

### A. 本机 / 快速启动

```bash
cd cli-proxy-logger
node src/index.js            # 等价 npm start
# [proxy] listening on http://127.0.0.1:8788
# [ui]    open http://127.0.0.1:8789
```

带配置启动（示例）：

```bash
PROXY_PORT=8788 UI_PORT=8789 LOG_DIR=./logs \
OPENAI_UPSTREAM=https://your-gateway/v1 \
node src/index.js
```

### B. 离线内网部署（拷目录 + 离线运行时）

因零依赖，内网部署只需「目录 + Node 运行时」，**不需要联网、不需要 `npm install`**。

1. **准备 Node 离线包**：在联网机器从 nodejs.org 下与内网 OS 匹配的免安装包（Windows `node-v20.x.x-win-x64.zip`，Linux `node-v20.x.x-linux-x64.tar.xz`），拷进内网解压，把 `node`/`node.exe` 所在目录加入 `PATH`。
2. **打包工程**：
   ```bash
   bash scripts/package.sh                                          # Linux/macOS → dist/cli-proxy-logger-node.tar.gz
   powershell -ExecutionPolicy Bypass -File scripts\package.ps1     # Windows     → dist\cli-proxy-logger-node.zip
   ```
   把压缩包拷进内网解压（直接拷整个目录也行）。
3. **运行**：见上面 A。

### C. 常驻后台 · Linux（systemd）

用仓库模板 `deploy/cli-proxy-logger.service`：改好里面的 `WorkingDirectory`/`ExecStart`（node 路径）/端口/上游环境变量，然后：

```bash
sudo cp deploy/cli-proxy-logger.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cli-proxy-logger
journalctl -u cli-proxy-logger -f          # 看日志
```

### D. 常驻后台 · Windows（nssm）

装好 [nssm](https://nssm.cc/) 后，以管理员 PowerShell 运行模板脚本：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\install-nssm.ps1
# 卸载：nssm remove cli-proxy-logger confirm
```

临时跑：Linux `nohup node src/index.js > proxy.out 2>&1 &`；Windows `start /b node src/index.js`。

### E. Docker / docker-compose

仓库内置 `Dockerfile`（`node:20-slim`）+ `docker-compose.yml`。镜像内已默认 `BIND_ADDR=0.0.0.0`。**镜像构建时会自动下载官方 xray + sing-box 的 Linux 二进制打进 `/app/vendor`（约 80MB），所以容器开箱即可走高级协议，无需自己准备内核**。

```bash
cd cli-proxy-logger
docker compose up -d --build
# 代理 http://<host>:8788   UI http://<host>:8789   日志落 ./logs
docker compose logs -f
docker compose down
```

不想把内核打进镜像（镜像更小）：`docker compose build --build-arg WITH_KERNEL=0`，或在 compose 的 `build.args` 里设 `WITH_KERNEL: "0"`。

不用 compose：

```bash
docker build -t cli-proxy-logger-node .
docker run -d --name cli-proxy-logger -p 8788:8788 -p 8789:8789 \
  -e BIND_ADDR=0.0.0.0 -v "$PWD/logs:/app/logs" cli-proxy-logger-node
```

**容器内走内核**：默认已把官方内核打进镜像（见上），你只需把 `UPSTREAM_PROXY` 指向高级协议分享链接（compose 里有注释示例）。若要用**你自己的** Linux 版二进制覆盖内置的，把它放到宿主机 `./vendor/`，在 `docker-compose.yml` 里取消注释 `./vendor/xray:/app/vendor/xray:ro` 等挂载行即可。

### F. 单文件 exe（说明）

本变体源码是 **ESM + 顶层 await + `import.meta`**，因此 Node 内置 SEA（要求单个 CommonJS 入口）与 `esbuild --format=cjs` 都**不能直接打**（顶层 await 进不了 CJS）。Node 版的免运行时分发建议：

- **Docker 镜像**（见 E，跨平台、最省心）；或
- **目录压缩包**（见 B 的 `scripts/package.*`，目标机只需装 Node 20）。

若确实要一个**免装运行时的单文件 .exe**，最干净的是改用 **Python 变体的 PyInstaller** 路径（三套功能等价，Python 版纯标准库可直接 `--onefile` 打成 exe）。

---

## 3. 内核出站（xray / sing-box）的获取与启用

让代理支持 VMess/VLESS/Trojan/Shadowsocks/Hysteria2/TUIC 等高级协议：内核作为本地 SOCKS5 前置层由本服务自动拉起，**你只需服务起一个**，外加内核二进制能被找到。**注意：内核一般无需自己编译——直接下载官方预编译二进制即可（见下 A/B）**。

二进制查找顺序：显式路径（`XRAY_BIN`/`SING_BOX_BIN`）→ 同级 `vendor/`（`xray`/`sing-box`，Windows 带 `.exe`）→ 系统 PATH。下面三种获取方式任选其一（**绝大多数情况选 A，不用装 Go、不用编译**）。

#### A. 一键下载官方预编译二进制（推荐，已实测）

仓库自带 `scripts/fetch-kernel.*`：按当前 OS/arch 从 GitHub 官方 Releases 拉 xray + sing-box 到 `./vendor/`，应用会自动发现。

```bash
bash scripts/fetch-kernel.sh                                       # Linux/macOS，两个内核都拉
powershell -ExecutionPolicy Bypass -File scripts\fetch-kernel.ps1  # Windows
# 只要一个： fetch-kernel.sh xray  或  fetch-kernel.sh sing-box
```

#### B. 手动下载官方 Release

到官方 Releases 下对应平台的压缩包，解压把 `xray`/`sing-box`（Windows 带 `.exe`）丢进 `./vendor/` 或用 `XRAY_BIN`/`SING_BOX_BIN` 指向：
- xray：<https://github.com/XTLS/Xray-core/releases>
- sing-box：<https://github.com/SagerNet/sing-box/releases>

#### C. 从源码自行构建（仅在需要特定版本/特性时）

```bash
# xray-core（用较新的 Go；本项目用 Go 1.26 验过）
git clone https://github.com/XTLS/Xray-core && cd Xray-core
go build -o xray ./main            # Windows 产 xray.exe

# sing-box（必须 Go 1.24.x，见下方坑）
git clone https://github.com/SagerNet/sing-box && cd sing-box
go build -tags "with_utls,with_quic" -o sing-box ./cmd/sing-box
```

> **坑**：sing-box 的 `badtls` 用 `//go:linkname` 引用 `crypto/tls` 内部方法，**Go 1.26 改了相关签名导致链接失败**，必须用 **Go 1.24.x** 且带 `with_utls,with_quic` tag。xray 用 Go 1.26 正常。

### 3.2 启用（两种用法）

**用法 A：直接给分享链接**（最省事）——`UPSTREAM_PROXY` 识别到高级协议链接就自动拉起内核：

```bash
export XRAY_BIN="/path/to/xray"
export UPSTREAM_PROXY='vless://<uuid>@example.com:443?encryption=none&security=tls&sni=...&type=ws&host=...&path=%2F#node'
export PROXY_KERNEL=auto         # auto 默认 xray；hy2/tuic 自动 sing-box
node src/index.js
# 日志可见 kernel ready: SOCKS5 127.0.0.1:xxxxx -> ...
```

**用法 B：给一份完整原生配置**（高级）——`PROXY_KERNEL_CONFIG` 指向 xray/sing-box 的原生 JSON，服务会自动确保有一个回环 socks inbound 再路由过去：

```bash
PROXY_KERNEL=sing-box PROXY_KERNEL_CONFIG=./my-singbox.json node src/index.js
```

内核子进程随主进程退出自动关闭，临时配置文件自动清理。完整设计/实施过程见 [../KERNEL_INTEGRATION.md](../KERNEL_INTEGRATION.md)。

---

## 4. 网络 / 安全

- 代理与 UI 默认监听 `127.0.0.1`；需对外时设 `BIND_ADDR=0.0.0.0`（Docker 已默认）。
- CLI 的 base URL 通常指向 `127.0.0.1`，**代理一般与 CLI 同机部署**。把端口暴露到内网其他机器时请自行加访问控制（鉴权头虽落盘脱敏，但内存/转发链路上是明文）。
