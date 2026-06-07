# cli-proxy-logger

本地反向代理，用来**拦截并记录 Claude Code / Codex 这类 CLI 发出的全部 LLM API 请求**——清楚地看到：

- 发了哪些请求（URL / method / headers / body）
- 响应是什么（status / headers / body，支持流式 SSE）
- 是否调用了工具、调用了哪个工具、参数是什么、工具返回了什么

不劫持 TLS、**不需要安装根证书**：利用 Claude Code / Codex 支持「自定义 base URL」的能力，把它们指向本地代理即可。代理把请求**原样转发**到真实上游，同时旁路解析、落盘、并提供网页查看。

```
Claude Code / Codex ──HTTP──▶ 本地代理 :8788 ──HTTPS──▶ api.anthropic.com / api.openai.com
                                   │
                                   ├─ 解析请求/响应 + 工具调用
                                   ├─ logs/YYYY-MM-DD.jsonl
                                   └─ Web UI :8789
```

## 运行

需要 Node.js >= 18（无第三方依赖）。

```bash
cd cli-proxy-logger
npm start
# [proxy] listening on http://127.0.0.1:8788
# [ui]    open http://127.0.0.1:8789
```

打开 http://127.0.0.1:8789 浏览抓到的请求。

## 使用配置模板（Codex / Claude Code / opencode）

启动代理后（见上方「运行」），把各 CLI 的 base URL 指向本地代理即可。**最关键的区别：Codex / opencode 的 base URL 带 `/v1`；Claude Code 的不带 `/v1`**（它自己会拼 `/v1/messages`，带了会变成 `/v1/v1/messages` 而 404）。

代理按路径区分上游：`/v1/responses` 与 `/v1/chat/completions` 走 `OPENAI_UPSTREAM`，`/v1/messages` 走 `ANTHROPIC_UPSTREAM`。启动代理时按需设置这两个上游（见本节末「启动代理时设置上游」）。

### Codex（`~/.codex/config.toml`）

**场景 A：自定义 provider（推荐，可与官方 OpenAI 配置共存）**
```toml
model = "gpt-5"
model_provider = "proxy"

[model_providers.proxy]
name = "local proxy"            # 必填，否则报 "provider name must not be empty"
base_url = "http://127.0.0.1:8788/v1"
wire_api = "responses"          # Codex 默认走 Responses；兼容 API 可设 "chat"
env_key = "OPENAI_API_KEY"      # key 走环境变量时需要；若用 auth.json 则删掉这行
```

**场景 B：直接改内置 openai provider 的 base URL（最省事）**
```toml
openai_base_url = "http://127.0.0.1:8788/v1"
```

**key 的两种提供方式（二选一）：**
- 环境变量：provider 块保留 `env_key = "OPENAI_API_KEY"`，启动前设好 `set OPENAI_API_KEY=<key>`（PowerShell：`$env:OPENAI_API_KEY="<key>"`）。
- `~/.codex/auth.json`：写 `{ "OPENAI_API_KEY": "<key>" }`，并**删掉** provider 块里的 `env_key`（否则 Codex 强制找环境变量，报 `Missing environment variable: OPENAI_API_KEY`）。

**wire 区别：** `wire_api = "responses"` → `POST /v1/responses`（Codex 默认，流式）；`wire_api = "chat"` → `POST /v1/chat/completions`。

### Claude Code（环境变量）

```bash
# base URL 不带 /v1！Claude Code 自己拼 /v1/messages
export ANTHROPIC_BASE_URL=http://127.0.0.1:8788
export ANTHROPIC_API_KEY=<key>          # 以 x-api-key 头发出，代理原样转发、落盘脱敏
# 指向非官方 host 时 MCP tool search 默认关闭，需要可开启：
# export ENABLE_TOOL_SEARCH=true

# 模型分三档：opus / sonnet / haiku。接第三方上游时建议显式指定，
# 否则别名会解析成 Anthropic 官方模型名，上游不一定认。
export ANTHROPIC_MODEL=<主模型>                       # 覆盖当前会话主模型
export ANTHROPIC_DEFAULT_OPUS_MODEL=<opus 档模型>     # /model 切到 opus 时解析到的模型
export ANTHROPIC_DEFAULT_SONNET_MODEL=<sonnet 档模型> # /model 切到 sonnet 时解析到的模型
export ANTHROPIC_DEFAULT_HAIKU_MODEL=<haiku 档模型>   # haiku 档 + 后台任务（标题/补全等）
claude
```
Windows 用 `$env:NAME="..."`（PowerShell）或 `set NAME=...`（cmd）。

模型说明：
- `ANTHROPIC_DEFAULT_{OPUS,SONNET,HAIKU}_MODEL` 分别控制三档别名解析到的真实模型；`ANTHROPIC_MODEL` 覆盖「当前主模型」（优先级高于 `model` 设置）。
- 旧版的 `ANTHROPIC_SMALL_FAST_MODEL` 已被 `ANTHROPIC_DEFAULT_HAIKU_MODEL` 取代（仍向后兼容，对应 haiku/后台档）。
- **后台任务**（生成会话标题等）默认走 haiku 档，所以即便你只用 sonnet，也建议把 `ANTHROPIC_DEFAULT_HAIKU_MODEL` 指到一个上游可用的小模型，否则后台请求可能报错。
- 实测（freemodel）：`ANTHROPIC_MODEL=claude-sonnet-4-6` + `ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-4-5-20251001` 可用。

Claude Code 走 Anthropic Messages 格式：`POST /v1/messages`（流式 SSE）。

### opencode（`opencode.json` 或 `~/.config/opencode/opencode.json`）

opencode 支持「每个 provider 自定义 `baseURL`」，底层就是本工具已覆盖的三种 wire 格式，base URL **带 `/v1`**。三种场景按 `npm` 包区分：

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    // 场景①：OpenAI 兼容 -> /v1/chat/completions -> chat wire
    "myproxy-chat": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Local proxy (chat)",
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<key>" },
      "models": { "gpt-5": { "name": "gpt-5 via proxy (chat)" } }
    },
    // 场景②：OpenAI Responses -> /v1/responses -> responses wire
    "myproxy-resp": {
      "npm": "@ai-sdk/openai",
      "name": "Local proxy (responses)",
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<key>" },
      "models": { "gpt-5": { "name": "gpt-5 via proxy (responses)" } }
    },
    // 场景③：Anthropic 模型 -> /v1/messages -> anthropic wire（覆盖内置 anthropic 的 baseURL）
    "anthropic": {
      "options": { "baseURL": "http://127.0.0.1:8788/v1", "apiKey": "<key>" }
    }
  }
}
```
跑：`opencode run -m myproxy-chat/gpt-5 "..."`（或 `myproxy-resp/...`、`anthropic/...`）。本工具实测：chat / responses 路径都抓到工具调用参数与请求/响应体；anthropic 路径也被正确路由落盘（若上游按 CLI 指纹放行——如只认 Claude Code——可能拒绝 opencode，属上游限制，与代理无关）。

### 启动代理时设置上游

| 用到的 CLI | 上游环境变量（默认值） |
|-----------|------------------------|
| Codex、opencode（chat / responses） | `OPENAI_UPSTREAM`（`https://api.openai.com`） |
| Claude Code、opencode（anthropic） | `ANTHROPIC_UPSTREAM`（`https://api.anthropic.com`） |

例（指向 freemodel）：
```bash
OPENAI_UPSTREAM=https://api.freemodel.dev ANTHROPIC_UPSTREAM=https://cc.freemodel.dev npm start
```

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|------|------|------|
| `PROXY_PORT` | `8788` | 代理监听端口 |
| `UI_PORT` | `8789` | Web UI 端口 |
| `LOG_DIR` | `./logs` | JSONL 日志目录 |
| `REDACT_AUTH` | 开启 | 落盘时对 `x-api-key`/`authorization` 脱敏；设 `0` 关闭 |
| `ANTHROPIC_UPSTREAM` | `https://api.anthropic.com` | Anthropic 上游 |
| `OPENAI_UPSTREAM` | `https://api.openai.com` | OpenAI 上游 |
| `MAX_BODY_BYTES` | `2000000` | 单条 body 落盘上限，超出截断 |
| `ANTHROPIC_COMPAT` | 关闭（透明直通） | 设为 `chat` 时开启「协议翻译」：把进来的 Anthropic `/v1/messages` 翻译成 OpenAI `/v1/chat/completions` 发往 `OPENAI_UPSTREAM`（见下一节） |
| `MODEL_MAP` | 空 | 模型映射表，JSON（`{"claude-sonnet-4-6":"gpt-4o"}`）或逗号分隔（`claude-sonnet-4-6=gpt-4o,claude-haiku-4-5=gpt-4o-mini`）；没命中就原样透传模型名 |
| `MODEL_MAP_FILE` | 空 | 模型映射 JSON 文件路径（优先于 `MODEL_MAP`） |
| `PROVIDERS` | 空 | 供应商池，JSON 数组（见「弹性」节）。配了池就启用故障转移+熔断 |
| `PROVIDERS_FILE` | 空 | 供应商池 JSON 文件路径（优先于 `PROVIDERS`） |
| `BREAKER` | 关闭 | 设 `1` 强制开启熔断；**配了 `PROVIDERS` 池时自动开启** |
| `BREAKER_FAILURES` | `5` | 单个供应商连续失败多少次后熔断（OPEN） |
| `BREAKER_COOLDOWN_MS` | `30000` | 熔断后冷却多久（毫秒）才放探测请求（HALF_OPEN） |
| `BREAKER_HALFOPEN_MAX` | `1` | HALF_OPEN 时允许的并发探测数 |
| `FAILOVER_STATUSES` | `429,500,502,503,504` | 触发故障转移的上游状态码（逗号分隔） |
| `RECTIFY` / `RECTIFIER` | 关闭 | 设 `1` 开启 Anthropic thinking 整流（仅作用于 `/v1/messages`） |
| `RECTIFY_SIGNATURE` | 开启 | 设 `0` 关闭「签名整流」子规则 |
| `RECTIFY_BUDGET` | 开启 | 设 `0` 关闭「budget 整流」子规则 |
| `TOOL_NAME_CASE` | 关闭 | 设 `1` 开启「工具名规范化」（小写→PascalCase，仅作用于 `/v1/messages`，见下节） |
| `TOOL_NAME_REQUEST` | 开启 | 设 `0` 关闭请求侧工具名改写（`tools[].name` 与历史 `tool_use.name`） |
| `TOOL_NAME_RESPONSE` | 开启 | 设 `0` 关闭响应侧工具名改写（含 SSE `content_block_start`） |
| `TOOL_NAME_REPAIR_INPUT` | 开启 | 设 `0` 关闭 `tool_use.input` 修复（被序列化成字符串的数组/对象还原） |
| `TOOL_NAME_MAP` | 空 | 工具名映射 JSON 对象（`{"todowrite":"TodoWrite"}`），合并/覆盖内置表 |
| `TOOL_NAME_MAP_FILE` | 空 | 工具名映射 JSON 文件路径（优先于 `TOOL_NAME_MAP`） |
| `FILTERS` | 空 | 请求过滤器/规则 JSON 数组（见下节）；转发上游前改写请求头/请求体 |
| `FILTERS_FILE` | 空 | 过滤器 JSON 文件路径（优先于 `FILTERS`） |
| `UPSTREAM_PROXY` | 空 | 出站代理 URL。普通代理：`http://`/`https://`/`socks5://`（可带 `user:pass@`），未设时回退 `HTTPS_PROXY`/`HTTP_PROXY`。**高级协议**：`vmess://`/`vless://`/`trojan://`/`ss://`/`hysteria2://`(`hy2://`)/`tuic://` 分享链接，自动经本地 xray/sing-box 内核出站（见「内核出站」节） |
| `PROXY_KERNEL` | `auto` | 内核选择：`auto`/`xray`/`sing-box`。`auto` 优先 xray；`hysteria2`/`tuic` 只能 sing-box，会自动选 |
| `PROXY_KERNEL_CONFIG` | 空 | 一份完整的原生内核配置 JSON 文件路径（高级用法）；会自动确保存在一个回环 socks inbound 并路由过去。设了它就启用内核（不需要 `UPSTREAM_PROXY`） |
| `XRAY_BIN` / `SING_BOX_BIN` | 空 | 内核二进制路径；不设则按 PATH 与同级 `vendor/` 目录查找 |
| `PROXY_KERNEL_SOCKS_PORT` | 随机 | 内核本地 socks inbound 端口（默认取空闲端口） |

## 弹性：多供应商故障转移 + 熔断 + thinking 整流（全部 opt-in）

> 这三块**默认全关**，不配就和以前完全一样（透明直通、字节级不变）。只有显式配置时才进入「弹性路径」。三套实现（Node/Python/Java）逻辑一致。

### 1) 多供应商池 + 故障转移（failover）

用 `PROVIDERS`（或 `PROVIDERS_FILE`）配一个有序的供应商池。每个供应商：

```json
[
  { "id": "anthropic-main", "group": "anthropic", "baseUrl": "https://api.vendor-a.com", "apiKey": "sk-a" },
  { "id": "anthropic-backup", "group": "anthropic", "baseUrl": "https://api.vendor-b.com", "apiKey": "sk-b" },
  { "id": "openai-main", "group": "openai", "baseUrl": "https://api.vendor-c.com" }
]
```

- **`group`**：`anthropic`（匹配 `/v1/messages`）或 `openai`（匹配 `/v1/responses` 与 `/v1/chat/completions`）。也可写 `wire`（`messages`→anthropic，`responses`/`chat`→openai）。
- **`id`**：缺省自动生成（`anthropic-0`…），是熔断器的 key。
- **`apiKey`**：可选。给了就用它替换客户端凭证（anthropic 发 `x-api-key`，openai 发 `Authorization: Bearer`）；不给则原样转发客户端凭证。
- **`baseUrl`**：上游根地址（末尾 `/` 自动去掉）。请求按池顺序尝试。
- 池为空时回退到单上游（`ANTHROPIC_UPSTREAM`/`OPENAI_UPSTREAM`），即旧行为。

**故障转移语义（关键）**：只在「**还没把响应流给客户端之前**」切换——即上游**连接失败**或返回**故障转移状态码**（默认 429/500/502/503/504）时，记一次失败并试下一个供应商。**一旦上游回了 2xx 开始流式回写，就不再切换**（避免重放、保证保真）。其它非 2xx（如 400/401/403）视为终止错误，原样回写客户端、不再故障转移。池全部失败则把最后一次上游错误原样回放给客户端。

### 2) 熔断（circuit breaker）

每个供应商一个独立熔断器，状态机 `CLOSED → OPEN → HALF_OPEN`：

- 连续失败累计到 `BREAKER_FAILURES`（默认 5）→ **OPEN**，在 `BREAKER_COOLDOWN_MS`（默认 30s）内直接跳过该供应商；
- 冷却后进入 **HALF_OPEN**，放最多 `BREAKER_HALFOPEN_MAX`（默认 1）个探测请求，成功则回到 CLOSED，失败则重新 OPEN；
- **每次请求结束都会释放 HALF_OPEN 探测名额**（避免探测名额泄漏导致熔断器卡死）；
- **区分错误类型**：上游真故障（连接失败 / 故障转移状态码）才计入失败；客户端类错误（如 400/401，以及被整流的请求）记为「中性」，不计入熔断。
- 配了 `PROVIDERS` 池就自动开启熔断；没有池时可用 `BREAKER=1` 单独开启（对单上游也生效）。

### 3) Anthropic thinking 整流器（rectifier，仅 `/v1/messages`）

跨供应商切换或换模型时，历史 `thinking` 块签名/budget 约束可能被新上游拒。开启 `RECTIFY=1` 后，代理检测到特定错误会**改写请求体并对同一供应商重试一次**（重试在故障转移之前）：

- **签名整流**（`RECTIFY_SIGNATURE`，默认开）：上游报 thinking 签名校验错 → 删掉历史消息里的 `thinking`/`redacted_thinking` 块及各块的 `signature` 字段 → 重试一次。
- **budget 整流**（`RECTIFY_BUDGET`，默认开）：上游报 `budget_tokens` 约束错（如要求 ≥1024）→ 把 `thinking.type` 设 `enabled`、`budget_tokens=32000`，必要时把 `max_tokens` 提到 64000 → 重试一次。
- 只整流**一次**；整流后仍失败则进入故障转移。整流触发的失败计为「中性」，不计入熔断。

> 落盘：走弹性路径的请求在 JSONL 里带一个 `resilience` 字段（`{providerId, attempt, rectified?, failedOver?}`），便于排查实际命中了哪个供应商、是否发生了整流/故障转移。

**最小示例（两个 anthropic 供应商 + 熔断 + 整流）**
```bash
PROVIDERS='[{"id":"a","group":"anthropic","baseUrl":"https://api.vendor-a.com","apiKey":"sk-a"},{"id":"b","group":"anthropic","baseUrl":"https://api.vendor-b.com","apiKey":"sk-b"}]' \
RECTIFY=1 \
npm start
```

## 扩展三件套：工具名规范化 + 请求过滤器 + 出站代理（全部 opt-in）

> 三块互相独立、**默认全关**。不配就和以前完全一样（透明直通、字节级不变）。三套实现（Node/Python/Java）逻辑一致。

### 1) 工具名规范化（`TOOL_NAME_CASE=1`）

有些第三方上游对工具名大小写敏感，要求 `TodoWrite` 这样的 PascalCase，而 opencode/部分客户端会发小写 `todowrite`。开启后代理对 **Anthropic `/v1/messages`** 流量做：

- **请求侧**：把 `tools[].name` 和历史 `messages[].content[].tool_use.name` 从小写改成 PascalCase（内置表 `todowrite→TodoWrite`、`webfetch→WebFetch`、`google_search→Google_Search`，其余首字母大写）。已是 PascalCase 的原样保留。
- **响应侧**：把响应里 `tool_use.name`（含 SSE `content_block_start` 事件）改回客户端期望的形态；并**修复 `tool_use.input`**——上游有时把数组/对象序列化成 JSON 字符串（`"[\"a\",\"b\"]"`），这里还原成真正的数组/对象。
- 用 `TOOL_NAME_MAP`（或 `TOOL_NAME_MAP_FILE`）自定义/覆盖映射表；`TOOL_NAME_REQUEST` / `TOOL_NAME_RESPONSE` / `TOOL_NAME_REPAIR_INPUT` 可分别关掉某一侧（默认都开）。

```bash
TOOL_NAME_CASE=1 \
TOOL_NAME_MAP='{"todowrite":"TodoWrite","webfetch":"WebFetch"}' \
npm start
```

**只关响应侧改写**（只规范化发往上游的请求，原样回传上游响应）：

```bash
TOOL_NAME_CASE=1 TOOL_NAME_RESPONSE=0 npm start
```

**只做响应修复、不动请求**（例如上游名字已对，只想把序列化成字符串的 `tool_use.input` 还原成数组/对象）：

```bash
TOOL_NAME_CASE=1 TOOL_NAME_REQUEST=0 TOOL_NAME_RESPONSE=0 TOOL_NAME_REPAIR_INPUT=1 npm start
```

**用文件加载映射表**（`TOOL_NAME_MAP_FILE`，优先级低于 `TOOL_NAME_MAP`）：

```bash
cat > tool-name-map.json <<'JSON'
{ "todowrite": "TodoWrite", "webfetch": "WebFetch", "google_search": "Google_Search" }
JSON
TOOL_NAME_CASE=1 TOOL_NAME_MAP_FILE=./tool-name-map.json npm start
```

### 2) 请求过滤器 / 规则引擎（`FILTERS=...`）

一组有序规则，在**转发上游前**改写请求头与请求体（JSON）。每条规则：

```json
[
  { "name": "beta-header", "action": "set_header", "target": "anthropic-beta", "value": "context-1m-2025-08-07", "priority": 1 },
  { "name": "force-adaptive", "action": "json_set", "target": "thinking.type", "value": "adaptive", "priority": 2 },
  { "name": "min-budget", "action": "json_set", "target": "thinking.budget_tokens", "value": 1024, "priority": 3 },
  { "name": "drop-trace", "action": "delete_header", "target": "x-internal-trace", "priority": 4 },
  { "name": "vendor-only", "action": "set_header", "target": "x-vendor", "value": "1", "scope": "provider:anthropic-main" }
]
```

- **`action`**：`set_header` / `delete_header`（请求头）、`json_set` / `json_delete`（请求体，`target` 为点路径如 `thinking.type`、`metadata.user_id`）。无效 action 的规则会被丢弃。
- **`value`**：`json_set` 的值支持字符串/数字/布尔/对象（纯数字字符串如 `"1024"` 会强转成数字）。
- **`priority`**：升序应用（小的先），缺省 `0`。
- **`enabled`**：设 `false` 跳过该规则。
- **`scope`**：`all`（默认，所有请求）或 `provider:<id>`（只对该供应商 id 生效，配合「弹性」供应商池）。
- 命中的规则名记录到 JSONL 的 `mutation` 字段，便于排查。

```bash
FILTERS='[{"name":"beta","action":"set_header","target":"anthropic-beta","value":"context-1m-2025-08-07"}]' \
npm start
```

**用文件加载过滤器**（`FILTERS_FILE`，优先级低于 `FILTERS`；适合规则较多时维护成独立文件）：

```bash
cat > filters.json <<'JSON'
[
  { "name": "beta-header",    "action": "set_header",  "target": "anthropic-beta",          "value": "context-1m-2025-08-07", "priority": 1 },
  { "name": "force-adaptive", "action": "json_set",    "target": "thinking.type",           "value": "adaptive",              "priority": 2 },
  { "name": "min-budget",     "action": "json_set",    "target": "thinking.budget_tokens",  "value": 1024,                     "priority": 3 },
  { "name": "drop-trace",     "action": "delete_header","target": "x-internal-trace",                                          "priority": 4 }
]
JSON
FILTERS_FILE=./filters.json npm start
```

### 3) 出站代理（`UPSTREAM_PROXY=...`）

让代理**去上游**的连接走一个外部代理（内网出口常见需求）。支持 `http://`、`https://`、`socks5://`（`socks://`、`socks5h://` 视为 socks5），可带 `user:pass@` 鉴权。未设 `UPSTREAM_PROXY` 时回退读 `HTTPS_PROXY`/`HTTP_PROXY`（含小写）。

```bash
UPSTREAM_PROXY=socks5://127.0.0.1:1080 npm start
# 或带鉴权的 HTTP 代理：
UPSTREAM_PROXY=http://user:pass@proxy.example.com:3128 npm start
```

#### 内核出站：用 xray-core / sing-box 支持更多协议

`http`/`socks5` 之外，出站代理还能走 **VMess / VLESS / Trojan / Shadowsocks / Hysteria2 / TUIC** 等协议——做法是把官方内核（[xray-core](https://github.com/XTLS/Xray-core) 或 [sing-box](https://github.com/SagerNet/sing-box)）作为本地子进程拉起：内核 inbound 是一个**仅回环的 SOCKS5**，outbound 是你的高级协议；代理现有的 socks 隧道直接指向这个本地端口。Node 侧零依赖、隧道逻辑零改动。

```
CLI ──HTTP──▶ cli-proxy-logger :8788 ──socks5──▶ 127.0.0.1:<随机端口>
                                                  (xray / sing-box)
                                                       │ VMess/VLESS/Trojan/SS/Hy2/TUIC
                                                       ▼  真实上游
```

**前置条件**：本机要有内核二进制。从仓库自行构建（需 Go）：

```bash
# xray-core -> 产出 ./xray（用较新的 Go，本项目用 Go 1.26 验证过）
git clone https://github.com/XTLS/Xray-core && (cd Xray-core && go build -o xray ./main)

# sing-box -> 产出 ./sing-box
# 注意：sing-box 的 badtls 用 //go:linkname 引用 crypto/tls 内部方法，
#      Go 1.26 改了相关签名导致链接失败；请用 Go 1.24.x 构建，并带上需要的特性 tag。
git clone https://github.com/SagerNet/sing-box && \
  (cd sing-box && go build -tags "with_utls,with_quic" ./cmd/sing-box)
```

> Windows 下产出 `xray.exe` / `sing-box.exe`。`with_quic` 是 Hysteria2/TUIC 必需的；`with_utls` 提供 uTLS 指纹（`fp=chrome` 等）。

把二进制放进 PATH、或放到本模块同级的 `vendor/` 目录、或用 `XRAY_BIN` / `SING_BOX_BIN` 指定路径（推荐绝对路径）：

```bash
export XRAY_BIN=/abs/path/to/xray            # Windows: set XRAY_BIN=C:\path\xray.exe
export SING_BOX_BIN=/abs/path/to/sing-box
```

二进制查找顺序：`XRAY_BIN`/`SING_BOX_BIN` 显式路径 → 同级 `vendor/`（`xray`/`sing-box`，Windows 加 `.exe`）→ 系统 PATH。

**用法 A：直接给分享链接**（最省事）。`UPSTREAM_PROXY` 识别到高级协议链接就自动走内核：

```bash
# VLESS + Reality（自动选 xray）
UPSTREAM_PROXY='vless://<uuid>@example.com:443?encryption=none&security=reality&pbk=<pubkey>&sid=<shortid>&sni=www.apple.com&fp=chrome&type=tcp#node' npm start
# VMess（v2rayN 的 base64-JSON 链接）
UPSTREAM_PROXY='vmess://eyJ2IjoiMiIsImFkZCI6...' npm start
# Hysteria2 / TUIC（只能 sing-box，auto 会自动选）
UPSTREAM_PROXY='hysteria2://<pass>@example.com:8443?sni=example.com#node' npm start
```

`PROXY_KERNEL=auto`（默认）优先用 xray；`hysteria2`/`tuic` xray 不支持，会自动改用 sing-box。也可显式 `PROXY_KERNEL=xray` 或 `PROXY_KERNEL=sing-box`。

**用法 B：给一份完整原生配置**（高级，协议/参数随便配）。设 `PROXY_KERNEL_CONFIG` 指向 xray 或 sing-box 的原生 JSON；代理会自动确保里面有一个回环 socks inbound 再路由过去：

```bash
PROXY_KERNEL=sing-box PROXY_KERNEL_CONFIG=./my-singbox.json npm start
```

> 链接里的常用参数都支持：TLS（`security=tls`，`sni`/`alpn`/`fp`/`allowInsecure`）、Reality（`security=reality`，`pbk`/`sid`/`spx`）、传输层（`type=ws|grpc|http|httpupgrade`，`path`/`host`/`serviceName`）。内核子进程随主进程退出（SIGINT/SIGTERM）一并关闭，临时配置文件自动清理。

> Web UI 顶部有「config」按钮，只读展示当前生效的工具名映射规模、过滤器列表、出站代理（脱敏）、翻译/弹性开关，便于核对配置是否按预期加载。

## 协议翻译：让只支持 `/v1/chat/completions` 的厂商也能跑 Claude Code

**场景**：有的第三方厂商/路由**只认 OpenAI `/v1/chat/completions`**，不支持 Anthropic `/v1/messages`。而 Claude Code（以及 opencode 的 anthropic provider）只会说 Anthropic 协议。开启**协议翻译**后，代理在中间做格式转换，Claude Code 端**完全无感**。

> 默认是**透明直通**（不翻译，原样转发）。翻译是 **opt-in**，只有设了 `ANTHROPIC_COMPAT=chat` 才开启，且只作用于 `/v1/messages`；其它路径（`/v1/responses`、`/v1/chat/completions`）仍透明转发。

**开启方式**
```bash
ANTHROPIC_COMPAT=chat \
OPENAI_UPSTREAM=https://only-chat-vendor.example.com \
MODEL_MAP='{"claude-sonnet-4-6":"gpt-4o","claude-haiku-4-5":"gpt-4o-mini"}' \
npm start
```
然后 Claude Code 照常配置（base URL 指向代理、`x-api-key` 带 key）即可，代理会自动把它翻译成 chat 请求发往上游。

**客户端 Claude Code 配置（已用真实「只支持 chat 的厂商」实测）**

客户端全用环境变量配置（代理这侧按上面「开启方式」把 `OPENAI_UPSTREAM` 指向只认 `/v1/chat/completions` 的厂商）：
```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8788              # 不带 /v1，Claude Code 自己拼 /v1/messages
export ANTHROPIC_API_KEY=<上游厂商的 key>                     # 以 x-api-key 发出，代理原样转发给上游
export ANTHROPIC_MODEL=claude-sonnet-4-6                     # 主模型；按 MODEL_MAP → 上游 gpt-4o
export ANTHROPIC_SMALL_FAST_MODEL=claude-haiku-4-5-20251001  # 后台小/快模型；按 MODEL_MAP → gpt-4o-mini
claude
```
> **两个坑**：① `MODEL_MAP` 必须把 Claude Code 用到的**每个**模型名都映射到上游真实模型——尤其后台任务用的 **haiku 档**，漏了它那条后台请求会以原模型名透传、上游可能不认而报错；② `ANTHROPIC_BASE_URL` **不带 `/v1`**（与 Codex/opencode 相反），带了会变成 `/v1/v1/messages` 而 404。
>
> 实测：上游用 `https://api.freemodel.dev`（只支持 chat），映射 `claude-sonnet-4-6→gpt-4o`、`claude-haiku-4-5-20251001→gpt-4o-mini`，跑真实 Claude Code，`Read` 等工具调用全程正常；UI 里每条记录标 `anthropic` 但上游 URL 是 `…/v1/chat/completions`，即翻译生效。

**翻译都做了什么**
1. **请求**：Anthropic `/v1/messages` → OpenAI `/v1/chat/completions`：`system` → system 消息；content blocks（文本/图片）展开；`tool_use` → `tool_calls`、`tool_result` → `tool` 角色消息；`tools[].input_schema` → `function.parameters`；鉴权 `x-api-key: K` → `Authorization: Bearer K`。
2. **模型映射**：按 `MODEL_MAP`/`MODEL_MAP_FILE` 把进来的模型名换成上游模型名（正好覆盖 Claude Code 的 opus/sonnet/haiku 三档）；没命中就原样透传。
3. **响应（最难）**：把上游回来的 OpenAI chat **SSE 流**（`choices[].delta`、`delta.tool_calls[]` 按 index 聚合）**实时**翻译回 Anthropic 事件流（`message_start` / `content_block_start` / `content_block_delta`(`text_delta`、`input_json_delta`) / `content_block_stop` / `message_delta` / `message_stop`）；非流式则整包转一次，若客户端要的是流式还会把整包合成成 SSE 回放。`finish_reason` → `stop_reason`、`usage` 字段也做映射。
4. **落盘**：翻译类请求在 JSONL 里带一个 `translation` 字段（`{from, to, model, upstreamModel}`），方便排查。

> **模型映射文件示例**（`MODEL_MAP_FILE=./model-map.json`）：
> ```json
> { "claude-opus-4": "gpt-4o", "claude-sonnet-4-6": "gpt-4o", "claude-haiku-4-5": "gpt-4o-mini" }
> ```

## 内网打包与部署（离线）

本工具**零第三方依赖**（只用 Node 内置模块），所以内网部署很简单：把目录拷进去 + 装好 Node 运行时即可，**不需要 `npm install`、不需要联网**。

**步骤（推荐：拷目录 + 离线 Node 运行时）**
1. **准备 Node 运行时离线包**：在能联网的机器上从 nodejs.org 下载与内网 OS 匹配的免安装包（Windows 用 `node-v20.x.x-win-x64.zip`，Linux 用 `node-v20.x.x-linux-x64.tar.xz`），拷进内网解压，把其中的 `node`（Windows 是 `node.exe`）所在目录加入 `PATH`。要求 **Node >= 18**（本机实测 v20.19.0）。
2. **打包工程**：用一键打包脚本生成离线包（只含 `src/`、`public/`、`package.json`、README，**没有 `node_modules`**，因为本项目无第三方依赖）：
   ```bash
   bash scripts/package.sh                # Linux/macOS → dist/cli-proxy-logger-node.tar.gz
   # 或 Windows PowerShell：
   powershell -ExecutionPolicy Bypass -File scripts\package.ps1   # → dist\cli-proxy-logger-node.zip
   ```
   把 `dist/` 里的压缩包拷进内网解压即可（也可以直接拷整个目录）。
3. **运行**：
   ```bash
   cd cli-proxy-logger
   node src/index.js            # 等价于 npm start
   ```
   按需设置环境变量（同一条命令前缀，或先 export/set）：`PROXY_PORT` / `UI_PORT` / `LOG_DIR` / `OPENAI_UPSTREAM` / `ANTHROPIC_UPSTREAM`。
4. **常驻后台**（可选，仓库已带模板）：
   - **Linux（systemd）**：用 <code>deploy/cli-proxy-logger.service</code> 模板——改好里面的路径/端口/上游，`sudo cp` 到 `/etc/systemd/system/`，再 `sudo systemctl enable --now cli-proxy-logger`。日志看 `journalctl -u cli-proxy-logger -f`。
   - **Windows（nssm）**：用 <code>deploy/install-nssm.ps1</code>——装好 [nssm](https://nssm.cc/) 后，以管理员 PowerShell 运行该脚本即可注册成开机自启服务（卸载：`nssm remove cli-proxy-logger confirm`）。
   - 临时跑也行：Linux `nohup node src/index.js > proxy.out 2>&1 &`；Windows `start /b node src/index.js`。

### Docker / docker-compose 部署

仓库内置 `Dockerfile` + `docker-compose.yml`（零三方依赖，基于 `node:20-slim`）。容器内用 `BIND_ADDR=0.0.0.0` 监听以便发布端口可达。

```bash
cd cli-proxy-logger
docker compose up -d --build
# 代理: http://<host>:8788   UI: http://<host>:8789   日志落在 ./logs
docker compose logs -f
docker compose down
```

或不用 compose：

```bash
docker build -t cli-proxy-logger-node .
docker run -d --name cli-proxy-logger -p 8788:8788 -p 8789:8789 \
  -e BIND_ADDR=0.0.0.0 -v "$PWD/logs:/app/logs" cli-proxy-logger-node
```

**容器里走内核（高级协议）**：内核二进制必须是 **Linux 版**（容器是 Linux）。把 Linux 版 `xray`/`sing-box` 放进 `./vendor`，在 compose 里取消注释 `./vendor:/vendor:ro` 卷与 `XRAY_BIN`/`SING_BOX_BIN`/`PROXY_KERNEL` 即可。不装 Go 也能用一次性 Go 容器交叉构建（命令见 compose 文件末尾注释）。

> 注意：CLI 的 base URL 仍指向 CLI 所在机器；若代理跑在另一台机器，把 base URL 指向该机的 `8788`，并自行确保链路安全（见下方网络/安全说明）。

**关于单文件 exe（Node 变体的实话）**
本变体源码是 **ESM + 顶层 await + `import.meta`**，因此 Node 内置的 SEA（要求把入口打成单个 CommonJS 文件）和 `esbuild --format=cjs` 都**不能直接用**（顶层 await 无法编进 CJS）。要硬做需要先改造成无顶层 await 的 CJS 再打包，得不偿失。

因此 Node 变体的发布建议优先：
- **Docker 镜像**（见上，最省心、跨平台）；或
- **目录压缩包**：`scripts/package.ps1` 产出 `dist/cli-proxy-logger-node.zip`，目标机只需装 Node 20 解压即跑（无 `node_modules` 可装）。

如果你确实要一个**免装运行时的单文件 .exe**，最干净的是用 **Python 变体的 PyInstaller** 路径（见 `cli-proxy-logger-py` 的 README，一条命令产出 `dist/cli-proxy-logger.exe`，已实测可跑）。三个变体功能等价，按交付形态选即可。

> **网络/安全**：代理与 UI 默认监听本机端口（proxy `:8788`、UI `:8789`），需要对外时设 `BIND_ADDR=0.0.0.0`（Docker 已默认）。由于 CLI 的 base URL 通常指向 `127.0.0.1`，**代理一般与 CLI 部署在同一台机器**。把端口暴露到内网其他机器时请自行加访问控制（鉴权头虽落盘脱敏，但内存/转发链路上是明文）。

## 工作原理（三种 wire 格式的工具调用解析点）

| wire | 触发 CLI | 请求路径 | 工具调用解析点 |
|------|----------|----------|----------------|
| `anthropic` | Claude Code | `/v1/messages` | 流式 `content_block_start(tool_use)` + `input_json_delta`；非流式 `content[].tool_use` |
| `responses` | Codex（默认） | `/v1/responses` | 流式 `response.output_item.added(function_call)` + `function_call_arguments.delta`；非流式 `output[].function_call` |
| `chat` | Codex（chat 模式）/ 兼容 API | `/v1/chat/completions` | 流式 `choices[].delta.tool_calls[]`（按 index 聚合）；非流式 `choices[].message.tool_calls[]` |

代理始终**先把上游字节原样转发给 CLI**，再把一份解码后的副本喂给解析器，因此解析报错不会影响 CLI 的正常使用。

## 测试

```bash
npm test
```

`test/run.js` 会启动一个 mock 上游 + 代理，覆盖三种 wire 格式的流式/非流式工具调用解析，以及「客户端收到的字节与上游完全一致」的保真性校验（无需任何 API key）。

## 日志：存在哪、怎么命名

- **目录**：由 `LOG_DIR` 环境变量决定，**默认 `./logs`**。这是**相对路径**，相对的是「你启动 `npm start` 时所在的工作目录」——按上面「运行」的步骤是在 `cli-proxy-logger/` 里启动，所以默认就是 `cli-proxy-logger/logs/`。想固定位置就用绝对路径，例如 `LOG_DIR=C:\proxy-logs npm start`（PowerShell：`$env:LOG_DIR="C:\proxy-logs"; npm start`）。
- **文件名**：按天滚动，`YYYY-MM-DD.jsonl`（UTC 日期），每天一个文件。
- **写入方式**：**追加**（append），每来一条请求就追加一行，进程重启不会清空，会继续往当天的文件追加。
- **内存 vs 磁盘**：UI 列表读的是**内存里最近 500 条**；磁盘 `.jsonl` 则是**全量持久**记录。两者独立。

### 「清空」按钮做什么

UI 顶部 refresh 旁边的 **「清空」** 按钮（带确认弹窗）只清空 **内存列表 / 当前视图**（底层是 `DELETE /api/exchanges`），**不会删除磁盘上的 `.jsonl` 文件**——磁盘日志是持久审计记录，故意保留。新开一个会话想让界面干净，点它即可。

**想彻底删除磁盘日志**：手动删文件即可，例如删当天的：
```bash
rm cli-proxy-logger/logs/$(date -u +%F).jsonl      # 删当天
rm -rf cli-proxy-logger/logs                         # 全删（下次启动自动重建目录）
```
（Windows PowerShell：`Remove-Item .\logs\*.jsonl` 或 `Remove-Item -Recurse -Force .\logs`。）

## 日志格式

每行一条 JSON（`<LOG_DIR>/YYYY-MM-DD.jsonl`），关键字段：

- `wire` / `method` / `url` / `resStatus` / `durationMs`
- `reqHeaders`（脱敏后）/ `requestBodyRaw`
- `request`：归一化后的请求（`model` / `system` / `messages` / `tools`）
- `response`：归一化后的响应（`text` / `toolCalls[{id,name,args}]` / `stopReason` / `usage`）
