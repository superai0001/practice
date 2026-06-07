"""Configuration loading from environment variables / overrides.

Env vars (all optional):
    PROXY_PORT          proxy listen port             (default 8788)
    UI_PORT             web UI listen port            (default 8789)
    BIND_ADDR           listen address for proxy+UI   (default 127.0.0.1; use
                        0.0.0.0 to expose, e.g. inside Docker)
    LOG_DIR             directory for JSONL logs      (default <module>/logs)
    REDACT_AUTH         "0" to keep raw auth headers  (default redact)
    ANTHROPIC_UPSTREAM  override Anthropic upstream    (default https://api.anthropic.com)
    OPENAI_UPSTREAM     override OpenAI upstream       (default https://api.openai.com)
    MAX_BODY_BYTES      max stored body size, larger is truncated (default 2_000_000)
    ANTHROPIC_COMPAT    "chat" to translate incoming /v1/messages into OpenAI
                        /v1/chat/completions (for vendors that only support chat).
                        Default off (transparent pass-through).
    MODEL_MAP           model name remap used in compat mode. JSON object
                        (e.g. {"claude-sonnet-4-6":"gpt-4o"}) OR comma list
                        (e.g. "claude-sonnet-4-6=gpt-4o,claude-haiku-4-5=gpt-4o-mini").
    MODEL_MAP_FILE      path to a JSON file with the same mapping (alternative to MODEL_MAP).

    --- resilience (all opt-in; default behavior is unchanged when unset) ---
    PROVIDERS           JSON array of failover providers, each
                        {id, group:"anthropic"|"openai", baseUrl, apiKey?}.
    PROVIDERS_FILE      path to a JSON file with the same array.
    BREAKER             "1"/"on" to enable the circuit breaker (auto-on when a
                        provider pool is configured).
    BREAKER_FAILURES    failures before a provider opens          (default 5)
    BREAKER_COOLDOWN_MS open-state cooldown in ms                 (default 30000)
    BREAKER_HALFOPEN_MAX concurrent half-open probes              (default 1)
    FAILOVER_STATUSES   comma list of HTTP statuses that trigger failover
                        (default 429,500,502,503,504)
    RECTIFY             "1"/"on" to enable Anthropic thinking rectification.
    RECTIFY_SIGNATURE / RECTIFY_BUDGET  "0" to disable one sub-rule.
"""

import json
import os
from pathlib import Path

from .providers import parse_providers
from .filters import parse_filters
from .transform import DEFAULT_TOOL_NAME_MAP
from .outbound import create_outbound

_MODULE_ROOT = Path(__file__).resolve().parent.parent


def _int_env(name, fallback):
    v = os.environ.get(name)
    if v is None or v == "":
        return fallback
    try:
        return int(v)
    except ValueError:
        return fallback


def parse_model_map(raw):
    """Parse the model map from a string that is either JSON or a comma list of
    ``from=to`` pairs. Returns a dict (empty when nothing is configured)."""
    if not isinstance(raw, str):
        return {}
    trimmed = raw.strip()
    if trimmed == "":
        return {}
    if trimmed.startswith("{"):
        try:
            obj = json.loads(trimmed)
            return obj if isinstance(obj, dict) else {}
        except (ValueError, TypeError):
            return {}
    out = {}
    for pair in trimmed.split(","):
        if "=" not in pair:
            continue
        k, v = pair.split("=", 1)
        k = k.strip()
        if k:
            out[k] = v.strip()
    return out


def _load_model_map():
    path = os.environ.get("MODEL_MAP_FILE")
    if path:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                return parse_model_map(fh.read())
        except OSError as err:
            print(f"[config] failed to read MODEL_MAP_FILE: {err}")
    return parse_model_map(os.environ.get("MODEL_MAP"))


def _bool_env(name):
    return (os.environ.get(name) or "").lower() in ("1", "on", "true", "yes")


def _load_providers():
    path = os.environ.get("PROVIDERS_FILE")
    if path:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                return parse_providers(fh.read())
        except OSError as err:
            print(f"[config] failed to read PROVIDERS_FILE: {err}")
    return parse_providers(os.environ.get("PROVIDERS"))


def _load_filters():
    path = os.environ.get("FILTERS_FILE")
    if path:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                return parse_filters(fh.read())
        except OSError as err:
            print(f"[config] failed to read FILTERS_FILE: {err}")
    return parse_filters(os.environ.get("FILTERS"))


def _load_tool_name_map():
    """Built-in special cases overlaid with user JSON (TOOL_NAME_MAP inline or
    TOOL_NAME_MAP_FILE). Keys are case-sensitive; built-ins are lowercase."""
    name_map = dict(DEFAULT_TOOL_NAME_MAP)
    raw = os.environ.get("TOOL_NAME_MAP")
    path = os.environ.get("TOOL_NAME_MAP_FILE")
    if path:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                raw = fh.read()
        except OSError as err:
            print(f"[config] failed to read TOOL_NAME_MAP_FILE: {err}")
    if isinstance(raw, str) and raw.strip().startswith("{"):
        try:
            obj = json.loads(raw)
            if isinstance(obj, dict):
                name_map.update(obj)
        except (ValueError, TypeError):
            pass
    return name_map


def _parse_statuses(raw, fallback):
    if not isinstance(raw, str) or not raw:
        return set(fallback)
    out = set()
    for tok in raw.split(","):
        tok = tok.strip()
        try:
            out.add(int(tok))
        except ValueError:
            continue
    return out or set(fallback)


def load_config(**overrides):
    config = {
        "proxyPort": _int_env("PROXY_PORT", 8788),
        "uiPort": _int_env("UI_PORT", 8789),
        "bindAddr": os.environ.get("BIND_ADDR") or "127.0.0.1",
        "logDir": os.environ.get("LOG_DIR") or str(_MODULE_ROOT / "logs"),
        "redactAuth": os.environ.get("REDACT_AUTH") != "0",
        "maxBodyBytes": _int_env("MAX_BODY_BYTES", 2_000_000),
        "upstream": {
            "anthropic": os.environ.get("ANTHROPIC_UPSTREAM") or "https://api.anthropic.com",
            "openai": os.environ.get("OPENAI_UPSTREAM") or "https://api.openai.com",
        },
        "compat": {
            # When 'chat', /v1/messages is translated to /v1/chat/completions and
            # sent to the OpenAI upstream. None = transparent pass-through (default).
            "anthropicTo": "chat" if (os.environ.get("ANTHROPIC_COMPAT") or "").lower() == "chat" else None,
            "modelMap": _load_model_map(),
        },
        "providers": {
            # Grouped failover pools. Empty pools => fall back to single upstream.
            "pools": _load_providers(),
        },
        "breaker": {
            # Auto-enable the breaker when a provider pool exists; otherwise opt-in.
            "enabled": _bool_env("BREAKER"),
            "failureThreshold": _int_env("BREAKER_FAILURES", 5),
            "cooldownMs": _int_env("BREAKER_COOLDOWN_MS", 30000),
            "halfOpenMax": _int_env("BREAKER_HALFOPEN_MAX", 1),
            "failoverStatuses": _parse_statuses(os.environ.get("FAILOVER_STATUSES"), [429, 500, 502, 503, 504]),
        },
        "rectifier": {
            "enabled": _bool_env("RECTIFY") or _bool_env("RECTIFIER"),
            "signature": os.environ.get("RECTIFY_SIGNATURE") != "0",
            "budget": os.environ.get("RECTIFY_BUDGET") != "0",
        },
        # Tool-name normalization (opt-in). When enabled, lowercase tool names
        # are rewritten (default PascalCase + built-in map) on the request, and
        # optionally on the response, plus array/object inputs serialized as
        # strings are repaired. Affects Anthropic /v1/messages traffic only.
        "transform": {
            "toolName": {
                "enabled": _bool_env("TOOL_NAME_CASE"),
                "request": os.environ.get("TOOL_NAME_REQUEST") != "0",
                "response": os.environ.get("TOOL_NAME_RESPONSE") != "0",
                "repairInput": os.environ.get("TOOL_NAME_REPAIR_INPUT") != "0",
                "map": _load_tool_name_map(),
            },
        },
        # Request filters/rules (opt-in): mutate outbound headers/body pre-send.
        "filters": _load_filters(),
        # Outbound proxy (opt-in): route upstream connections via an HTTP/SOCKS5
        # proxy. None when UPSTREAM_PROXY (or HTTPS_PROXY/HTTP_PROXY) is unset.
        "outbound": create_outbound(
            os.environ.get("UPSTREAM_PROXY") or os.environ.get("HTTPS_PROXY")
            or os.environ.get("https_proxy") or os.environ.get("HTTP_PROXY")
            or os.environ.get("http_proxy"),
            {
                "kernel": os.environ.get("PROXY_KERNEL") or "auto",
                "configPath": os.environ.get("PROXY_KERNEL_CONFIG") or "",
                "xrayBin": os.environ.get("XRAY_BIN") or "",
                "singboxBin": os.environ.get("SING_BOX_BIN") or "",
                "socksPort": _int_env("PROXY_KERNEL_SOCKS_PORT", 0),
            },
        ),
    }
    config.update(overrides)
    return config
