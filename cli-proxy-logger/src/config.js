// Configuration loading from environment variables / CLI args.
//
// Env vars (all optional):
//   PROXY_PORT       proxy listen port            (default 8788)
//   UI_PORT          web UI listen port           (default 8789)
//   BIND_ADDR        listen address for proxy+UI  (default 127.0.0.1; use
//                    0.0.0.0 to expose, e.g. inside Docker)
//   LOG_DIR          directory for JSONL logs     (default <module>/logs)
//   REDACT_AUTH      "0" to keep raw auth headers (default redact)
//   ANTHROPIC_UPSTREAM  override Anthropic upstream (default https://api.anthropic.com)
//   OPENAI_UPSTREAM     override OpenAI upstream    (default https://api.openai.com)
//   MAX_BODY_BYTES   max stored body size, larger is truncated (default 2_000_000)
//   ANTHROPIC_COMPAT    "chat" to translate incoming /v1/messages into OpenAI
//                       /v1/chat/completions (for vendors that only support chat).
//                       Default off (transparent pass-through).
//   MODEL_MAP        model name remap used in compat mode. JSON object
//                       (e.g. {"claude-sonnet-4-6":"gpt-4o"}) OR comma list
//                       (e.g. "claude-sonnet-4-6=gpt-4o,claude-haiku-4-5=gpt-4o-mini").
//   MODEL_MAP_FILE   path to a JSON file with the same mapping (alternative to MODEL_MAP).
//
//   --- resilience (all opt-in; default behavior is unchanged when unset) ---
//   PROVIDERS        JSON array of failover providers, each
//                    {id, group:"anthropic"|"openai", baseUrl, apiKey?}. When a
//                    group's pool is non-empty we try its providers in order.
//   PROVIDERS_FILE   path to a JSON file with the same array.
//   BREAKER          "1"/"on" to enable the circuit breaker (auto-on when a
//                    provider pool is configured).
//   BREAKER_FAILURES failures before a provider opens          (default 5)
//   BREAKER_COOLDOWN_MS  open-state cooldown in ms             (default 30000)
//   BREAKER_HALFOPEN_MAX concurrent half-open probes           (default 1)
//   FAILOVER_STATUSES    comma list of HTTP statuses that trigger failover
//                        (default 429,500,502,503,504)
//   RECTIFY          "1"/"on" to enable Anthropic thinking rectification.
//   RECTIFY_SIGNATURE / RECTIFY_BUDGET  "0" to disable one sub-rule.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseProviders } from './providers.js';
import { parseFilters } from './filters.js';
import { DEFAULT_TOOL_NAME_MAP } from './transform.js';
import { createOutbound } from './outbound.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function intEnv(name, fallback) {
  const v = process.env[name];
  if (v === undefined || v === '') return fallback;
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) ? n : fallback;
}

// Parse the model map from a string that is either JSON or a comma list of
// `from=to` pairs. Returns a plain object (empty when nothing is configured).
export function parseModelMap(raw) {
  if (!raw || typeof raw !== 'string') return {};
  const trimmed = raw.trim();
  if (trimmed === '') return {};
  if (trimmed.startsWith('{')) {
    try {
      const obj = JSON.parse(trimmed);
      return obj && typeof obj === 'object' ? obj : {};
    } catch {
      return {};
    }
  }
  const map = {};
  for (const pair of trimmed.split(',')) {
    const i = pair.indexOf('=');
    if (i === -1) continue;
    const k = pair.slice(0, i).trim();
    const v = pair.slice(i + 1).trim();
    if (k) map[k] = v;
  }
  return map;
}

function loadModelMap() {
  if (process.env.MODEL_MAP_FILE) {
    try {
      return parseModelMap(fs.readFileSync(process.env.MODEL_MAP_FILE, 'utf8'));
    } catch (err) {
      console.error('[config] failed to read MODEL_MAP_FILE:', err.message);
    }
  }
  return parseModelMap(process.env.MODEL_MAP);
}

function boolEnv(name) {
  const v = (process.env[name] || '').toLowerCase();
  return v === '1' || v === 'on' || v === 'true' || v === 'yes';
}

function loadProviders() {
  if (process.env.PROVIDERS_FILE) {
    try {
      return parseProviders(fs.readFileSync(process.env.PROVIDERS_FILE, 'utf8'));
    } catch (err) {
      console.error('[config] failed to read PROVIDERS_FILE:', err.message);
    }
  }
  return parseProviders(process.env.PROVIDERS);
}

function loadFilters() {
  if (process.env.FILTERS_FILE) {
    try {
      return parseFilters(fs.readFileSync(process.env.FILTERS_FILE, 'utf8'));
    } catch (err) {
      console.error('[config] failed to read FILTERS_FILE:', err.message);
    }
  }
  return parseFilters(process.env.FILTERS);
}

// Tool-name map: built-in special cases overlaid with user JSON (TOOL_NAME_MAP
// inline or TOOL_NAME_MAP_FILE). Keys are case-sensitive; built-ins are lowercase.
function loadToolNameMap() {
  const map = { ...DEFAULT_TOOL_NAME_MAP };
  let raw = process.env.TOOL_NAME_MAP;
  if (process.env.TOOL_NAME_MAP_FILE) {
    try {
      raw = fs.readFileSync(process.env.TOOL_NAME_MAP_FILE, 'utf8');
    } catch (err) {
      console.error('[config] failed to read TOOL_NAME_MAP_FILE:', err.message);
    }
  }
  if (raw && raw.trim().startsWith('{')) {
    try {
      Object.assign(map, JSON.parse(raw));
    } catch {
      /* ignore malformed map */
    }
  }
  return map;
}

function parseStatuses(raw, fallback) {
  if (!raw || typeof raw !== 'string') return new Set(fallback);
  const out = new Set();
  for (const tok of raw.split(',')) {
    const n = Number.parseInt(tok.trim(), 10);
    if (Number.isFinite(n)) out.add(n);
  }
  return out.size ? out : new Set(fallback);
}

export function loadConfig(overrides = {}) {
  return {
    proxyPort: intEnv('PROXY_PORT', 8788),
    uiPort: intEnv('UI_PORT', 8789),
    bindAddr: process.env.BIND_ADDR || '127.0.0.1',
    logDir: process.env.LOG_DIR || path.resolve(__dirname, '..', 'logs'),
    redactAuth: process.env.REDACT_AUTH !== '0',
    maxBodyBytes: intEnv('MAX_BODY_BYTES', 2_000_000),
    upstream: {
      anthropic: process.env.ANTHROPIC_UPSTREAM || 'https://api.anthropic.com',
      openai: process.env.OPENAI_UPSTREAM || 'https://api.openai.com',
    },
    compat: {
      // When 'chat', /v1/messages is translated to /v1/chat/completions and sent
      // to the OpenAI upstream. null = transparent pass-through (default).
      anthropicTo: (process.env.ANTHROPIC_COMPAT || '').toLowerCase() === 'chat' ? 'chat' : null,
      modelMap: loadModelMap(),
    },
    providers: {
      // Grouped failover pools. Empty pools => fall back to single upstream.
      pools: loadProviders(),
    },
    breaker: {
      // Auto-enable the breaker when a provider pool exists; otherwise opt-in.
      enabled: boolEnv('BREAKER'),
      failureThreshold: intEnv('BREAKER_FAILURES', 5),
      cooldownMs: intEnv('BREAKER_COOLDOWN_MS', 30000),
      halfOpenMax: intEnv('BREAKER_HALFOPEN_MAX', 1),
      failoverStatuses: parseStatuses(process.env.FAILOVER_STATUSES, [429, 500, 502, 503, 504]),
    },
    rectifier: {
      enabled: boolEnv('RECTIFY') || boolEnv('RECTIFIER'),
      signature: process.env.RECTIFY_SIGNATURE !== '0',
      budget: process.env.RECTIFY_BUDGET !== '0',
    },
    // Tool-name normalization (opt-in). When enabled, lowercase tool names are
    // rewritten (default PascalCase + built-in map) on the request, and
    // optionally on the response, plus array/object inputs serialized as strings
    // are repaired. Affects Anthropic /v1/messages traffic only.
    transform: {
      toolName: {
        enabled: boolEnv('TOOL_NAME_CASE'),
        request: process.env.TOOL_NAME_REQUEST !== '0',
        response: process.env.TOOL_NAME_RESPONSE !== '0',
        repairInput: process.env.TOOL_NAME_REPAIR_INPUT !== '0',
        map: loadToolNameMap(),
      },
    },
    // Request filters/rules (opt-in): mutate outbound headers/body before send.
    filters: loadFilters(),
    // Outbound proxy (opt-in): route upstream connections via an HTTP/SOCKS5
    // proxy, OR — for advanced share links (vmess/vless/trojan/ss/hysteria2/
    // tuic) or a native PROXY_KERNEL_CONFIG — via a local xray/sing-box kernel.
    // null when nothing is configured.
    outbound: createOutbound(
      process.env.UPSTREAM_PROXY || process.env.HTTPS_PROXY || process.env.https_proxy
      || process.env.HTTP_PROXY || process.env.http_proxy,
      {
        kernel: process.env.PROXY_KERNEL || 'auto',
        configPath: process.env.PROXY_KERNEL_CONFIG || '',
        xrayBin: process.env.XRAY_BIN || '',
        singboxBin: process.env.SING_BOX_BIN || '',
        socksPort: intEnv('PROXY_KERNEL_SOCKS_PORT', 0),
      },
    ),
    ...overrides,
  };
}
