"""Lightweight web UI + JSON API for browsing captured exchanges.

    GET    /                   -> static index.html
    GET    /api/exchanges      -> recent exchange summaries
    GET    /api/exchanges/:id  -> full exchange detail
    DELETE /api/exchanges      -> clear the in-memory list (one-click "清空")
"""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs, unquote

from .filters import summarize_filters

PUBLIC_DIR = Path(__file__).resolve().parent.parent / "public"


def _config_summary(config):
    """Read-only snapshot of the active opt-in features, for the UI to display.
    Never includes secrets (provider apiKeys / credentials are omitted)."""
    tn = (config.get("transform") or {}).get("toolName") or {}
    pools = (config.get("providers") or {}).get("pools") or {}
    breaker = config.get("breaker") or {}
    rectifier = config.get("rectifier") or {}
    outbound = config.get("outbound")

    def _pool(group):
        return [{"id": p.get("id"), "baseUrl": p.get("baseUrl"), "hasKey": bool(p.get("apiKey"))}
                for p in (pools.get(group) or [])]

    return {
        "compat": {"anthropicTo": (config.get("compat") or {}).get("anthropicTo")},
        "providers": {"anthropic": _pool("anthropic"), "openai": _pool("openai")},
        "breaker": {
            "enabled": bool(breaker.get("enabled")),
            "failureThreshold": breaker.get("failureThreshold"),
            "cooldownMs": breaker.get("cooldownMs"),
        },
        "rectifier": {
            "enabled": bool(rectifier.get("enabled")),
            "signature": bool(rectifier.get("signature")),
            "budget": bool(rectifier.get("budget")),
        },
        "toolName": {
            "enabled": bool(tn.get("enabled")),
            "request": bool(tn.get("request")),
            "response": bool(tn.get("response")),
            "repairInput": bool(tn.get("repairInput")),
            "mapSize": len(tn.get("map") or {}),
        },
        "filters": summarize_filters(config.get("filters")),
        "outbound": {"enabled": True, "describe": outbound["describe"]} if outbound else {"enabled": False},
    }


def _make_handler(config, recorder):
    class UiHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args):
            pass

        def _send_json(self, status, obj):
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_DELETE(self):
            # DELETE /api/exchanges empties the in-memory list (UI 的「清空」按钮)。
            # 磁盘上的 JSONL 日志保留。
            parts = urlsplit(self.path)
            if parts.path == "/api/exchanges":
                cleared = recorder.clear()
                return self._send_json(200, {"cleared": cleared})
            return self._send_json(404, {"error": "not found"})

        def do_GET(self):
            parts = urlsplit(self.path)
            p = parts.path

            if p == "/api/config":
                return self._send_json(200, _config_summary(config))
            if p == "/api/exchanges":
                qs = parse_qs(parts.query)
                limit = int((qs.get("limit") or ["100"])[0])
                return self._send_json(200, recorder.list(limit))
            if p.startswith("/api/exchanges/"):
                ex_id = unquote(p[len("/api/exchanges/"):])
                ex = recorder.get(ex_id)
                return self._send_json(200, ex) if ex else self._send_json(404, {"error": "not found"})

            # Static files (index.html only by default).
            rel = "index.html" if p == "/" else p.lstrip("/")
            full = (PUBLIC_DIR / rel).resolve()
            if not str(full).startswith(str(PUBLIC_DIR)):
                self.send_response(403)
                self.end_headers()
                self.wfile.write(b"forbidden")
                return
            try:
                data = full.read_bytes()
            except OSError:
                self.send_response(404)
                self.end_headers()
                self.wfile.write(b"not found")
                return
            ext = full.suffix
            ctype = "text/html" if ext == ".html" else "text/javascript" if ext == ".js" else "text/plain"
            self.send_response(200)
            self.send_header("Content-Type", f"{ctype}; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return UiHandler


def start_ui(config, recorder):
    handler = _make_handler(config, recorder)
    bind_addr = config.get("bindAddr") or "127.0.0.1"
    server = ThreadingHTTPServer((bind_addr, config["uiPort"]), handler)
    server.daemon_threads = True
    actual_port = server.server_address[1]
    config["uiPort"] = actual_port
    print(f"[ui]    open http://{bind_addr}:{actual_port}")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server
