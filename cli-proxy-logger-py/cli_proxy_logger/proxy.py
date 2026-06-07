"""Core reverse proxy.

Flow:
    1. Buffer the incoming request body (CLI request bodies are complete JSON).
    2. resolve_upstream() picks the real upstream + wire format from the path.
    3. Forward method/path/headers/body to the upstream over http(s).
    4. Stream the upstream response back to the client byte-for-byte (fidelity
       first -- the CLI must be unaffected), while teeing a decoded COPY into the
       matching parser to reconstruct text + tool calls.
    5. Record the normalized Exchange.
"""

import http.client
import json
import threading
import uuid
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from .breaker import BreakerRegistry
from .filters import apply_filters
from .model import safe_json_parse
from .parsers import PARSERS, openai_chat
from .providers import resolve_candidates, wire_to_group
from .rectifier import detect_rectification, apply_rectification
from .sse import SSEParser
from .transform import (
    normalize_request_tool_names,
    rewrite_response_tool_names,
    repair_tool_use_input,
    rewrite_stream_event_tool_name,
)
from .translate import (
    anthropic_request_to_chat,
    chat_response_to_anthropic,
    chat_error_to_anthropic,
    anthropic_message_to_sse,
    anthropic_error_sse,
    ChatToAnthropicStream,
)
from .upstream import resolve_upstream

# "Hop-by-hop" headers are meaningful only for a single transport connection
# (per RFC 7230 6.1) and must NOT be blindly relayed by a proxy. We also drop
# host/content-length here because we always recompute them for the new
# connection, and transfer-encoding because we re-frame the body ourselves.
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
}


def _redact_headers(headers, redact):
    """Mask credentials before they are written to disk. The *real* key is still
    forwarded to the upstream untouched -- only the logged copy is redacted, so
    your JSONL files never contain a usable API key.
    """
    out = {}
    for k, v in headers:
        lk = k.lower()
        if redact and lk in ("authorization", "x-api-key", "api-key"):
            s = str(v)
            # Keep a short prefix/suffix so you can tell two keys apart in logs
            # without exposing the secret (e.g. "Bearer...7912").
            out[k] = "***" if len(s) <= 12 else f"{s[:6]}...{s[-4:]}"
        else:
            out[k] = v
    return out


def _make_stream_decoder(content_encoding):
    """Return a callable ``feed(bytes) -> bytes`` that incrementally decodes a
    (possibly compressed) byte stream. Returns identity for unknown encodings so
    a decode failure can never break forwarding or logging.
    """
    enc = (content_encoding or "").lower()
    if enc == "gzip":
        obj = zlib.decompressobj(16 + zlib.MAX_WBITS)
    elif enc == "deflate":
        obj = zlib.decompressobj()
    elif enc == "br":
        try:
            import brotli  # optional; not in stdlib
            obj = brotli.Decompressor()

            def feed_br(chunk):
                try:
                    return obj.process(chunk)
                except Exception:
                    return b""
            return feed_br
        except Exception:
            return lambda chunk: b""  # cannot decode br -> skip parsing copy
    else:
        return lambda chunk: chunk

    def feed(chunk):
        try:
            return obj.decompress(chunk)
        except Exception:
            return b""
    return feed


def _truncate(buf, max_bytes):
    if len(buf) <= max_bytes:
        return buf.decode("utf-8", "replace")
    return buf[:max_bytes].decode("utf-8", "replace") + f"\n...[truncated {len(buf) - max_bytes} bytes]"


def _is_resilient(config, wire):
    """True when any resilience feature (provider pool / breaker / rectifier) is
    active for this request. When False we take the original transparent path so
    default behavior is unchanged."""
    group = wire_to_group(wire)
    pool = ((config.get("providers") or {}).get("pools") or {}).get(group)
    if isinstance(pool, list) and len(pool) > 0:
        return True
    if (config.get("breaker") or {}).get("enabled"):
        return True
    if (config.get("rectifier") or {}).get("enabled") and wire == "anthropic":
        return True
    return False


def _split_upstream(base_url):
    split = urlsplit(base_url)
    scheme = split.scheme or "https"
    return scheme, split.hostname, split.port or (443 if scheme == "https" else 80), split.netloc


def _open_connection(config, scheme, host, port):
    """Open an upstream connection, tunneling via the outbound proxy when one is
    configured (opt-in). Default path returns a plain stdlib connection."""
    outbound = config.get("outbound")
    if outbound:
        return outbound["connection"](scheme, host, port, 600)
    if scheme == "https":
        return http.client.HTTPSConnection(host, port, timeout=600)
    return http.client.HTTPConnection(host, port, timeout=600)


def _apply_outbound_mutation(config, wire, provider_id, out_headers, body_bytes, exchange):
    """Mutate ``out_headers`` in place (header filters) and return the possibly-new
    body bytes (when body filters / tool-name rewriting changed it). Records what
    was applied onto ``exchange`` for UI/log visibility. Default path is
    untouched: returns the original bytes when nothing is configured."""
    filters = config.get("filters")
    tn = (config.get("transform") or {}).get("toolName") or {}
    tool_name_req = bool(tn.get("enabled") and tn.get("request") and wire == "anthropic")
    has_filters = isinstance(filters, list) and len(filters) > 0
    if not has_filters and not tool_name_req:
        return body_bytes

    need_body = tool_name_req or (has_filters and any(f["domain"] == "body" for f in filters))
    body_obj = None
    if need_body and body_bytes:
        body_obj = safe_json_parse(body_bytes.decode("utf-8", "replace"))

    changed = False
    meta = {}
    if has_filters:
        res = apply_filters(filters, {"providerId": provider_id, "headers": out_headers, "body": body_obj})
        if res["applied"]:
            meta["filters"] = res["applied"]
        if res["bodyChanged"]:
            changed = True
    if tool_name_req and isinstance(body_obj, dict):
        n = normalize_request_tool_names(body_obj, tn.get("map"))
        if n > 0:
            meta["toolNamesRewritten"] = n
            changed = True
    if exchange is not None and meta:
        exchange.setdefault("mutation", {}).update(meta)
    if changed and isinstance(body_obj, dict):
        return json.dumps(body_obj).encode("utf-8")
    return body_bytes


def _response_rewrite_active(config, wire):
    """Should we rewrite the Anthropic RESPONSE (tool names / input repair)?"""
    tn = (config.get("transform") or {}).get("toolName") or {}
    return bool(tn.get("enabled") and wire == "anthropic" and (tn.get("response") or tn.get("repairInput")))


def _rewrite_sse_line(line, tn):
    """Transform a single SSE text line: only ``data:`` lines carrying a tool_use
    content_block_start are rewritten; everything else passes through verbatim."""
    if not tn.get("response") or not line.startswith("data:"):
        return line
    json_str = line[5:].strip()
    if not json_str or json_str == "[DONE]":
        return line
    data = safe_json_parse(json_str)
    if not isinstance(data, dict):
        return line
    if rewrite_stream_event_tool_name(data, tn.get("map")):
        return "data: " + json.dumps(data, separators=(",", ":"))
    return line


def _make_handler(config, recorder):
    # One breaker registry per proxy instance, shared across all requests so a
    # provider's failure state persists between requests.
    breakers = BreakerRegistry(config.get("breaker") or {})

    class ProxyHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args):
            pass  # silence default stderr access log

        def _handle(self):
            started = datetime.now(timezone.utc)

            # --- Step 1: buffer the request body -----------------------------
            # CLI requests are a single complete JSON document, so reading the
            # whole Content-Length up front is simplest. (A general-purpose
            # proxy would stream this too, but here it keeps the code readable.)
            length = int(self.headers.get("content-length") or 0)
            req_body = self.rfile.read(length) if length else b""

            # --- Step 2: pick the real upstream + wire format ----------------
            # The path alone tells us who the client is: /v1/messages == Claude
            # Code (Anthropic), /v1/responses or /v1/chat/completions == Codex.
            lower_headers = {k.lower(): v for k, v in self.headers.items()}
            info = resolve_upstream(config, self.path, lower_headers)
            wire = info["wire"]

            # Compat mode: when ANTHROPIC_COMPAT=chat and the client speaks
            # Anthropic Messages, hand off to the TRANSLATING path (Anthropic ->
            # OpenAI Chat) instead of the transparent tee. This is the only case
            # where we rewrite both the request and the response.
            compat = config.get("compat") or {}
            if (compat.get("anthropicTo") == "chat" and wire == "anthropic"
                    and self.path.split("?")[0].startswith("/v1/messages")):
                self._handle_anthropic_to_chat(config, recorder, req_body, started)
                return

            # Resilient transparent path: provider failover + circuit breaker +
            # (Anthropic) thinking rectification. Only taken when opted in.
            if _is_resilient(config, wire):
                self._handle_resilient(config, recorder, req_body, started, wire)
                return

            parser = PARSERS[wire]

            split = urlsplit(info["baseUrl"])
            scheme = split.scheme or "https"
            host = split.hostname
            port = split.port or (443 if scheme == "https" else 80)
            netloc = split.netloc
            upstream_url = f"{scheme}://{netloc}{self.path}"

            # --- Step 3: build the upstream request headers ------------------
            # Copy the client's headers through verbatim (including the real
            # Authorization / x-api-key) so the upstream sees an identical
            # request -- this is why CLI-specific gating (e.g. cc.freemodel.dev
            # only answering real Claude Code headers) still works through us.
            out_headers = {}
            for k, v in self.headers.items():
                lk = k.lower()
                if lk in HOP_BY_HOP:
                    continue
                if lk == "accept-encoding":
                    # Normalize to encodings the standard library can decode, so
                    # the logged copy is always introspectable without extra
                    # dependencies (the upstream may otherwise pick brotli/zstd,
                    # which stdlib cannot decompress). The client still receives
                    # a valid, correctly-encoded response.
                    out_headers[k] = "gzip, deflate"
                    continue
                out_headers[k] = v
            out_headers["Host"] = netloc
            if req_body:
                out_headers["Content-Length"] = str(len(req_body))

            # Parse the request body now (it is plain JSON) into the normalized
            # shape. Wrapped in try/except: a parse bug must never stop us from
            # forwarding the request to the upstream.
            try:
                parsed_request = parser.parse_request(req_body.decode("utf-8", "replace"))
            except Exception:
                parsed_request = {"wire": wire, "model": None, "messages": [],
                                  "tools": [], "stream": False, "raw": None}

            exchange = {
                "id": str(uuid.uuid4()),
                "ts": started.isoformat().replace("+00:00", "Z"),
                "durationMs": 0,
                "wire": wire,
                "method": self.command,
                "url": upstream_url,
                "reqHeaders": _redact_headers(self.headers.items(), config["redactAuth"]),
                "requestBodyRaw": _truncate(req_body, config["maxBodyBytes"]),
                "request": parsed_request,
                "resStatus": 0,
                "resHeaders": {},
                "response": None,
                "error": None,
            }

            # Opt-in: apply request filters + tool-name normalization to the
            # OUTBOUND request (headers/body). No-op when nothing is configured.
            send_body = _apply_outbound_mutation(config, wire, None, out_headers, req_body, exchange)
            if send_body is not req_body:
                out_headers["Content-Length"] = str(len(send_body))

            # --- Step 4: open the upstream connection and send the request ---
            # http.client is the stdlib's low-level HTTP/1.1 client. Unlike
            # urllib it lets us stream the response with .read(n), which is what
            # we need to relay an SSE stream chunk-by-chunk. When an outbound
            # proxy is configured the connection is tunneled through it.
            conn = _open_connection(config, scheme, host, port)

            try:
                conn.request(self.command, self.path, body=send_body or None, headers=out_headers)
                upstream_res = conn.getresponse()
            except Exception as err:
                exchange["error"] = f"upstream request error: {err}"
                exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                recorder.record(exchange)
                self.send_response(502)
                self.send_header("Content-Type", "application/json")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b'{"error":{"type":"proxy_error","message":"upstream request failed"}}')
                self.close_connection = True
                conn.close()
                return

            exchange["resStatus"] = upstream_res.status
            res_header_pairs = upstream_res.getheaders()
            exchange["resHeaders"] = {k: v for k, v in res_header_pairs}

            content_type = upstream_res.getheader("content-type") or ""
            is_sse = "text/event-stream" in content_type
            content_encoding = upstream_res.getheader("content-encoding")

            # Opt-in: rewrite the Anthropic response (tool names / input repair)
            # instead of the verbatim tee. Only when explicitly enabled.
            if _response_rewrite_active(config, wire):
                self._forward_rewritten(upstream_res, exchange, started, config)
                conn.close()
                return

            # --- Step 5: relay status + headers back to the client -----------
            # We forward the upstream's status line and headers, minus the
            # framing headers (transfer-encoding/content-length). Because we set
            # "Connection: close", the response body is delimited by EOF -- a
            # valid HTTP/1.1 framing that avoids re-implementing chunked
            # encoding and never desyncs the client's parser.
            self.send_response_only(upstream_res.status, upstream_res.reason)
            for k, v in res_header_pairs:
                if k.lower() in HOP_BY_HOP:
                    continue
                self.send_header(k, v)
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

            # --- Step 6: stream the body two ways at once --------------------
            # For an SSE response we run an incremental SSE parser whose events
            # feed a per-wire "aggregator" that rebuilds text + tool calls. For
            # a plain JSON response we just buffer the decoded bytes and parse
            # once at the end.
            sse = SSEParser() if is_sse else None
            agg = parser.create_stream_aggregator() if is_sse else None
            if sse:
                sse.on("event", lambda e: agg["feed"](e))

            # The bytes on the wire may be gzip/deflate-compressed; this decoder
            # incrementally decompresses the COPY we parse. The bytes forwarded
            # to the client are never touched.
            decode = _make_stream_decoder(content_encoding)
            raw_copy = bytearray()

            def on_decoded(buf):
                if not buf:
                    return
                if sse:
                    sse.push(buf.decode("utf-8", "replace"))
                elif len(raw_copy) < config["maxBodyBytes"]:
                    raw_copy.extend(buf)

            # The core dual-path loop. Read a chunk from the upstream, write the
            # ORIGINAL bytes to the client first (fidelity: the CLI must be
            # unaffected even if our parser later throws), then tee a decoded
            # copy into the parser. If the client hangs up we keep draining the
            # upstream so the exchange is still fully logged.
            client_alive = True
            while True:
                chunk = upstream_res.read(65536)
                if not chunk:
                    break
                if client_alive:
                    try:
                        self.wfile.write(chunk)  # forward raw bytes FIRST
                        self.wfile.flush()       # flush so SSE arrives live
                    except (BrokenPipeError, ConnectionError, OSError):
                        client_alive = False
                on_decoded(decode(chunk))  # tee a decoded COPY into the parser

            # --- Step 7: finalize the normalized response --------------------
            try:
                if sse:
                    sse.flush()  # emit any trailing event still in the buffer
                    exchange["response"] = agg["result"]()
                else:
                    exchange["response"] = parser.parse_response(bytes(raw_copy).decode("utf-8", "replace"))
            except Exception as err:
                exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                        "usage": None, "raw": None, "parseError": str(err)}

            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
            recorder.record(exchange)
            conn.close()

        # =================================================================
        # Compat path: Anthropic /v1/messages -> OpenAI /v1/chat/completions
        #
        # Unlike _handle (transparent tee), this REWRITES both directions:
        #   request  : translate.anthropic_request_to_chat() -> POST /v1/chat/completions
        #   response : OpenAI Chat (SSE or JSON) -> Anthropic events to the client
        # The client (Claude Code) never knows the vendor speaks a different
        # protocol.
        # =================================================================
        def _handle_anthropic_to_chat(self, config, recorder, req_body, started):
            anth_body = safe_json_parse(req_body.decode("utf-8", "replace")) or {}
            want_stream = bool(anth_body.get("stream"))
            original_model = anth_body.get("model")
            model_map = (config.get("compat") or {}).get("modelMap") or {}

            # 1) Translate the request body and pick the mapped model.
            chat_body = anthropic_request_to_chat(anth_body, model_map)
            mapped_model = chat_body.get("model")
            chat_body_bytes = json.dumps(chat_body).encode("utf-8")

            # 2) Build the upstream request to the OpenAI-style vendor.
            split = urlsplit(config["upstream"]["openai"])
            scheme = split.scheme or "https"
            host = split.hostname
            port = split.port or (443 if scheme == "https" else 80)
            netloc = split.netloc
            path = "/v1/chat/completions"
            upstream_url = f"{scheme}://{netloc}{path}"

            # Copy client headers minus hop-by-hop, then fix up auth + content for
            # an OpenAI vendor: Claude Code authenticates with `x-api-key`, but
            # OpenAI-style vendors expect `Authorization: Bearer`. Translate that,
            # and drop Anthropic-only headers the vendor would not understand.
            drop = {"x-api-key", "anthropic-version", "anthropic-beta",
                    "anthropic-dangerous-direct-browser-access", "content-type"}
            out_headers = {}
            for k, v in self.headers.items():
                lk = k.lower()
                if lk in HOP_BY_HOP or lk in drop:
                    continue
                out_headers[k] = v
            auth = self.headers.get("authorization")
            api_key = self.headers.get("x-api-key")
            if auth:
                out_headers["Authorization"] = auth
            elif api_key:
                out_headers["Authorization"] = f"Bearer {api_key}"
            out_headers["Host"] = netloc
            out_headers["Content-Type"] = "application/json"
            out_headers["Content-Length"] = str(len(chat_body_bytes))

            # 3) Record the exchange as an Anthropic request (what the client
            # sent) with a translation marker; response is normalized from chat.
            try:
                parsed_request = PARSERS["anthropic"].parse_request(req_body.decode("utf-8", "replace"))
            except Exception:
                parsed_request = {"wire": "anthropic", "model": original_model, "messages": [],
                                  "tools": [], "stream": want_stream, "raw": None}
            exchange = {
                "id": str(uuid.uuid4()),
                "ts": started.isoformat().replace("+00:00", "Z"),
                "durationMs": 0,
                "wire": "anthropic",
                "method": self.command,
                "url": upstream_url,
                "reqHeaders": _redact_headers(self.headers.items(), config["redactAuth"]),
                "requestBodyRaw": _truncate(req_body, config["maxBodyBytes"]),
                "request": parsed_request,
                "translation": {"from": "anthropic", "to": "chat",
                                "model": original_model, "upstreamModel": mapped_model},
                "resStatus": 0,
                "resHeaders": {},
                "response": None,
                "error": None,
            }

            # 4) Open the upstream connection and send the translated request.
            #    Outbound proxy (opt-in) tunnels this connection when configured.
            conn = _open_connection(config, scheme, host, port)
            try:
                conn.request("POST", path, body=chat_body_bytes, headers=out_headers)
                upstream_res = conn.getresponse()
            except Exception as err:
                exchange["error"] = f"upstream request error: {err}"
                exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                recorder.record(exchange)
                body = json.dumps(chat_error_to_anthropic(
                    {"error": {"type": "proxy_error", "message": "upstream request failed"}})).encode("utf-8")
                self.send_response(502)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                self.close_connection = True
                conn.close()
                return

            status = upstream_res.status
            exchange["resStatus"] = status
            exchange["resHeaders"] = {k: v for k, v in upstream_res.getheaders()}
            content_type = upstream_res.getheader("content-type") or ""
            is_sse = "text/event-stream" in content_type
            decode = _make_stream_decoder(upstream_res.getheader("content-encoding"))

            if is_sse:
                # ---- streaming translation ----
                self.send_response_only(status, upstream_res.reason)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True

                translator = ChatToAnthropicStream(original_model or mapped_model)
                chat_agg = openai_chat.create_stream_aggregator()  # for logging
                sse = SSEParser()

                def on_event(evt):
                    raw = (evt.get("data") or "").strip()
                    if raw == "" or raw == "[DONE]":
                        return
                    data = safe_json_parse(raw)
                    if not data:
                        return
                    chat_agg["feed"](evt)  # normalized response for the log
                    for frame in translator.feed(data):
                        try:
                            self.wfile.write(frame.encode("utf-8"))
                            self.wfile.flush()
                        except (BrokenPipeError, ConnectionError, OSError):
                            pass

                sse.on("event", on_event)
                while True:
                    chunk = upstream_res.read(65536)
                    if not chunk:
                        break
                    decoded = decode(chunk)
                    if decoded:
                        sse.push(decoded.decode("utf-8", "replace"))
                sse.flush()
                for frame in translator.end():
                    try:
                        self.wfile.write(frame.encode("utf-8"))
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionError, OSError):
                        pass
                try:
                    exchange["response"] = chat_agg["result"]()
                except Exception as err:
                    exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                            "usage": None, "raw": None, "parseError": str(err)}
            else:
                # ---- buffered (non-SSE) translation ----
                # Covers a plain JSON chat completion and error bodies. We may
                # still owe the client an SSE stream (if it asked for one), in
                # which case we synthesize the Anthropic event sequence.
                raw_copy = bytearray()
                while True:
                    chunk = upstream_res.read(65536)
                    if not chunk:
                        break
                    decoded = decode(chunk)
                    if decoded:
                        raw_copy.extend(decoded)
                body_text = bytes(raw_copy).decode("utf-8", "replace")
                obj = safe_json_parse(body_text)
                is_error = status >= 400 or (isinstance(obj, dict) and obj.get("error"))

                if is_error:
                    anth_err = chat_error_to_anthropic(obj if obj is not None else body_text)
                    if want_stream:
                        frame = anthropic_error_sse(obj if obj is not None else body_text).encode("utf-8")
                        self.send_response_only(status)
                        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                        self.send_header("Connection", "close")
                        self.end_headers()
                        self.wfile.write(frame)
                    else:
                        out = json.dumps(anth_err).encode("utf-8")
                        self.send_response_only(status)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Content-Length", str(len(out)))
                        self.send_header("Connection", "close")
                        self.end_headers()
                        self.wfile.write(out)
                    exchange["error"] = anth_err["error"]["message"]
                else:
                    anth_obj = chat_response_to_anthropic(obj or {}, original_model or mapped_model)
                    if want_stream:
                        self.send_response_only(status)
                        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                        self.send_header("Cache-Control", "no-cache")
                        self.send_header("Connection", "close")
                        self.end_headers()
                        for frame in anthropic_message_to_sse(anth_obj):
                            self.wfile.write(frame.encode("utf-8"))
                    else:
                        out = json.dumps(anth_obj).encode("utf-8")
                        self.send_response_only(status)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Content-Length", str(len(out)))
                        self.send_header("Connection", "close")
                        self.end_headers()
                        self.wfile.write(out)
                self.close_connection = True
                try:
                    exchange["response"] = openai_chat.parse_response(body_text)
                except Exception as err:
                    exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                            "usage": None, "raw": None, "parseError": str(err)}

            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
            recorder.record(exchange)
            conn.close()

        # =================================================================
        # Resilient transparent path: provider failover + circuit breaker +
        # (Anthropic) thinking rectification. Reached only when _is_resilient()
        # is True; the default transparent path (_handle) is left untouched so
        # non-resilient traffic is byte-for-byte identical to before.
        #
        # Per client request we walk the ordered provider candidates. For each:
        #   - skip it if its breaker is OPEN (cooldown not elapsed);
        #   - send the request; on a 2xx we COMMIT (stream bytes to the client)
        #     and stop;
        #   - on a connection error or "failover status" (default 429/5xx) we
        #     record the failure and try the NEXT provider;
        #   - on a rectifiable Anthropic error we rewrite the body and retry the
        #     SAME provider ONCE (a client-compat fix, NOT a breaker failure);
        #   - any other error (401/403/400/...) is committed to the client as-is.
        # Failover is only possible BEFORE we stream a 2xx to the client.
        # =================================================================

        def _forward_rewritten(self, upstream_res, exchange, started, config):
            """Rewrite-and-forward an Anthropic upstream response (tool names /
            input repair) instead of the verbatim tee, then record. Handles both
            SSE and plain JSON. Because we change the body, we drop
            content-encoding/length and send identity."""
            tn = (config.get("transform") or {}).get("toolName") or {}
            parser = PARSERS[exchange["wire"]]
            status = upstream_res.status
            res_header_pairs = upstream_res.getheaders()
            exchange["resStatus"] = status
            exchange["resHeaders"] = {k: v for k, v in res_header_pairs}
            content_type = upstream_res.getheader("content-type") or ""
            is_sse = "text/event-stream" in content_type
            decode = _make_stream_decoder(upstream_res.getheader("content-encoding"))

            base_out = [(k, v) for k, v in res_header_pairs
                        if k.lower() not in ("content-encoding", "content-length", "transfer-encoding")]

            if is_sse:
                self.send_response_only(status, upstream_res.reason)
                for k, v in base_out:
                    self.send_header(k, v)
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True

                sse = SSEParser()
                agg = parser.create_stream_aggregator()
                sse.on("event", lambda e: agg["feed"](e))
                line_buf = ""
                client_alive = True

                def flush_lines(final):
                    nonlocal line_buf, client_alive
                    parts = line_buf.split("\n")
                    line_buf = "" if final else parts.pop()
                    for line in parts:
                        if client_alive:
                            try:
                                self.wfile.write((_rewrite_sse_line(line, tn) + "\n").encode("utf-8"))
                                self.wfile.flush()
                            except (BrokenPipeError, ConnectionError, OSError):
                                client_alive = False
                    if final and line_buf and client_alive:
                        try:
                            self.wfile.write((_rewrite_sse_line(line_buf, tn) + "\n").encode("utf-8"))
                        except (BrokenPipeError, ConnectionError, OSError):
                            client_alive = False

                while True:
                    chunk = upstream_res.read(65536)
                    if not chunk:
                        break
                    text = decode(chunk).decode("utf-8", "replace")
                    sse.push(text)  # logging copy
                    line_buf += text
                    flush_lines(False)
                flush_lines(True)
                try:
                    sse.flush()
                    exchange["response"] = agg["result"]()
                except Exception as err:
                    exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                            "usage": None, "raw": None, "parseError": str(err)}
                exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                recorder.record(exchange)
                return

            # Non-SSE: buffer decoded body, rewrite, send with fresh length.
            raw = bytearray()
            while True:
                chunk = upstream_res.read(65536)
                if not chunk:
                    break
                raw.extend(decode(chunk))
            body_text = bytes(raw).decode("utf-8", "replace")
            obj = safe_json_parse(body_text)
            if isinstance(obj, dict):
                n = 0
                if tn.get("response"):
                    n += rewrite_response_tool_names(obj, tn.get("map"))
                if tn.get("repairInput"):
                    n += repair_tool_use_input(obj)
                if n > 0:
                    exchange.setdefault("mutation", {})["responseRewrites"] = n
                out_buf = json.dumps(obj).encode("utf-8")
            else:
                out_buf = body_text.encode("utf-8")

            self.send_response_only(status, upstream_res.reason)
            for k, v in base_out:
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(out_buf)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            try:
                self.wfile.write(out_buf)
            except (BrokenPipeError, ConnectionError, OSError):
                pass
            try:
                exchange["response"] = parser.parse_response(json.dumps(obj) if isinstance(obj, dict) else body_text)
            except Exception as err:
                exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                        "usage": None, "raw": None, "parseError": str(err)}
            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
            recorder.record(exchange)

        def _base_out_headers(self):
            out = {}
            for k, v in self.headers.items():
                lk = k.lower()
                if lk in HOP_BY_HOP:
                    continue
                if lk == "accept-encoding":
                    out[k] = "gzip, deflate"
                    continue
                out[k] = v
            return out

        def _make_exchange(self, wire, url, body_bytes, started):
            parser = PARSERS[wire]
            try:
                parsed_request = parser.parse_request(body_bytes.decode("utf-8", "replace"))
            except Exception:
                parsed_request = {"wire": wire, "model": None, "messages": [],
                                  "tools": [], "stream": False, "raw": None}
            return {
                "id": str(uuid.uuid4()),
                "ts": started.isoformat().replace("+00:00", "Z"),
                "durationMs": 0,
                "wire": wire,
                "method": self.command,
                "url": url,
                "reqHeaders": _redact_headers(self.headers.items(), config["redactAuth"]),
                "requestBodyRaw": _truncate(body_bytes, config["maxBodyBytes"]),
                "request": parsed_request,
                "resStatus": 0,
                "resHeaders": {},
                "response": None,
                "error": None,
            }

        def _commit_stream(self, upstream_res, parser, exchange, started):
            """Stream a 2xx response to the client while teeing a decoded copy
            into the parser. Mirrors steps 5-7 of the transparent path."""
            res_header_pairs = upstream_res.getheaders()
            exchange["resStatus"] = upstream_res.status
            exchange["resHeaders"] = {k: v for k, v in res_header_pairs}
            content_type = upstream_res.getheader("content-type") or ""
            is_sse = "text/event-stream" in content_type
            content_encoding = upstream_res.getheader("content-encoding")

            self.send_response_only(upstream_res.status, upstream_res.reason)
            for k, v in res_header_pairs:
                if k.lower() in HOP_BY_HOP:
                    continue
                self.send_header(k, v)
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

            sse = SSEParser() if is_sse else None
            agg = parser.create_stream_aggregator() if is_sse else None
            if sse:
                sse.on("event", lambda e: agg["feed"](e))
            decode = _make_stream_decoder(content_encoding)
            raw_copy = bytearray()

            def on_decoded(buf):
                if not buf:
                    return
                if sse:
                    sse.push(buf.decode("utf-8", "replace"))
                elif len(raw_copy) < config["maxBodyBytes"]:
                    raw_copy.extend(buf)

            client_alive = True
            while True:
                chunk = upstream_res.read(65536)
                if not chunk:
                    break
                if client_alive:
                    try:
                        self.wfile.write(chunk)
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionError, OSError):
                        client_alive = False
                on_decoded(decode(chunk))

            try:
                if sse:
                    sse.flush()
                    exchange["response"] = agg["result"]()
                else:
                    exchange["response"] = parser.parse_response(bytes(raw_copy).decode("utf-8", "replace"))
            except Exception as err:
                exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                        "usage": None, "raw": None, "parseError": str(err)}
            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
            recorder.record(exchange)

        def _handle_resilient(self, config, recorder, req_body, started, wire):
            parser = PARSERS[wire]
            candidates = resolve_candidates(config, wire)
            group = wire_to_group(wire)
            pool_configured = len((((config.get("providers") or {}).get("pools") or {}).get(group)) or []) > 0
            use_breaker = bool((config.get("breaker") or {}).get("enabled") or pool_configured)
            failover_statuses = (config.get("breaker") or {}).get("failoverStatuses") or {429, 500, 502, 503, 504}
            rectify_on = bool((config.get("rectifier") or {}).get("enabled") and wire == "anthropic")

            base_headers = self._base_out_headers()
            anth_obj = (safe_json_parse(req_body.decode("utf-8", "replace")) or {}) if wire == "anthropic" else None

            last_error = None  # (status, header_pairs, raw_bytes) of most recent failover
            attempt_no = 0
            attempted_any = False

            for i, cand in enumerate(candidates):
                is_last = i == len(candidates) - 1
                permit = {"allowed": True, "halfOpen": False}
                if use_breaker:
                    permit = breakers.can_request(cand["id"])
                    if not permit["allowed"]:
                        continue
                attempted_any = True

                body_bytes = req_body
                rectified_kind = None
                rectify_tried = False

                while True:
                    attempt_no += 1
                    scheme, host, port, netloc = _split_upstream(cand["baseUrl"])
                    url = f"{scheme}://{netloc}{self.path}"
                    out_headers = dict(base_headers)
                    out_headers["Host"] = netloc
                    if body_bytes:
                        out_headers["Content-Length"] = str(len(body_bytes))
                    if cand.get("apiKey"):
                        # swap in this provider's credential, dropping the other style
                        for hk in list(out_headers):
                            if hk.lower() in ("authorization", "x-api-key"):
                                del out_headers[hk]
                        if wire == "anthropic":
                            out_headers["x-api-key"] = cand["apiKey"]
                        else:
                            out_headers["Authorization"] = f"Bearer {cand['apiKey']}"

                    exchange = self._make_exchange(wire, url, body_bytes, started)
                    exchange["resilience"] = {"providerId": cand["id"], "attempt": attempt_no}
                    if rectified_kind:
                        exchange["resilience"]["rectified"] = rectified_kind

                    # Opt-in: provider-scoped filters + tool-name normalization.
                    send_body = _apply_outbound_mutation(config, wire, cand["id"], out_headers, body_bytes, exchange)
                    if send_body is not body_bytes and send_body is not None:
                        out_headers["Content-Length"] = str(len(send_body))

                    conn = _open_connection(config, scheme, host, port)
                    try:
                        conn.request(self.command, self.path, body=send_body or None, headers=out_headers)
                        upstream_res = conn.getresponse()
                    except Exception as err:
                        exchange["error"] = f"upstream request error: {err}"
                        exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                        exchange["resilience"]["failedOver"] = not is_last
                        recorder.record(exchange)
                        if use_breaker:
                            breakers.record_failure(cand["id"], permit["halfOpen"])
                        conn.close()
                        break  # next candidate

                    status = upstream_res.status
                    if 200 <= status < 300:
                        if _response_rewrite_active(config, wire):
                            self._forward_rewritten(upstream_res, exchange, started, config)
                        else:
                            self._commit_stream(upstream_res, parser, exchange, started)
                        conn.close()
                        if use_breaker:
                            breakers.record_success(cand["id"], permit["halfOpen"])
                        return

                    # non-2xx: buffer the (decoded) error body fully.
                    raw_bytes = upstream_res.read()
                    decode = _make_stream_decoder(upstream_res.getheader("content-encoding"))
                    text = decode(raw_bytes).decode("utf-8", "replace")
                    obj = safe_json_parse(text)
                    header_pairs = upstream_res.getheaders()
                    conn.close()
                    exchange["resStatus"] = status
                    exchange["resHeaders"] = {k: v for k, v in header_pairs}
                    err_msg = None
                    if isinstance(obj, dict):
                        e = obj.get("error")
                        err_msg = (e.get("message") if isinstance(e, dict) else None) or obj.get("message")
                    exchange["error"] = err_msg or f"upstream {status}"
                    try:
                        exchange["response"] = parser.parse_response(text)
                    except Exception as err:
                        exchange["response"] = {"text": "", "toolCalls": [], "stopReason": None,
                                                "usage": None, "raw": None, "parseError": str(err)}

                    # (a) rectifiable Anthropic error -> rewrite + retry SAME provider once.
                    if rectify_on and not rectify_tried:
                        kind = detect_rectification(status, obj, config.get("rectifier"))
                        if kind:
                            rectify_tried = True
                            rectified_kind = kind
                            exchange["resilience"]["rectifyTriggered"] = kind
                            exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                            recorder.record(exchange)  # log pre-rectify failure (no breaker change)
                            body_bytes = json.dumps(apply_rectification(kind, anth_obj)).encode("utf-8")
                            continue  # retry same candidate (permit still held)

                    # (b) failover-eligible and another provider remains -> next.
                    if status in failover_statuses and not is_last:
                        exchange["resilience"]["failedOver"] = True
                        exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                        recorder.record(exchange)
                        if use_breaker:
                            breakers.record_failure(cand["id"], permit["halfOpen"])
                        last_error = (status, header_pairs, raw_bytes)
                        break

                    # (c) terminal error -> commit to client as-is.
                    self.send_response_only(status)
                    for k, v in header_pairs:
                        if k.lower() in HOP_BY_HOP:
                            continue
                        self.send_header(k, v)
                    self.send_header("Content-Length", str(len(raw_bytes)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.close_connection = True
                    if raw_bytes:
                        self.wfile.write(raw_bytes)
                    exchange["durationMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
                    recorder.record(exchange)
                    if use_breaker:
                        if status in failover_statuses:
                            breakers.record_failure(cand["id"], permit["halfOpen"])
                        else:
                            breakers.record_neutral(cand["id"], permit["halfOpen"])
                    return

            # All candidates exhausted: replay the last buffered error, else 502.
            self.close_connection = True
            if last_error is not None:
                status, header_pairs, raw_bytes = last_error
                self.send_response_only(status)
                for k, v in header_pairs:
                    if k.lower() in HOP_BY_HOP:
                        continue
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(raw_bytes)))
                self.send_header("Connection", "close")
                self.end_headers()
                if raw_bytes:
                    self.wfile.write(raw_bytes)
            else:
                msg = "all upstream providers failed" if attempted_any else "all providers unavailable (circuit open)"
                if wire == "anthropic":
                    payload = {"type": "error", "error": {"type": "proxy_error", "message": msg}}
                else:
                    payload = {"error": {"type": "proxy_error", "message": msg}}
                out = json.dumps(payload).encode("utf-8")
                self.send_response_only(502)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(out)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(out)

        # BaseHTTPRequestHandler dispatches by method name (do_GET, do_POST,
        # ...). We proxy every verb identically, so point them all at _handle.
        do_GET = _handle
        do_POST = _handle
        do_PUT = _handle
        do_PATCH = _handle
        do_DELETE = _handle
        do_HEAD = _handle
        do_OPTIONS = _handle

    # Expose the shared breaker registry for tests/introspection (mirrors the
    # Node proxy's `proxy.breakers`).
    ProxyHandler.breakers = breakers
    return ProxyHandler


def start_proxy(config, recorder):
    handler = _make_handler(config, recorder)
    bind_addr = config.get("bindAddr") or "127.0.0.1"
    server = ThreadingHTTPServer((bind_addr, config["proxyPort"]), handler)
    server.daemon_threads = True
    server.breakers = handler.breakers
    actual_port = server.server_address[1]
    config["proxyPort"] = actual_port
    print(f"[proxy] listening on http://{bind_addr}:{actual_port}")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server
