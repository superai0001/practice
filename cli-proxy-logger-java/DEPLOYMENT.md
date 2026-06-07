# 部署手册 — cli-proxy-logger（Java / Spring Boot 版）

本文件是 **Java 版** 的独立部署手册，自包含、与 Node/Python 版无关。涵盖：配置项速查、各环境实施步骤（本机 / 离线内网 / Linux systemd / Windows nssm / Docker / exe）、内核出站的构建与启用。

- **运行时**：JRE/JDK ≥ 8（工具默认 `java.version=8`；JDK 8 / 11 / 17 都能编译运行，实测 Temurin 1.8.0_492）
- **构建**：Maven（产出**可执行 fat jar**，已内嵌 Spring Boot + Tomcat + Jackson + 静态 UI）
- **端口**：代理 + Web UI **共用** `8788`（可改）
- **监听地址**：Spring Boot 默认监听所有网卡（Docker 下正需如此）；只想本机访问加 `--server.address=127.0.0.1`

---

## 1. 配置项速查（`application.yml`，前缀 `proxy.*`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `server.port` | `8788` | 代理 + UI 端口 |
| `server.address` | 全部网卡 | 监听地址；只想本机访问设 `127.0.0.1` |
| `proxy.anthropic-upstream` | `https://api.anthropic.com` | Anthropic 上游 |
| `proxy.openai-upstream` | `https://api.openai.com` | OpenAI 上游 |
| `proxy.log-dir` | `./logs` | JSONL 日志目录 |
| `proxy.redact-auth` | `true` | 落盘脱敏 `x-api-key`/`authorization` |
| `proxy.max-body-bytes` | `2000000` | 单条 body 落盘上限 |
| `proxy.anthropic-compat` | 空 | 设 `chat` 开启协议翻译（Anthropic `/v1/messages` → OpenAI `/v1/chat/completions`） |
| `proxy.model-map` / `proxy.model-map-file` | 空 | 模型名映射 |
| `proxy.providers` / `proxy.providers-file` | 空 | 多供应商池（配了即启用故障转移 + 熔断） |
| `proxy.breaker` / `proxy.breaker-failures` / `proxy.breaker-cooldown-ms` / `proxy.breaker-half-open-max` | 见 README | 熔断器参数 |
| `proxy.failover-statuses` | `429,500,502,503,504` | 触发故障转移的上游状态码 |
| `proxy.rectify` / `proxy.rectify-signature` / `proxy.rectify-budget` | 关闭/开启 | Anthropic thinking 整流 |
| `proxy.tool-name-case` / `proxy.tool-name-*` / `proxy.tool-name-map(-file)` | 见 README | 工具名规范化 |
| `proxy.filters` / `proxy.filters-file` | 空 | 请求过滤器/规则 |
| `proxy.upstream-proxy` | 空 | 出站代理：普通 `http(s)://`/`socks5://`，或**高级协议分享链接**（见 §3） |
| `proxy.proxy-kernel` | `auto` | 内核选择：`auto`/`xray`/`sing-box` |
| `proxy.proxy-kernel-config` | 空 | 完整原生内核配置 JSON 路径（设了即启用内核） |
| `proxy.xray-bin` / `proxy.sing-box-bin` | 空 | 内核二进制路径；不设则按 PATH 与同级 `vendor/` 查找 |
| `proxy.proxy-kernel-socks-port` | 随机 | 内核本地 socks inbound 端口 |

**三种配置方式**（优先级：命令行 > 环境变量 > `application.yml`）：

```bash
# 1) 命令行参数
java -jar target/cli-proxy-logger-1.0.0.jar --server.port=8788 --proxy.openai-upstream=https://gw/v1
# 2) 环境变量（Spring relaxed binding；也兼容裸名）
PROXY_OPENAI_UPSTREAM=https://gw/v1 UPSTREAM_PROXY=socks5://127.0.0.1:1080 java -jar ...
# 3) jar 同级放 application.yml（或 ./config/application.yml），启动自动加载覆盖——改配置不用重新打包
```

> 内核相关项的环境变量回退用**裸名**（与 Node/Python 一致）：`PROXY_KERNEL`、`PROXY_KERNEL_CONFIG`、`XRAY_BIN`、`SING_BOX_BIN`、`PROXY_KERNEL_SOCKS_PORT`、`UPSTREAM_PROXY`。字段全集见 [README.md](./README.md)。

---

## 2. 各环境实施步骤

### A. 本机 / 快速启动

```bash
cd cli-proxy-logger-java
mvn -q -DskipTests package
java -jar target/cli-proxy-logger-1.0.0.jar
# 代理 + UI: http://127.0.0.1:8788
```

### B. 离线内网部署（拷 fat jar + JRE）

Java 版有三方依赖（Spring Boot / Tomcat / Jackson），但都被打进**一个 fat jar**，所以内网部署只需「jar + JRE」，**不需要 Maven、不需要联网**。

1. **在联网机器构建 fat jar**：
   ```bash
   bash scripts/package.sh                                          # Linux/macOS → dist/ 内含 jar + 样例 application.yml
   powershell -ExecutionPolicy Bypass -File scripts\package.ps1     # Windows
   # 或手动：mvn -DskipTests package  → target/cli-proxy-logger-1.0.0.jar（实测约 17 MB）
   ```
   > 构建机 JDK 版本要 **≥ `java.version`（默认 8）**。JDK 8 构建出 Java 8 字节码，可在 8/11/17 上跑；JDK 17 构建且 `java.version=8` 也产 Java 8 字节码。
2. **准备 JRE**：内网备 **JRE/JDK 8+**（Temurin/Adoptium、Zulu、Microsoft OpenJDK 等离线包均可）。**不要 Maven、不要源码**——只这个 jar + JRE。
3. **拷贝并运行**：
   ```bash
   java -jar cli-proxy-logger-1.0.0.jar
   java -jar cli-proxy-logger-1.0.0.jar --server.port=8788 \
        --proxy.openai-upstream=https://内网网关/v1 --proxy.log-dir=/var/log/cli-proxy
   ```
   也可在 jar 同级放 `application.yml`（仓库附 `deploy/application.yml.sample`）覆盖配置，无需重新打包。

> 若**必须在内网用 Maven 构建**（不推荐）：联网机 `mvn -DskipTests package dependency:go-offline` 预热 `~/.m2/repository`，整个拷到内网同路径，再 `mvn -o package` 离线构建。直接拷 fat jar 更省事。

### C. 常驻后台 · Linux（systemd）

用仓库模板 `deploy/cli-proxy-logger.service`：把 jar 放到 `/opt/cli-proxy-logger-java/`，改好里面 java 路径/端口/上游，然后：

```bash
sudo cp deploy/cli-proxy-logger.service /etc/systemd/system/cli-proxy-logger-java.service
sudo systemctl daemon-reload
sudo systemctl enable --now cli-proxy-logger-java
journalctl -u cli-proxy-logger-java -f     # 看日志
```

### D. 常驻后台 · Windows（nssm）

装好 [nssm](https://nssm.cc/) 后，以管理员 PowerShell 运行模板脚本：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\install-nssm.ps1
# 卸载：nssm remove cli-proxy-logger-java confirm
```

临时跑：Linux `nohup java -jar cli-proxy-logger-1.0.0.jar > proxy.out 2>&1 &`。

### E. Docker / docker-compose

仓库内置**多阶段** `Dockerfile`（`maven:3.9-eclipse-temurin-8` 构建 fat jar → `eclipse-temurin:8-jre` 运行）+ `docker-compose.yml`。Spring Boot 默认监听所有网卡，发布端口即可达，无需额外 `BIND_ADDR`。**镜像构建时会自动下载官方 xray + sing-box 的 Linux 二进制打进 `/app/vendor`（约 80MB），所以容器开箱即可走高级协议，无需自己准备内核**。

```bash
cd cli-proxy-logger-java
docker compose up -d --build
# 代理 + UI: http://<host>:8788   日志落 ./logs
docker compose logs -f
docker compose down
```

不想把内核打进镜像（镜像更小）：`docker compose build --build-arg WITH_KERNEL=0`，或在 compose 的 `build.args` 里设 `WITH_KERNEL: "0"`。

不用 compose：

```bash
docker build -t cli-proxy-logger-java .
docker run -d --name cli-proxy-logger-java -p 8788:8788 \
  -v "$PWD/logs:/app/logs" cli-proxy-logger-java
```

**容器内走内核**：默认已把官方内核打进镜像（见上），你只需把 `UPSTREAM_PROXY` 指向高级协议分享链接（compose 里有注释示例）。若要用**你自己的** Linux 版二进制覆盖内置的，把它放到宿主机 `./vendor/`，在 `docker-compose.yml` 里取消注释 `./vendor/xray:/app/vendor/xray:ro` 等挂载行即可。

### F. exe / 单文件分发

Java 的标准交付物就是**可执行 fat jar**（`java -jar ...`，已实测在 JDK 8 上启动并服务 `:8788`），目标机只需一个 JRE。

若一定要**免装 JRE 的原生 .exe/安装包**，用 JDK 自带的 `jpackage`（**需 JDK 14+，本机 JDK 8 无此工具，未实测**）：

```bash
# 在装了 JDK 17+ 的机器上：
jpackage --type app-image --name cli-proxy-logger \
  --input target --main-jar cli-proxy-logger-1.0.0.jar \
  --main-class org.springframework.boot.loader.JarLauncher
# Windows 下 --type exe / msi 可出安装包（需 WiX）
```

多数场景直接用 **Docker 镜像**或 **fat jar + JRE** 即可，无需 jpackage。

---

## 3. 内核出站（xray / sing-box）的获取与启用

让代理支持 VMess/VLESS/Trojan/Shadowsocks/Hysteria2/TUIC 等高级协议：内核作为本地 SOCKS5 前置层由本服务自动拉起，**你只需服务起一个**，外加内核二进制能被找到。**注意：内核一般无需自己编译——直接下载官方预编译二进制即可（见下 A/B）**。

二进制查找顺序：显式路径（`proxy.xray-bin`/`proxy.sing-box-bin` 或 `XRAY_BIN`/`SING_BOX_BIN`）→ 同级 `vendor/`（`xray`/`sing-box`，Windows 带 `.exe`）→ 系统 PATH。下面三种获取方式任选其一（**绝大多数情况选 A，不用装 Go、不用编译**）。

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

**用法 A：直接给分享链接**（最省事）：

```bash
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.xray-bin=/path/to/xray \
  --proxy.upstream-proxy='vless://<uuid>@example.com:443?encryption=none&security=tls&sni=...&type=ws&host=...&path=%2F#node' \
  --proxy.proxy-kernel=auto
# 或环境变量：XRAY_BIN=... UPSTREAM_PROXY='ss://...#node' PROXY_KERNEL=auto java -jar ...
```

`auto` 默认 xray；`hysteria2`/`tuic` xray 不支持会自动改用 sing-box。

**用法 B：给一份完整原生配置**（高级）：

```bash
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.proxy-kernel=sing-box --proxy.proxy-kernel-config=./my-singbox.json
```

内核子进程随主进程退出（shutdown hook）自动关闭，临时配置文件自动清理。完整设计/实施过程见 [../KERNEL_INTEGRATION.md](../KERNEL_INTEGRATION.md)。

---

## 4. 网络 / 安全

- 代理 + UI 共用一个端口（默认 `:8788`）。Spring Boot/Tomcat 默认监听所有网卡（Docker 下正需如此）；只想本机可访问加 `--server.address=127.0.0.1`。
- CLI 的 base URL 通常指向 `127.0.0.1`，**建议与 CLI 同机部署**。把端口暴露到内网其他机器时请自行加访问控制（鉴权头虽落盘脱敏，但内存/转发链路上是明文）。
