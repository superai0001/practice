# cli-proxy-logger（Spring Boot 学习版）

与 [`../cli-proxy-logger`](../cli-proxy-logger)（Node 实际使用版）**功能等价**的 Java/Spring Boot 实现，用来学习「如何用 Servlet 阻塞式 I/O 做一个流式反向代理并解析 LLM 工具调用」。

本地反向代理，拦截并记录 Claude Code / Codex 的全部 LLM API 请求——请求/响应参数、以及调用了哪个工具、参数是什么。不劫持 TLS、不装根证书。

代理与 Web UI **共用一个端口（默认 8788）**：

```
Claude Code / Codex ──HTTP──▶ :8788  ──HTTPS──▶ api.anthropic.com / api.openai.com
                                │
                                ├─ /v1/**         代理 + 解析（ProxyController）
                                ├─ /api/exchanges 查询接口（ExchangeApiController）
                                └─ /              Web UI（static/index.html）
```

## 运行

需要 **JDK 8 及以上**、Maven。本工程刻意用 Java 8 兼容写法（`pom.xml` 里 `java.version=8`），所以 **JDK 8 / 11 / 17 都能编译运行**——内网常见的 JDK 8 也 OK（已用 Temurin 1.8.0_492 实测：编译产物为 Java 8 字节码、`java -jar` 启动、端到端代理+解析+落盘全通过）。

```bash
cd cli-proxy-logger-java
mvn spring-boot:run
# 或： mvn -q package && java -jar target/cli-proxy-logger-1.0.0.jar
```

打开 http://127.0.0.1:8788/ 浏览抓到的请求。

> **关于 JDK 版本**：默认 `java.version=8`（最大兼容内网环境）。如果你的环境是 JDK 11/17 且想用更高字节码，把 `pom.xml` 的 `<java.version>` 改成 `11` 或 `17` 即可，代码无需改动（未使用任何 Java 9+ 专有 API）。

## 使用配置模板（Codex / Claude Code / opencode）

与 Node 版完全一致，只是本版代理 + UI **共用 8788**。把各 CLI 的 base URL 指向本地代理即可。**最关键的区别：Codex / opencode 的 base URL 带 `/v1`；Claude Code 的不带 `/v1`**（它自己会拼 `/v1/messages`，带了会变成 `/v1/v1/messages` 而 404）。

代理按路径区分上游：`/v1/responses` 与 `/v1/chat/completions` 走 `proxy.openai-upstream`，`/v1/messages` 走 `proxy.anthropic-upstream`（见本节末「启动代理时设置上游」）。

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
跑：`opencode run -m myproxy-chat/gpt-5 "..."`（或 `myproxy-resp/...`、`anthropic/...`）。chat / responses 路径都抓到工具调用参数与请求/响应体；anthropic 路径也被正确路由落盘（若上游按 CLI 指纹放行——如只认 Claude Code——可能拒绝 opencode，属上游限制，与代理无关）。

### 启动代理时设置上游

本版上游用 `proxy.*` 配置（默认 `https://api.openai.com` / `https://api.anthropic.com`）。指向 freemodel 的两种写法：

```bash
# 命令行参数
mvn spring-boot:run -Dspring-boot.run.arguments="--proxy.openai-upstream=https://api.freemodel.dev --proxy.anthropic-upstream=https://cc.freemodel.dev"

# 或环境变量（Spring Boot relaxed binding）
PROXY_OPENAI_UPSTREAM=https://api.freemodel.dev PROXY_ANTHROPIC_UPSTREAM=https://cc.freemodel.dev mvn spring-boot:run
```
也可直接写进 `application.yml` 的 `proxy.openai-upstream` / `proxy.anthropic-upstream`。

## 配置（application.yml，前缀 `proxy.*`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `server.port` | `8788` | 代理 + UI 端口 |
| `proxy.anthropic-upstream` | `https://api.anthropic.com` | Anthropic 上游 |
| `proxy.openai-upstream` | `https://api.openai.com` | OpenAI 上游 |
| `proxy.log-dir` | `./logs` | JSONL 日志目录 |
| `proxy.redact-auth` | `true` | 落盘时脱敏 `x-api-key`/`authorization` |
| `proxy.max-body-bytes` | `2000000` | 单条 body 落盘上限 |
| `proxy.anthropic-compat` | 空（透明直通） | 设为 `chat` 时开启「协议翻译」：把进来的 Anthropic `/v1/messages` 翻译成 OpenAI `/v1/chat/completions` 发往 `proxy.openai-upstream`（见下一节） |
| `proxy.model-map` | 空 | 模型映射表，JSON（`{"claude-sonnet-4-6":"gpt-4o"}`）或逗号分隔（`claude-sonnet-4-6=gpt-4o,claude-haiku-4-5=gpt-4o-mini`）；没命中就原样透传模型名 |
| `proxy.model-map-file` | 空 | 模型映射 JSON 文件路径（优先于 `proxy.model-map`） |
| `proxy.providers` | 空 | 供应商池，JSON 数组（见「弹性」节）。配了池就启用故障转移+熔断 |
| `proxy.providers-file` | 空 | 供应商池 JSON 文件路径（优先于 `proxy.providers`） |
| `proxy.breaker` | 关闭 | 设 `true` 强制开启熔断；**配了 `proxy.providers` 池时自动开启** |
| `proxy.breaker-failures` | `5` | 单个供应商连续失败多少次后熔断（OPEN） |
| `proxy.breaker-cooldown-ms` | `30000` | 熔断后冷却多久（毫秒）才放探测请求（HALF_OPEN） |
| `proxy.breaker-half-open-max` | `1` | HALF_OPEN 时允许的并发探测数 |
| `proxy.failover-statuses` | `429,500,502,503,504` | 触发故障转移的上游状态码（逗号分隔） |
| `proxy.rectify` | 关闭 | 设 `true` 开启 Anthropic thinking 整流（仅作用于 `/v1/messages`） |
| `proxy.rectify-signature` | 开启 | 设 `false`/`0` 关闭「签名整流」子规则 |
| `proxy.rectify-budget` | 开启 | 设 `false`/`0` 关闭「budget 整流」子规则 |
| `proxy.tool-name-case` | 关闭 | 设 `true` 开启「工具名规范化」（小写→PascalCase，仅作用于 `/v1/messages`，见下节） |
| `proxy.tool-name-request` | 开启 | 设 `false` 关闭请求侧工具名改写（`tools[].name` 与历史 `tool_use.name`） |
| `proxy.tool-name-response` | 开启 | 设 `false` 关闭响应侧工具名改写（含 SSE `content_block_start`） |
| `proxy.tool-name-repair-input` | 开启 | 设 `false` 关闭 `tool_use.input` 修复（被序列化成字符串的数组/对象还原） |
| `proxy.tool-name-map` | 空 | 工具名映射 JSON 对象（`{"todowrite":"TodoWrite"}`），合并/覆盖内置表 |
| `proxy.tool-name-map-file` | 空 | 工具名映射 JSON 文件路径（优先于 `proxy.tool-name-map`） |
| `proxy.filters` | 空 | 请求过滤器/规则 JSON 数组（见下节）；转发上游前改写请求头/请求体 |
| `proxy.filters-file` | 空 | 过滤器 JSON 文件路径（优先于 `proxy.filters`） |
| `proxy.upstream-proxy` | 空 | 出站代理 URL（`http://`/`https://`/`socks5://`，可带 `user:pass@`）；未设时回退 `HTTPS_PROXY`/`HTTP_PROXY` |

> 这几项都支持环境变量（Spring relaxed binding）：`PROXY_ANTHROPIC_COMPAT` / `PROXY_MODEL_MAP` / `PROXY_MODEL_MAP_FILE`；为与 Node/Python 版保持一致，也兼容裸的 `ANTHROPIC_COMPAT` / `MODEL_MAP` / `MODEL_MAP_FILE`。弹性相关项同理：`PROXY_PROVIDERS` / `PROXY_BREAKER` / `PROXY_BREAKER_FAILURES` / `PROXY_FAILOVER_STATUSES` / `PROXY_RECTIFY` …，也兼容裸的 `PROVIDERS` / `PROVIDERS_FILE` / `BREAKER` / `BREAKER_FAILURES` / `BREAKER_COOLDOWN_MS` / `BREAKER_HALFOPEN_MAX` / `FAILOVER_STATUSES` / `RECTIFY`(或 `RECTIFIER`) / `RECTIFY_SIGNATURE` / `RECTIFY_BUDGET`。扩展三件套同理：`PROXY_TOOL_NAME_CASE` / `PROXY_FILTERS` / `PROXY_UPSTREAM_PROXY` …，也兼容裸的 `TOOL_NAME_CASE` / `TOOL_NAME_REQUEST` / `TOOL_NAME_RESPONSE` / `TOOL_NAME_REPAIR_INPUT` / `TOOL_NAME_MAP` / `TOOL_NAME_MAP_FILE` / `FILTERS` / `FILTERS_FILE` / `UPSTREAM_PROXY`（出站代理还兼容 `HTTPS_PROXY` / `HTTP_PROXY`）。

## 弹性：多供应商故障转移 + 熔断 + thinking 整流（全部 opt-in）

> 这三块**默认全关**，不配就和以前完全一样（透明直通、字节级不变）。只有显式配置时才进入「弹性路径」。三套实现（Node/Python/Java）逻辑一致。

### 1) 多供应商池 + 故障转移（failover）

用 `proxy.providers`（或 `proxy.providers-file`，也可裸环境变量 `PROVIDERS`/`PROVIDERS_FILE`）配一个有序的供应商池。每个供应商：

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
- 池为空时回退到单上游（`proxy.anthropic-upstream`/`proxy.openai-upstream`），即旧行为。

**故障转移语义（关键）**：只在「**还没把响应流给客户端之前**」切换——即上游**连接失败**或返回**故障转移状态码**（默认 429/500/502/503/504）时，记一次失败并试下一个供应商。**一旦上游回了 2xx 开始流式回写，就不再切换**（避免重放、保证保真）。其它非 2xx（如 400/401/403）视为终止错误，原样回写客户端、不再故障转移。池全部失败则把最后一次上游错误原样回放给客户端。

### 2) 熔断（circuit breaker）

每个供应商一个独立熔断器，状态机 `CLOSED → OPEN → HALF_OPEN`：

- 连续失败累计到 `proxy.breaker-failures`（默认 5）→ **OPEN**，在 `proxy.breaker-cooldown-ms`（默认 30s）内直接跳过该供应商；
- 冷却后进入 **HALF_OPEN**，放最多 `proxy.breaker-half-open-max`（默认 1）个探测请求，成功则回到 CLOSED，失败则重新 OPEN；
- **每次请求结束都会释放 HALF_OPEN 探测名额**（避免探测名额泄漏导致熔断器卡死）；
- **区分错误类型**：上游真故障（连接失败 / 故障转移状态码）才计入失败；客户端类错误（如 400/401，以及被整流的请求）记为「中性」，不计入熔断。
- 配了 `proxy.providers` 池就自动开启熔断；没有池时可用 `proxy.breaker=true` 单独开启（对单上游也生效）。

### 3) Anthropic thinking 整流器（rectifier，仅 `/v1/messages`）

跨供应商切换或换模型时，历史 `thinking` 块签名/budget 约束可能被新上游拒。开启 `proxy.rectify=true` 后，代理检测到特定错误会**改写请求体并对同一供应商重试一次**（重试在故障转移之前）：

- **签名整流**（`proxy.rectify-signature`，默认开）：上游报 thinking 签名校验错 → 删掉历史消息里的 `thinking`/`redacted_thinking` 块及各块的 `signature` 字段 → 重试一次。
- **budget 整流**（`proxy.rectify-budget`，默认开）：上游报 `budget_tokens` 约束错（如要求 ≥1024）→ 把 `thinking.type` 设 `enabled`、`budget_tokens=32000`，必要时把 `max_tokens` 提到 64000 → 重试一次。
- 只整流**一次**；整流后仍失败则进入故障转移。整流触发的失败计为「中性」，不计入熔断。

> 落盘：走弹性路径的请求在 JSONL 里带一个 `resilience` 字段（`{providerId, attempt, rectified?, failedOver?}`），便于排查实际命中了哪个供应商、是否发生了整流/故障转移。

**最小示例（两个 anthropic 供应商 + 熔断 + 整流）**
```bash
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.providers='[{"id":"a","group":"anthropic","baseUrl":"https://api.vendor-a.com","apiKey":"sk-a"},{"id":"b","group":"anthropic","baseUrl":"https://api.vendor-b.com","apiKey":"sk-b"}]' \
  --proxy.rectify=true
```
> 也可用环境变量：`PROVIDERS='…' RECTIFY=1 java -jar …`。

## 扩展三件套：工具名规范化 + 请求过滤器 + 出站代理（全部 opt-in）

> 三块互相独立、**默认全关**。不配就和以前完全一样（透明直通、字节级不变）。三套实现（Node/Python/Java）逻辑一致。

### 1) 工具名规范化（`proxy.tool-name-case=true`）

有些第三方上游对工具名大小写敏感，要求 `TodoWrite` 这样的 PascalCase，而 opencode/部分客户端会发小写 `todowrite`。开启后代理对 **Anthropic `/v1/messages`** 流量做：

- **请求侧**：把 `tools[].name` 和历史 `messages[].content[].tool_use.name` 从小写改成 PascalCase（内置表 `todowrite→TodoWrite`、`webfetch→WebFetch`、`google_search→Google_Search`，其余首字母大写）。已是 PascalCase 的原样保留。
- **响应侧**：把响应里 `tool_use.name`（含 SSE `content_block_start` 事件）改回客户端期望的形态；并**修复 `tool_use.input`**——上游有时把数组/对象序列化成 JSON 字符串（`"[\"a\",\"b\"]"`），这里还原成真正的数组/对象。
- 用 `proxy.tool-name-map`（或 `proxy.tool-name-map-file`）自定义/覆盖映射表；`proxy.tool-name-request` / `proxy.tool-name-response` / `proxy.tool-name-repair-input` 可分别关掉某一侧（默认都开）。

```bash
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.tool-name-case=true \
  --proxy.tool-name-map='{"todowrite":"TodoWrite","webfetch":"WebFetch"}'
```

**只关响应侧改写**（只规范化发往上游的请求，原样回传上游响应）：

```bash
java -jar target/cli-proxy-logger-1.0.0.jar --proxy.tool-name-case=true --proxy.tool-name-response=false
# 等价环境变量：TOOL_NAME_CASE=true TOOL_NAME_RESPONSE=false
```

**只做响应修复、不动请求**（上游名字已对，只想把序列化成字符串的 `tool_use.input` 还原）：

```bash
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.tool-name-case=true \
  --proxy.tool-name-request=false \
  --proxy.tool-name-response=false \
  --proxy.tool-name-repair-input=true
```

**用文件加载映射表**（`proxy.tool-name-map-file`，优先级低于 `proxy.tool-name-map`）：

```bash
cat > tool-name-map.json <<'JSON'
{ "todowrite": "TodoWrite", "webfetch": "WebFetch", "google_search": "Google_Search" }
JSON
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.tool-name-case=true \
  --proxy.tool-name-map-file=./tool-name-map.json
```

### 2) 请求过滤器 / 规则引擎（`proxy.filters=...`）

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
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.filters='[{"name":"beta","action":"set_header","target":"anthropic-beta","value":"context-1m-2025-08-07"}]'
```

**用文件加载过滤器**（`proxy.filters-file`，优先级低于 `proxy.filters`；适合规则较多时维护成独立文件）：

```bash
cat > filters.json <<'JSON'
[
  { "name": "beta-header",    "action": "set_header",  "target": "anthropic-beta",          "value": "context-1m-2025-08-07", "priority": 1 },
  { "name": "force-adaptive", "action": "json_set",    "target": "thinking.type",           "value": "adaptive",              "priority": 2 },
  { "name": "min-budget",     "action": "json_set",    "target": "thinking.budget_tokens",  "value": 1024,                     "priority": 3 },
  { "name": "drop-trace",     "action": "delete_header","target": "x-internal-trace",                                          "priority": 4 }
]
JSON
java -jar target/cli-proxy-logger-1.0.0.jar --proxy.filters-file=./filters.json
```

### 3) 出站代理（`proxy.upstream-proxy=...`）

让代理**去上游**的连接走一个外部代理（内网出口常见需求）。用内置 `java.net.Proxy` 实现，支持 `http://`、`https://`、`socks5://`（`socks://`、`socks5h://` 视为 socks5），可带 `user:pass@` 鉴权（鉴权时安装进程级 `Authenticator`）。未设 `proxy.upstream-proxy` 时回退读 `UPSTREAM_PROXY`/`HTTPS_PROXY`/`HTTP_PROXY`（含小写）。

```bash
java -jar target/cli-proxy-logger-1.0.0.jar --proxy.upstream-proxy=socks5://127.0.0.1:1080
# 或带鉴权的 HTTP 代理：
java -jar target/cli-proxy-logger-1.0.0.jar --proxy.upstream-proxy=http://user:pass@proxy.example.com:3128
```

#### 内核出站：用 xray-core / sing-box 支持更多协议

`http`/`socks5` 之外，出站代理还能走 **VMess / VLESS / Trojan / Shadowsocks / Hysteria2 / TUIC** 等协议——做法是把官方内核（[xray-core](https://github.com/XTLS/Xray-core) 或 [sing-box](https://github.com/SagerNet/sing-box)）作为本地子进程拉起：内核 inbound 是一个**仅回环的 SOCKS5**，outbound 是你的高级协议；代理现有的 socks 隧道（`java.net.Proxy`）直接指向这个本地端口。Java 侧仅用 JDK + Jackson（已随 web starter 引入）、隧道逻辑零改动。与 Node/Python 版逻辑 1:1 对齐（`com.practice.cliproxy.kernel`）。

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

把二进制放进 PATH、或放到本模块同级的 `vendor/` 目录、或用 `XRAY_BIN` / `SING_BOX_BIN` 指定绝对路径。查找顺序：显式路径 → `vendor/` → PATH。

**用法 A：直接给分享链接**（最省事）。`proxy.upstream-proxy`（或 `UPSTREAM_PROXY`）识别到高级协议链接就自动走内核：

```bash
# VLESS + Reality（自动选 xray）
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.upstream-proxy='vless://<uuid>@example.com:443?encryption=none&security=reality&pbk=<pubkey>&sid=<shortid>&sni=www.apple.com&fp=chrome&type=tcp#node'
# Shadowsocks（aes-128-gcm 等）
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.upstream-proxy='ss://<base64(method:password)>@example.com:8388#node'
# Hysteria2 / TUIC（只能 sing-box，auto 会自动选）
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.upstream-proxy='hysteria2://<pass>@example.com:8443?sni=example.com#node'
```

`proxy.proxy-kernel=auto`（默认）优先用 xray；`hysteria2`/`tuic` xray 不支持，会自动改用 sing-box。也可显式 `=xray` 或 `=sing-box`。

**用法 B：给一份完整原生配置**（高级，协议/参数随便配）。设 `proxy.proxy-kernel-config` 指向 xray 或 sing-box 的原生 JSON；代理会自动确保里面有一个回环 socks inbound 再路由过去：

```bash
java -jar target/cli-proxy-logger-1.0.0.jar \
  --proxy.proxy-kernel=sing-box --proxy.proxy-kernel-config=./my-singbox.json
```

**内核相关配置项**（`application.yml` 前缀 `proxy.*`，均支持环境变量回退）

| 配置项 | 环境变量回退 | 默认 | 说明 |
|---|---|---|---|
| `proxy.proxy-kernel` | `PROXY_KERNEL` | `auto` | 内核选择：`auto`/`xray`/`sing-box`。`auto` 优先 xray；`hysteria2`/`tuic` 只能 sing-box，会自动选 |
| `proxy.proxy-kernel-config` | `PROXY_KERNEL_CONFIG` | 空 | 完整原生内核配置 JSON 文件路径（高级用法；自动确保里面有一个回环 socks inbound 并路由过去）。设了它即启用内核 |
| `proxy.xray-bin` | `XRAY_BIN` | 空 | xray 二进制路径；不设则按 PATH 与同级 `vendor/` 目录查找 |
| `proxy.sing-box-bin` | `SING_BOX_BIN` | 空 | sing-box 二进制路径；查找规则同上 |
| `proxy.proxy-kernel-socks-port` | `PROXY_KERNEL_SOCKS_PORT` | 随机 | 内核本地 socks inbound 端口（默认取空闲端口） |

> 链接里的常用参数都支持：TLS（`security=tls`，`sni`/`alpn`/`fp`/`allowInsecure`）、Reality（`security=reality`，`pbk`/`sid`/`spx`）、传输层（`type=ws|grpc|http|httpupgrade`，`path`/`host`/`serviceName`）。内核子进程随主进程退出（JVM shutdown hook）一并关闭，临时配置文件自动清理。

> Web UI 顶部有「config」按钮，只读展示当前生效的工具名映射规模、过滤器列表、出站代理（脱敏）、翻译/弹性开关，便于核对配置是否按预期加载。

## 协议翻译：让只支持 `/v1/chat/completions` 的厂商也能跑 Claude Code

**场景**：有的第三方厂商/路由**只认 OpenAI `/v1/chat/completions`**，不支持 Anthropic `/v1/messages`。而 Claude Code（以及 opencode 的 anthropic provider）只会说 Anthropic 协议。开启**协议翻译**后，代理在中间做格式转换，Claude Code 端**完全无感**。

> 默认是**透明直通**（不翻译，原样转发）。翻译是 **opt-in**，只有设了 `proxy.anthropic-compat=chat`（或 `ANTHROPIC_COMPAT=chat`）才开启，且只作用于 `/v1/messages`；其它路径（`/v1/responses`、`/v1/chat/completions`）仍透明转发。

**开启方式**
```bash
# 命令行参数
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --proxy.anthropic-compat=chat \
  --proxy.openai-upstream=https://only-chat-vendor.example.com \
  --proxy.model-map={\"claude-sonnet-4-6\":\"gpt-4o\",\"claude-haiku-4-5\":\"gpt-4o-mini\"}"
# 或环境变量（等价）
ANTHROPIC_COMPAT=chat \
PROXY_OPENAI_UPSTREAM=https://only-chat-vendor.example.com \
MODEL_MAP='{"claude-sonnet-4-6":"gpt-4o","claude-haiku-4-5":"gpt-4o-mini"}' \
java -jar target/cli-proxy-logger-1.0.0.jar
```
然后 Claude Code 照常配置（base URL 指向代理、`x-api-key` 带 key）即可，代理会自动把它翻译成 chat 请求发往上游。

**客户端 Claude Code 配置（已用真实「只支持 chat 的厂商」实测）**

客户端全用环境变量配置（代理这侧按上面「开启方式」把 `proxy.openai-upstream` / `PROXY_OPENAI_UPSTREAM` 指向只认 `/v1/chat/completions` 的厂商）：
```bash
export ANTHROPIC_BASE_URL=http://127.0.0.1:8788              # 不带 /v1，Claude Code 自己拼 /v1/messages
export ANTHROPIC_API_KEY=<上游厂商的 key>                     # 以 x-api-key 发出，代理原样转发给上游
export ANTHROPIC_MODEL=claude-sonnet-4-6                     # 主模型；按 model-map → 上游 gpt-4o
export ANTHROPIC_SMALL_FAST_MODEL=claude-haiku-4-5-20251001  # 后台小/快模型；按 model-map → gpt-4o-mini
claude
```
> **两个坑**：① 模型映射必须把 Claude Code 用到的**每个**模型名都映射到上游真实模型——尤其后台任务用的 **haiku 档**，漏了它那条后台请求会以原模型名透传、上游可能不认而报错；② `ANTHROPIC_BASE_URL` **不带 `/v1`**（与 Codex/opencode 相反），带了会变成 `/v1/v1/messages` 而 404。
>
> 实测（在 Node 版上端到端验证，三套翻译逻辑一致）：上游用 `https://api.freemodel.dev`（只支持 chat），映射 `claude-sonnet-4-6→gpt-4o`、`claude-haiku-4-5-20251001→gpt-4o-mini`，跑真实 Claude Code，`Read` 等工具调用全程正常；UI 里每条记录标 `anthropic` 但上游 URL 是 `…/v1/chat/completions`，即翻译生效。

**翻译都做了什么**
1. **请求**：Anthropic `/v1/messages` → OpenAI `/v1/chat/completions`：`system` → system 消息；content blocks（文本/图片）展开；`tool_use` → `tool_calls`、`tool_result` → `tool` 角色消息；`tools[].input_schema` → `function.parameters`；鉴权 `x-api-key: K` → `Authorization: Bearer K`。
2. **模型映射**：按 `proxy.model-map`/`proxy.model-map-file` 把进来的模型名换成上游模型名（正好覆盖 Claude Code 的 opus/sonnet/haiku 三档）；没命中就原样透传。
3. **响应（最难）**：把上游回来的 OpenAI chat **SSE 流**（`choices[].delta`、`delta.tool_calls[]` 按 index 聚合）**实时**翻译回 Anthropic 事件流（`message_start` / `content_block_start` / `content_block_delta`(`text_delta`、`input_json_delta`) / `content_block_stop` / `message_delta` / `message_stop`）；非流式则整包转一次，若客户端要的是流式还会把整包合成成 SSE 回放。`finish_reason` → `stop_reason`、`usage` 字段也做映射。
4. **落盘**：翻译类请求在 JSONL 里带一个 `translation` 字段（`{from, to, model, upstreamModel}`），方便排查。

> **模型映射文件示例**（`--proxy.model-map-file=./model-map.json`）：
> ```json
> { "claude-opus-4": "gpt-4o", "claude-sonnet-4-6": "gpt-4o", "claude-haiku-4-5": "gpt-4o-mini" }
> ```

## 内网打包与部署（离线）

Java 版与 Node/Python 不同：它**有第三方依赖**（Spring Boot、内嵌 Tomcat、Jackson），内网机器无法从 Maven 中央仓库下载。所以**核心思路是：在能联网的机器上打成 fat jar（所有依赖打进单个 jar），再把 jar 拷到内网用 JRE 直接跑**。

**步骤**
1. **在联网机器构建 fat jar**（首次会从中央仓库拉依赖，所以必须联网）。可用一键打包脚本（会 `mvn package` 并把 jar + 样例 `application.yml` 放进 `dist/`）：
   ```bash
   bash scripts/package.sh                # Linux/macOS
   # 或 Windows PowerShell：
   powershell -ExecutionPolicy Bypass -File scripts\package.ps1
   # 也可以手动：mvn -DskipTests package
   ```
   产物：`target/cli-proxy-logger-1.0.0.jar`（脚本会再拷一份到 `dist/`），本机实测约 **17 MB**，**已内嵌 Spring + Tomcat + Jackson + 本工程的静态 UI**，是一个自包含可执行 jar（`spring-boot-maven-plugin` 的 repackage 会自动做这件事）。
2. **准备 JRE**：内网机器装 **JRE/JDK 8 及以上**（本工程默认 `java.version=8`，所以 JDK 8 即可；11/17 也行）。可用各厂商的离线包（Temurin/Adoptium、Zulu、Microsoft OpenJDK 等）。**不需要 Maven、不需要源码**——只要这一个 jar + JRE。注意：构建机的 JDK 版本要 **≥ 你设定的 `java.version`**（用 JDK 8 构建则产出 Java 8 字节码，能在 8/11/17 上跑；用 JDK 17 构建且 `java.version=8` 也能产出 Java 8 字节码）。
3. **拷贝并运行**：把 `cli-proxy-logger-1.0.0.jar` 拷到内网，运行：
   ```bash
   java -jar cli-proxy-logger-1.0.0.jar
   # 改端口 / 上游（命令行参数）：
   java -jar cli-proxy-logger-1.0.0.jar --server.port=8788 \
        --proxy.openai-upstream=https://内网网关/v1上游 \
        --proxy.anthropic-upstream=https://内网网关/anthropic上游 \
        --proxy.log-dir=/var/log/cli-proxy
   # 或用环境变量：PROXY_OPENAI_UPSTREAM / PROXY_ANTHROPIC_UPSTREAM / PROXY_LOG_DIR / SERVER_PORT
   ```
   也可在 jar 同级目录放一个 `application.yml`（或 `./config/application.yml`），Spring Boot 启动时会自动加载并覆盖内置配置——内网改配置不用重新打包。
4. **常驻后台**（可选，仓库已带模板）：
   - **Linux（systemd）**：用 <code>deploy/cli-proxy-logger.service</code> 模板——把 jar 放到 `/opt/cli-proxy-logger-java/`，改好里面的 java 路径/端口/上游，`sudo cp` 到 `/etc/systemd/system/cli-proxy-logger-java.service`，再 `sudo systemctl enable --now cli-proxy-logger-java`。日志看 `journalctl -u cli-proxy-logger-java -f`。
   - **Windows（nssm）**：用 <code>deploy/install-nssm.ps1</code>——装好 [nssm](https://nssm.cc/) 后以管理员 PowerShell 运行即可注册成开机自启服务（卸载：`nssm remove cli-proxy-logger-java confirm`）。
   - 临时跑也行：Linux `nohup java -jar ... &`。

> 仓库还附了 <code>deploy/application.yml.sample</code>（改名为 `application.yml` 放在 jar 同级目录即可覆盖配置，无需重新打包）。

**如果必须在内网用 Maven 构建**（不推荐，麻烦）：在联网机器用 `mvn -DskipTests package dependency:go-offline` 预热本地仓库 `~/.m2/repository`，把整个 `.m2/repository` 拷到内网同路径，再用 `mvn -o package` 离线构建。直接拷 fat jar 更省事。

### Docker / docker-compose 部署

仓库内置多阶段 `Dockerfile`（`maven:3.9-eclipse-temurin-8` 构建 fat jar → `eclipse-temurin:8-jre` 运行）+ `docker-compose.yml`。Spring Boot 默认监听所有网卡，发布端口即可达，无需额外 `BIND_ADDR`。

```bash
cd cli-proxy-logger-java
docker compose up -d --build
# 代理 + UI: http://<host>:8788   日志落在 ./logs
docker compose logs -f
docker compose down
```

或不用 compose：

```bash
docker build -t cli-proxy-logger-java .
docker run -d --name cli-proxy-logger-java -p 8788:8788 \
  -v "$PWD/logs:/app/logs" cli-proxy-logger-java
```

**容器里走内核（高级协议）**：内核二进制必须是 **Linux 版**。把 Linux 版 `xray`/`sing-box` 放进 `./vendor`，在 compose 里取消注释 `./vendor:/vendor:ro` 卷与 `XRAY_BIN`/`SING_BOX_BIN`/`PROXY_KERNEL` 即可（不装 Go 的交叉构建命令见 compose 文件末尾注释）。

### 关于 .exe / 单文件分发

Java 的标准交付物就是上面那个**可执行 fat jar**（`java -jar cli-proxy-logger-1.0.0.jar`，已实测在 JDK 8 上启动并服务 `:8788`），目标机只需一个 JRE。

如果一定要**免装 JRE 的原生 .exe/安装包**，用 JDK 自带的 `jpackage`（**需 JDK 14+，本机是 JDK 8 无此工具，未实测**）：

```bash
# 在装了 JDK 17+ 的机器上：
jpackage --type app-image --name cli-proxy-logger \
  --input target --main-jar cli-proxy-logger-1.0.0.jar \
  --main-class org.springframework.boot.loader.JarLauncher
# Windows 下 --type exe / msi 可出安装包（需 WiX）
```

多数场景直接用 **Docker 镜像**或 **fat jar + JRE** 即可，无需 jpackage。

> **网络/安全**：代理 + UI 共用一个端口（默认 `:8788`）。CLI 的 base URL 指向 `127.0.0.1`，**建议与 CLI 同机部署**。Spring Boot/Tomcat 默认会监听所有网卡（Docker 下正需如此），若只想本机可访问，加 `--server.address=127.0.0.1`，避免端口暴露到内网其他机器。

## 代码结构（控制/数据流顺序）

| 类 | 职责 |
|----|------|
| `proxy/UpstreamResolver` | 据请求路径选择上游 + wire 类型 |
| `proxy/ProxyController` | `/v1/**`：转发字节 + 旁路解析 + 记录 |
| `sse/SseParser` | 增量解析 SSE 事件 |
| `parser/WireParser` + `AnthropicParser` / `OpenAiResponsesParser` / `OpenAiChatParser` | 三种 wire 的请求/响应/工具调用解析 |
| `parser/StreamAggregator` | 流式聚合，重建文本 + 工具调用 |
| `recorder/ExchangeRecorder` | 内存最近列表 + 按天 JSONL 落盘 |
| `web/ExchangeApiController` | `/api/exchanges` 查询接口 + `DELETE /api/exchanges` 清空内存列表 |
| `model/*` | 统一数据模型 `Exchange` / `NormalizedRequest` / `NormalizedResponse` / `ToolCall` |

## 工具调用解析点

| wire | 路径 | 解析点 |
|------|------|--------|
| `anthropic` | `/v1/messages` | SSE `content_block_start(tool_use)` + `input_json_delta`；非流式 `content[].tool_use` |
| `responses` | `/v1/responses` | SSE `response.output_item.added(function_call)` + `function_call_arguments.delta`；非流式 `output[].function_call` |
| `chat` | `/v1/chat/completions` | SSE `choices[].delta.tool_calls[]`（按 index 聚合）；非流式 `message.tool_calls[]` |

> 实现说明：转发时不带 `Accept-Encoding`，由 `HttpURLConnection` 自行协商并透明解压 gzip，
> 因此读到/转发的都是 identity 字节，解析无需再处理压缩。

## 日志：存在哪、怎么命名

- **目录**：由 `proxy.log-dir` 决定，**默认 `./logs`**。这是**相对路径**，相对的是「你启动 `mvn spring-boot:run`（或 `java -jar`）时所在的工作目录」——按上面「运行」的步骤是在 `cli-proxy-logger-java/` 里启动，所以默认就是 `cli-proxy-logger-java/logs/`。想固定位置就用绝对路径，例如 `mvn spring-boot:run -Dspring-boot.run.arguments="--proxy.log-dir=C:\proxy-logs"`，或环境变量 `PROXY_LOG_DIR=C:\proxy-logs`，或直接写进 `application.yml`。
- **文件名**：按天滚动，`YYYY-MM-DD.jsonl`（系统本地日期 `LocalDate.now()`），每天一个文件。
- **写入方式**：**追加**（`StandardOpenOption.APPEND`），每来一条请求就追加一行，进程重启不会清空，会继续往当天的文件追加。
- **内存 vs 磁盘**：UI 列表读的是**内存里最近 500 条**；磁盘 `.jsonl` 则是**全量持久**记录。两者独立。

### 「清空」按钮做什么

UI 顶部 refresh 旁边的 **「清空」** 按钮（带确认弹窗）只清空 **内存列表 / 当前视图**（底层是 `DELETE /api/exchanges` → `ExchangeRecorder.clear()`），**不会删除磁盘上的 `.jsonl` 文件**——磁盘日志是持久审计记录，故意保留。新开一个会话想让界面干净，点它即可。

**想彻底删除磁盘日志**：手动删文件即可。
```bash
rm cli-proxy-logger-java/logs/$(date +%F).jsonl   # 删当天
rm -rf cli-proxy-logger-java/logs                   # 全删（下次启动自动重建目录）
```
（Windows PowerShell：`Remove-Item .\logs\*.jsonl` 或 `Remove-Item -Recurse -Force .\logs`。）

日志每行一条 JSON（`<proxy.log-dir>/YYYY-MM-DD.jsonl`），字段与 Node/Python 版一致：`wire` / `method` / `url` / `resStatus` / `durationMs` / `reqHeaders`（脱敏）/ `requestBodyRaw` / `request` / `response`。
