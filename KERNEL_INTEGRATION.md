# xray-core / sing-box 内核出站：完整实施过程

本文记录给 `cli-proxy-logger`（Node / Python / Java 三套实现）加入 **xray-core / sing-box 内核出站** 的完整设计、取舍、实现与真机验证过程。三套实现逻辑 1:1 对齐。

- Node：`cli-proxy-logger/src/kernel/`
- Python：`cli-proxy-logger-py/cli_proxy_logger/kernel/`
- Java：`cli-proxy-logger-java/src/main/java/com/practice/cliproxy/kernel/`

---

## 1. 背景与目标

`cli-proxy-logger` 是一个反向代理 + 抓包记录器，把发往 LLM API 的请求/响应记录下来。它原本的**出站代理**（`UPSTREAM_PROXY`）只支持 `http`/`https`/`socks5`——自己实现 CONNECT / SOCKS5 握手，零三方依赖。

需求：让出站代理能走 **VMess / VLESS / Trojan / Shadowsocks / Hysteria2 / TUIC** 等高级协议，且尽量不破坏现有零依赖、零侵入的设计。

---

## 2. 核心设计：内核作为「本地 SOCKS5 前置层」

高级协议的加密/传输实现都在 Go 写的内核里（xray-core、sing-box）。本项目是 Node / Python（标准库）/ Java（JDK+Jackson）实现，**无法把 Go 的协议栈直接嵌入**。两个进程之间需要一个通信接口，而内核对外暴露能力的最通用方式就是一个 **inbound**。

于是设计为：

```
应用进程（cli-proxy-logger）
   │  已有的出站 SOCKS5 客户端（给 socks5:// 用的那套，未改一行）
   ▼
本地回环 SOCKS5  127.0.0.1:<随机端口>   ← 内核的 inbound（明文、仅 loopback）
   │
xray / sing-box（子进程，由应用 spawn 并托管）
   │  你的高级协议封装：VMess/VLESS/Trojan/SS/Hy2/TUIC (+TLS/Reality +ws/grpc/…)
   ▼
落地节点 ───▶ 真实上游（api.anthropic.com / api.ipify.org / …）
```

**为什么用本地 SOCKS5？**

1. **进程间桥（IPC）**：内核是独立 Go 进程，本地回环 SOCKS5 是它和应用之间最通用的接口；只跑在 `127.0.0.1`、不出网，明文即可。
2. **零侵入**：应用**本来就实现并测试过 SOCKS5 客户端**（给 `socks5://` 代理用的）。让内核 inbound 也是 SOCKS5，就能复用这套隧道代码——高级协议路径几乎零改动，老 http/socks 路径一行没动。

**两层 TLS（容易混淆点）**：走 https 上游时其实有两层 TLS：
- **外层**：内核 ↔ 落地节点之间的传输 TLS（链接里的 `security=tls`），保护「你在用代理」这件事；
- **内层**：应用 ↔ 最终目标（如 `api.anthropic.com:443`）的端到端 TLS，被原样塞进隧道穿过去，节点也解不开。

这也是为什么出口 IP 变成节点落地 IP，但目标站证书校验仍针对真实目标域名。

---

## 3. 模块拆分（三套一致）

每套实现都拆成四块，职责单一、纯函数优先：

| 职责 | Node | Python | Java |
|---|---|---|---|
| 分享链接 → 规范化 spec | `links.js` | `kernel/links.py` | `ShareLink.java` + `Spec.java` |
| spec → xray 原生配置 | `xray.js` | `kernel/xray.py` | `XrayConfig.java` |
| spec → sing-box 原生配置 | `singbox.js` | `kernel/singbox.py` | `SingboxConfig.java` |
| 选内核/找二进制/spawn/就绪/清理 | `index.js` | `kernel/manager.py` | `Kernel.java` |

接线点（把内核接进出站）：
- Node：`src/outbound.js` 的 `createOutbound`
- Python：`cli_proxy_logger/outbound.py` 的 `create_outbound` + `config.py` 传入 `kernel_opts`
- Java：`outbound/OutboundProxy.java` 的 `create(raw, kernelOpts)` + `config/ProxyProperties.java` 解析配置 + `proxy/ProxyController.java` 装配并注册退出清理

### 3.1 链接解析（links）
- 支持 `vmess://`（v2rayN 的 base64-JSON）、`vless://`、`trojan://`、`ss://`（SIP002 与整段 base64 两种）、`hysteria2://`(`hy2://`)、`tuic://`。
- 归一化出统一 spec：协议、server、port、凭据（uuid/password/method）、TLS（含 Reality 的 `pbk`/`sid`/`spx`）、传输层（`ws`/`grpc`/`http`/`httpupgrade` 的 `path`/`host`/`serviceName`）。
- `isKernelScheme(raw)` 用于区分高级协议链接与普通 http/socks 代理 URL。

### 3.2 配置渲染（xray / singbox）
- 同一份 spec，分别渲染成两套内核的原生 JSON——两者 schema 不同（如 xray 的 `vnext`/`streamSettings` vs sing-box 的扁平 `outbounds[]` + `tls`/`transport`）。
- 产出最小可用配置：一个 loopback SOCKS5 inbound + 一个对应协议的 outbound + 一个 `direct`/`freedom` 兜底。

### 3.3 内核管理器（manager）
- **选内核**：`auto` 默认 xray；`hysteria2`/`tuic` 强制 sing-box（xray 无原生这两种出站）；也可显式指定。
- **找二进制**：`XRAY_BIN`/`SING_BOX_BIN` 显式路径 → 同级 `vendor/` → 系统 PATH。
- **spawn**：`<bin> run -c <临时配置>`，把子进程 stdout 泵到应用日志。
- **就绪探测**：轮询连接本地 SOCKS5 端口，直到可连接（或超时报错；若子进程提前退出则带上退出码报错）。
- **清理**：主进程退出时杀子进程、删临时配置（Node 的 SIGINT/SIGTERM 钩子；Python 的 `atexit`；Java 的 JVM shutdown hook）。

---

## 4. 配置项（全部 opt-in，向后兼容）

| 含义 | Node / Python 环境变量 | Java 配置项（`proxy.*`，含环境变量回退） |
|---|---|---|
| 出站代理 / 高级链接 | `UPSTREAM_PROXY` | `proxy.upstream-proxy`（回退 `UPSTREAM_PROXY`） |
| 内核选择 auto/xray/sing-box | `PROXY_KERNEL` | `proxy.proxy-kernel`（回退 `PROXY_KERNEL`） |
| 原生配置文件路径 | `PROXY_KERNEL_CONFIG` | `proxy.proxy-kernel-config`（回退 `PROXY_KERNEL_CONFIG`） |
| xray / sing-box 二进制路径 | `XRAY_BIN` / `SING_BOX_BIN` | `proxy.xray-bin` / `proxy.sing-box-bin`（回退同名大写） |
| 固定本地 socks 端口 | `PROXY_KERNEL_SOCKS_PORT` | `proxy.proxy-kernel-socks-port`（回退同名大写） |

不设这些时行为与以前完全一致：普通 http/socks 走老路径、不拉内核。

---

## 5. 构建内核二进制

```bash
# xray-core -> ./xray（较新的 Go；本项目用 Go 1.26 验证）
git clone https://github.com/XTLS/Xray-core && (cd Xray-core && go build -o xray ./main)

# sing-box -> ./sing-box（必须 Go 1.24.x，见下方坑）
git clone https://github.com/SagerNet/sing-box && \
  (cd sing-box && go build -tags "with_utls,with_quic" ./cmd/sing-box)
```

**Go 版本坑（已踩并记录）**：sing-box 的 `badtls` 用 `//go:linkname` 引用 `crypto/tls` 的内部方法，**Go 1.26 改了相关签名导致链接失败**。必须用 **Go 1.24.x** 构建 sing-box；xray 则可用 Go 1.26。两个版本可并存。`with_quic` 是 Hysteria2/TUIC 必需，`with_utls` 提供 `fp=chrome` 等 uTLS 指纹。

---

## 6. 测试与真机验证

### 6.1 单元 / 端到端测试
- Node：`test/kernel.js`（含用「假内核」SOCKS5 shim 的端到端隧道测试）。
- Python：`tests/test_kernel.py` + `tests/fixtures/fake_kernel.py`（同样覆盖 解析→渲染→选内核→端到端）。
- Java：`src/test/java/.../KernelTest.java`（JUnit，覆盖 scheme 检测、链接解析、xray/sing-box 渲染、选内核规则）。
- 全部并入各自既有测试套，无回归。

### 6.2 真机验证（出口 IP 法）
拉起真实内核走真实节点访问 `https://api.ipify.org`，比对出口 IP：

| 路径 | 出口 IP |
|---|---|
| 直连（VM 本机） | `54.69.238.189` |
| VLESS+TLS+WS 节点（`216.40.87.247:18633`）· xray / sing-box | `107.174.183.62` |
| Shadowsocks/aes-128-gcm 节点（`89.45.45.120:53791`）· xray / sing-box | `89.45.45.120` |

三套实现（Node / Python / Java）× 两内核（xray / sing-box）均验证：出口 IP 确实变成节点落地 IP，证明全链路打通、解析/渲染正确。Hysteria2/TUIC 因无真实节点未做真机验证，但配置渲染有单测覆盖。

---

## 7. 请求链路一句话

```
cli-proxy-logger ──SOCKS5(本机回环,明文)──▶ 内核inbound
内核 ──[你的协议封装: VLESS/SS… (+TLS +ws/grpc)]──▶ 落地节点 ──▶ 目标网站
```

普通 http/socks 路径不变（不拉内核），高级协议路径自动拉起内核——这就是「内核作为本地 SOCKS5 前置层」的零侵入实现。
