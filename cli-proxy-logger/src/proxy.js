// Core reverse proxy.
//
// Flow:
//   1. Buffer the incoming request body (CLI request bodies are complete JSON).
//   2. resolveUpstream() picks the real upstream + wire format from the path.
//   3. Forward method/path/headers/body to the upstream over http(s).
//   4. Stream the upstream response back to the client byte-for-byte (fidelity
//      first — the CLI must be unaffected), while teeing a decoded COPY into the
//      matching parser to reconstruct text + tool calls.
//   5. Record the normalized Exchange.

import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';
import { randomUUID } from 'node:crypto';
import { resolveUpstream } from './upstream.js';
import { SSEParser } from './sse.js';
import * as anthropic from './parsers/anthropic.js';
import * as openaiResponses from './parsers/openaiResponses.js';
import * as openaiChat from './parsers/openaiChat.js';
import { safeJsonParse } from './model.js';
import { BreakerRegistry } from './breaker.js';
import { resolveCandidates, wireToGroup } from './providers.js';
import { detectRectification, applyRectification } from './rectifier.js';
import { applyFilters } from './filters.js';
import {
  normalizeRequestToolNames,
  rewriteResponseToolNames,
  repairToolUseInput,
  rewriteStreamEventToolName,
} from './transform.js';
import {
  anthropicRequestToChat,
  chatResponseToAnthropic,
  chatErrorToAnthropic,
  anthropicMessageToSSE,
  anthropicErrorSSE,
  mapModel,
  ChatToAnthropicStream,
} from './translate.js';

const PARSERS = { anthropic, responses: openaiResponses, chat: openaiChat };

// "Hop-by-hop" headers are meaningful only for a single transport connection
// (per RFC 7230 6.1) and must NOT be blindly relayed by a proxy. We also drop
// host/content-length here because we recompute them for the new connection,
// and transfer-encoding because Node re-frames the body for us.
const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'host',
  'content-length',
]);

// Mask credentials before they are written to disk. The *real* key is still
// forwarded to the upstream untouched — only the logged copy is redacted, so
// your JSONL files never contain a usable API key.
function redactHeaders(headers, redact) {
  const out = {};
  for (const [k, v] of Object.entries(headers)) {
    const lk = k.toLowerCase();
    if (redact && (lk === 'authorization' || lk === 'x-api-key' || lk === 'api-key')) {
      const s = Array.isArray(v) ? v.join(',') : String(v);
      // Keep a short prefix/suffix so two keys are distinguishable in logs
      // without exposing the secret (e.g. "Bearer...7912").
      out[k] = s.length <= 12 ? '***' : `${s.slice(0, 6)}...${s.slice(-4)}`;
    } else {
      out[k] = v;
    }
  }
  return out;
}

// Build a streaming decompressor for the COPY we parse. Node ships gzip,
// deflate AND brotli natively (unlike Python's stdlib), so we can decode
// whatever the upstream picked. Returns null for identity/unknown encodings —
// then we just parse the bytes as-is.
function makeDecoder(contentEncoding) {
  switch ((contentEncoding || '').toLowerCase()) {
    case 'gzip':
      return zlib.createGunzip();
    case 'deflate':
      return zlib.createInflate();
    case 'br':
      return zlib.createBrotliDecompress();
    default:
      return null;
  }
}

function truncate(buf, max) {
  if (buf.length <= max) return { text: buf.toString('utf8'), truncated: false };
  return { text: buf.slice(0, max).toString('utf8') + `\n...[truncated ${buf.length - max} bytes]`, truncated: true };
}

// --- Opt-in outbound request mutation (filters + tool-name normalization) ----
// Mutates `outHeaders` in place (header filters) and returns the possibly-new
// body buffer (when body filters / tool-name rewriting changed it). Records what
// was applied onto `exchange` (when provided) for UI/log visibility. Default
// path is untouched: returns the original buffer when nothing is configured.
function applyOutboundMutation(config, wire, providerId, outHeaders, bodyBuf, exchange) {
  const filters = config.filters;
  const tn = config.transform?.toolName;
  const toolNameReq = !!(tn?.enabled && tn.request && wire === 'anthropic');
  const hasFilters = Array.isArray(filters) && filters.length > 0;
  if (!hasFilters && !toolNameReq) return bodyBuf;

  const needBody = toolNameReq || (hasFilters && filters.some((f) => f.domain === 'body'));
  let bodyObj = null;
  if (needBody && bodyBuf.length > 0) bodyObj = safeJsonParse(bodyBuf.toString('utf8'));

  let changed = false;
  const meta = {};
  if (hasFilters) {
    const res = applyFilters(filters, { providerId, headers: outHeaders, body: bodyObj });
    if (res.applied.length) meta.filters = res.applied;
    if (res.bodyChanged) changed = true;
  }
  if (toolNameReq && bodyObj) {
    const n = normalizeRequestToolNames(bodyObj, tn.map);
    if (n > 0) {
      meta.toolNamesRewritten = n;
      changed = true;
    }
  }
  if (exchange && (meta.filters || meta.toolNamesRewritten)) {
    exchange.mutation = { ...(exchange.mutation || {}), ...meta };
  }
  if (changed && bodyObj) return Buffer.from(JSON.stringify(bodyObj));
  return bodyBuf;
}

// Should we rewrite the Anthropic RESPONSE (tool names / input repair)? This
// trades byte-for-byte fidelity for client compatibility, so it only happens
// when the feature is explicitly enabled for Anthropic traffic.
function responseRewriteActive(config, wire) {
  const tn = config.transform?.toolName;
  return !!(tn?.enabled && wire === 'anthropic' && (tn.response || tn.repairInput));
}

// Rewrite-and-forward an Anthropic upstream response to the client (instead of
// the verbatim tee), then record the exchange. Handles both SSE and plain JSON.
// Because we change the body, we drop content-encoding/length and send identity.
function forwardRewrittenResponse(upstreamRes, clientRes, exchange, config, recorder, started) {
  const tn = config.transform.toolName;
  const status = upstreamRes.statusCode || 502;
  exchange.resStatus = status;
  exchange.resHeaders = upstreamRes.headers;
  const contentType = String(upstreamRes.headers['content-type'] || '');
  const isSSE = contentType.includes('text/event-stream');
  const decoder = makeDecoder(upstreamRes.headers['content-encoding']);

  // Outgoing headers: keep everything except framing/encoding we recompute.
  const baseOut = {};
  for (const [k, v] of Object.entries(upstreamRes.headers)) {
    const lk = k.toLowerCase();
    if (lk === 'content-encoding' || lk === 'content-length' || lk === 'transfer-encoding') continue;
    baseOut[k] = v;
  }

  return new Promise((resolve) => {
    if (isSSE) {
      clientRes.writeHead(status, baseOut);
      const sse = new SSEParser();
      const agg = anthropic.createStreamAggregator();
      sse.on('event', (e) => agg.feed(e));
      let lineBuf = '';
      const flushLines = (final) => {
        const parts = lineBuf.split('\n');
        lineBuf = final ? '' : parts.pop();
        for (const line of parts) clientRes.write(rewriteSSELine(line, tn) + '\n');
        if (final && lineBuf) clientRes.write(rewriteSSELine(lineBuf, tn) + '\n');
      };
      const onDecoded = (buf) => {
        const text = buf.toString('utf8');
        sse.push(text); // logging copy
        lineBuf += text;
        flushLines(false);
      };
      if (decoder) {
        decoder.on('data', onDecoded);
        decoder.on('error', () => {});
      }
      upstreamRes.on('data', (chunk) => {
        if (decoder) decoder.write(chunk);
        else onDecoded(chunk);
      });
      upstreamRes.on('end', () => {
        const finish = () => {
          flushLines(true);
          clientRes.end();
          try {
            sse.flush();
            exchange.response = agg.result();
          } catch (err) {
            exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
          }
          exchange.durationMs = Date.now() - started;
          recorder.record(exchange);
          resolve();
        };
        if (decoder) decoder.end(() => finish());
        else finish();
      });
      upstreamRes.on('error', (err) => {
        exchange.error = `upstream stream error: ${err.message}`;
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        clientRes.destroy();
        resolve();
      });
      return;
    }

    // Non-SSE: buffer the decoded body, rewrite, send with fresh content-length.
    const dec = [];
    const onDecoded = (buf) => dec.push(buf);
    if (decoder) {
      decoder.on('data', onDecoded);
      decoder.on('error', () => {});
    }
    upstreamRes.on('data', (chunk) => {
      if (decoder) decoder.write(chunk);
      else onDecoded(chunk);
    });
    upstreamRes.on('end', () => {
      const finish = () => {
        const bodyText = Buffer.concat(dec).toString('utf8');
        const obj = safeJsonParse(bodyText);
        let outBuf;
        if (obj && typeof obj === 'object') {
          let n = 0;
          if (tn.response) n += rewriteResponseToolNames(obj, tn.map);
          if (tn.repairInput) n += repairToolUseInput(obj);
          if (n > 0) exchange.mutation = { ...(exchange.mutation || {}), responseRewrites: n };
          outBuf = Buffer.from(JSON.stringify(obj));
        } else {
          outBuf = Buffer.from(bodyText, 'utf8');
        }
        baseOut['content-length'] = String(outBuf.length);
        clientRes.writeHead(status, baseOut);
        clientRes.end(outBuf);
        try {
          exchange.response = anthropic.parseResponse(obj ? JSON.stringify(obj) : bodyText);
        } catch (err) {
          exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
        }
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        resolve();
      };
      if (decoder) decoder.end(() => finish());
      else finish();
    });
    upstreamRes.on('error', (err) => {
      exchange.error = `upstream stream error: ${err.message}`;
      exchange.durationMs = Date.now() - started;
      recorder.record(exchange);
      clientRes.destroy();
      resolve();
    });
  });
}

// Transform a single SSE text line: only `data:` lines carrying a tool_use
// content_block_start are rewritten; everything else (event:, id:, comments,
// blanks) passes through byte-identical so the event framing is preserved.
function rewriteSSELine(line, tn) {
  if (!tn.response || !line.startsWith('data:')) return line;
  const jsonStr = line.slice(5).trim();
  if (!jsonStr || jsonStr === '[DONE]') return line;
  const data = safeJsonParse(jsonStr);
  if (!data) return line;
  if (rewriteStreamEventToolName(data, tn.map)) return 'data: ' + JSON.stringify(data);
  return line;
}

export function startProxy(config, recorder) {
  // One breaker registry per proxy instance, shared across all requests so
  // failure state for a provider persists between requests.
  const breakers = new BreakerRegistry(config.breaker || {});
  const server = http.createServer((clientReq, clientRes) => {
    const chunks = [];
    clientReq.on('data', (c) => chunks.push(c));
    clientReq.on('end', () => {
      const reqBodyBuf = Buffer.concat(chunks);
      handleRequest(config, recorder, breakers, clientReq, clientRes, reqBodyBuf);
    });
    clientReq.on('error', () => clientRes.destroy());
  });
  server.breakers = breakers; // exposed for tests / introspection

  const bindAddr = config.bindAddr || '127.0.0.1';
  server.listen(config.proxyPort, bindAddr, () => {
    console.log(`[proxy] listening on http://${bindAddr}:${server.address().port}`);
  });
  return server;
}

// Is any resilience feature (provider pool / breaker / rectifier) active for
// this request? When false we take the original, untouched transparent path so
// default behavior is byte-for-byte identical to before these features existed.
function isResilient(config, wire) {
  const group = wireToGroup(wire);
  const pool = config.providers?.pools?.[group];
  if (Array.isArray(pool) && pool.length > 0) return true;
  if (config.breaker?.enabled) return true;
  if (config.rectifier?.enabled && wire === 'anthropic') return true;
  return false;
}

function handleRequest(config, recorder, breakers, clientReq, clientRes, reqBodyBuf) {
  const started = Date.now();

  // Step 2: pick the real upstream + wire format. The path alone tells us who
  // the client is: /v1/messages == Claude Code (Anthropic), /v1/responses or
  // /v1/chat/completions == Codex. (Step 1 — buffering the body — happened in
  // startProxy before calling us.)
  const { baseUrl, wire } = resolveUpstream(config, clientReq.url, clientReq.headers);

  // Compat mode: when ANTHROPIC_COMPAT=chat and the client is speaking Anthropic
  // Messages, hand off to the TRANSLATING path (Anthropic -> OpenAI Chat) instead
  // of the transparent tee. This is the only situation where we rewrite both the
  // request and the response rather than forwarding bytes verbatim.
  if (config.compat?.anthropicTo === 'chat' && wire === 'anthropic'
      && (clientReq.url || '').split('?')[0].startsWith('/v1/messages')) {
    handleAnthropicToChat(config, recorder, breakers, clientReq, clientRes, reqBodyBuf, started);
    return;
  }

  // Resilient transparent path: multi-provider failover + circuit breaker +
  // (Anthropic) thinking rectification. Only taken when opted in.
  if (isResilient(config, wire)) {
    handleTransparentResilient(config, recorder, breakers, clientReq, clientRes, reqBodyBuf, started, wire);
    return;
  }

  const parser = PARSERS[wire];
  const upstreamUrl = new URL(clientReq.url, baseUrl);
  const isHttps = upstreamUrl.protocol === 'https:';
  const mod = isHttps ? https : http;

  // Step 3: copy the client's headers through verbatim (including the real
  // Authorization / x-api-key) so the upstream sees an identical request —
  // this is why CLI-specific gating (e.g. cc.freemodel.dev only answering real
  // Claude Code headers) still works through us. Only hop-by-hop headers are
  // stripped. (We keep Node's native Accept-Encoding, since Node can decode
  // brotli — the Python port has to normalize it to gzip/deflate instead.)
  const outHeaders = {};
  for (const [k, v] of Object.entries(clientReq.headers)) {
    if (!HOP_BY_HOP.has(k.toLowerCase())) outHeaders[k] = v;
  }

  // Parse the request body now (it is plain JSON) into the normalized shape.
  // Wrapped in try/catch: a parse bug must never stop us from forwarding.
  let parsedRequest;
  try {
    parsedRequest = parser.parseRequest(reqBodyBuf.toString('utf8'));
  } catch {
    parsedRequest = { wire, model: null, messages: [], tools: [], stream: false, raw: null };
  }

  const exchange = {
    id: randomUUID(),
    ts: new Date(started).toISOString(),
    durationMs: 0,
    wire,
    method: clientReq.method,
    url: upstreamUrl.toString(),
    reqHeaders: redactHeaders(clientReq.headers, config.redactAuth),
    requestBodyRaw: truncate(reqBodyBuf, config.maxBodyBytes).text,
    request: parsedRequest,
    resStatus: 0,
    resHeaders: {},
    response: null,
    error: null,
  };

  // Opt-in request mutation (filters + tool-name normalization) before send,
  // then (re)compute host + content-length for the body we actually forward.
  const sendBody = applyOutboundMutation(config, wire, null, outHeaders, reqBodyBuf, exchange);
  outHeaders['host'] = upstreamUrl.host;
  if (sendBody.length > 0) outHeaders['content-length'] = String(sendBody.length);
  const agent = config.outbound ? config.outbound.agentFor(upstreamUrl) : undefined;

  // Step 4: open the upstream connection and send the request.
  const upstreamReq = mod.request(
    upstreamUrl,
    { method: clientReq.method, headers: outHeaders, agent },
    (upstreamRes) => {
      // Opt-in: rewrite the Anthropic response (tool names / input repair)
      // instead of the verbatim tee. Trades fidelity for client compatibility.
      if (responseRewriteActive(config, wire)) {
        forwardRewrittenResponse(upstreamRes, clientRes, exchange, config, recorder, started);
        return;
      }
      // Step 5: relay the upstream status + headers straight back to the
      // client. Node forwards them as-is (it manages framing for us).
      clientRes.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      exchange.resStatus = upstreamRes.statusCode || 0;
      exchange.resHeaders = upstreamRes.headers;

      // Step 6 setup: decide how to parse. For SSE we run an incremental SSE
      // parser whose events feed a per-wire aggregator that rebuilds text +
      // tool calls; for plain JSON we buffer and parse once at the end.
      const contentType = String(upstreamRes.headers['content-type'] || '');
      const isSSE = contentType.includes('text/event-stream');
      const decoder = makeDecoder(upstreamRes.headers['content-encoding']);

      const sse = isSSE ? new SSEParser() : null;
      const agg = isSSE ? parser.createStreamAggregator() : null;
      if (sse) sse.on('event', (e) => agg.feed(e));

      const rawCopy = [];
      let rawCopyLen = 0;

      const onDecoded = (buf) => {
        if (sse) {
          sse.push(buf.toString('utf8'));
        } else if (rawCopyLen < config.maxBodyBytes) {
          rawCopy.push(buf);
          rawCopyLen += buf.length;
        }
      };

      if (decoder) {
        decoder.on('data', onDecoded);
        decoder.on('error', () => {}); // never break forwarding on decode error
      }

      // Step 6: the dual-path tee. For every chunk from the upstream we write
      // the ORIGINAL bytes to the client FIRST (fidelity: the CLI must be
      // unaffected even if our parser later throws), then feed a decoded copy
      // into the SSE parser / raw buffer.
      upstreamRes.on('data', (chunk) => {
        clientRes.write(chunk); // forward raw bytes first
        if (decoder) decoder.write(chunk);
        else onDecoded(chunk);
      });

      upstreamRes.on('end', () => {
        clientRes.end();
        // Step 7: finalize the normalized response and record the exchange.
        const finalize = () => {
          try {
            if (sse) {
              sse.flush();
              exchange.response = agg.result();
            } else {
              const body = Buffer.concat(rawCopy);
              exchange.response = parser.parseResponse(body.toString('utf8'));
            }
          } catch (err) {
            exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
          }
          exchange.durationMs = Date.now() - started;
          recorder.record(exchange);
        };
        if (decoder) decoder.end(() => finalize());
        else finalize();
      });

      upstreamRes.on('error', (err) => {
        exchange.error = `upstream stream error: ${err.message}`;
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        clientRes.destroy();
      });
    },
  );

  upstreamReq.on('error', (err) => {
    exchange.error = `upstream request error: ${err.message}`;
    exchange.durationMs = Date.now() - started;
    recorder.record(exchange);
    if (!clientRes.headersSent) clientRes.writeHead(502, { 'content-type': 'application/json' });
    clientRes.end(JSON.stringify({ error: { type: 'proxy_error', message: err.message } }));
  });

  if (sendBody.length > 0) upstreamReq.write(sendBody);
  upstreamReq.end();
}

// =============================================================================
// Compat path: Anthropic /v1/messages  ->  OpenAI /v1/chat/completions
//
// Unlike handleRequest (transparent tee), this path REWRITES both directions:
//   request  : translate.anthropicRequestToChat() -> POST /v1/chat/completions
//   response : OpenAI Chat (SSE or JSON) -> Anthropic events sent to the client
// The client (Claude Code) never knows the vendor speaks a different protocol.
// =============================================================================
function handleAnthropicToChat(config, recorder, breakers, clientReq, clientRes, reqBodyBuf, started) {
  const anthBody = safeJsonParse(reqBodyBuf.toString('utf8')) || {};
  const wantStream = !!anthBody.stream;
  const originalModel = anthBody.model ?? null;

  // 1) Translate the request body and pick the mapped model.
  const chatBody = anthropicRequestToChat(anthBody, config.compat.modelMap);
  const mappedModel = chatBody.model;
  const chatBodyBuf = Buffer.from(JSON.stringify(chatBody));

  // 2) Build the upstream request to the OpenAI-style vendor.
  const baseUrl = config.upstream.openai;
  const upstreamUrl = new URL('/v1/chat/completions', baseUrl);
  const isHttps = upstreamUrl.protocol === 'https:';
  const mod = isHttps ? https : http;

  // Copy client headers minus hop-by-hop, then fix up auth + content for an
  // OpenAI vendor: Claude Code authenticates with `x-api-key`, but OpenAI-style
  // vendors expect `Authorization: Bearer`. We translate that, and drop
  // Anthropic-only headers the vendor would not understand.
  const outHeaders = {};
  for (const [k, v] of Object.entries(clientReq.headers)) {
    const lk = k.toLowerCase();
    if (HOP_BY_HOP.has(lk)) continue;
    if (lk === 'x-api-key' || lk === 'anthropic-version' || lk === 'anthropic-beta'
        || lk === 'anthropic-dangerous-direct-browser-access' || lk === 'content-type') {
      continue;
    }
    outHeaders[k] = v;
  }
  const auth = clientReq.headers['authorization'];
  const apiKey = clientReq.headers['x-api-key'];
  if (auth) outHeaders['authorization'] = auth;
  else if (apiKey) outHeaders['authorization'] = `Bearer ${apiKey}`;
  outHeaders['host'] = upstreamUrl.host;
  outHeaders['content-type'] = 'application/json';
  outHeaders['content-length'] = String(chatBodyBuf.length);

  // 3) Record the exchange as an Anthropic request (what the client sent) with a
  // translation marker; the response is normalized from the chat side below.
  let parsedRequest;
  try {
    parsedRequest = anthropic.parseRequest(reqBodyBuf.toString('utf8'));
  } catch {
    parsedRequest = { wire: 'anthropic', model: originalModel, messages: [], tools: [], stream: wantStream, raw: null };
  }
  const exchange = {
    id: randomUUID(),
    ts: new Date(started).toISOString(),
    durationMs: 0,
    wire: 'anthropic',
    method: clientReq.method,
    url: upstreamUrl.toString(),
    reqHeaders: redactHeaders(clientReq.headers, config.redactAuth),
    requestBodyRaw: truncate(reqBodyBuf, config.maxBodyBytes).text,
    request: parsedRequest,
    translation: { from: 'anthropic', to: 'chat', model: originalModel, upstreamModel: mappedModel },
    resStatus: 0,
    resHeaders: {},
    response: null,
    error: null,
  };

  const agent = config.outbound ? config.outbound.agentFor(upstreamUrl) : undefined;
  const upstreamReq = mod.request(upstreamUrl, { method: 'POST', headers: outHeaders, agent }, (upstreamRes) => {
    const status = upstreamRes.statusCode || 502;
    exchange.resStatus = status;
    exchange.resHeaders = upstreamRes.headers;
    const contentType = String(upstreamRes.headers['content-type'] || '');
    const isSSE = contentType.includes('text/event-stream');
    const decoder = makeDecoder(upstreamRes.headers['content-encoding']);

    // We always build a normalized response for logging via the chat parser:
    //  - streaming  -> feed the chat SSE aggregator
    //  - non-stream -> parse the buffered JSON once at the end
    const chatAgg = isSSE ? openaiChat.createStreamAggregator() : null;

    if (isSSE) {
      // ---- streaming translation ----
      clientRes.writeHead(status, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache',
        connection: 'close',
      });
      const translator = new ChatToAnthropicStream(originalModel || mappedModel);
      const sseParser = new SSEParser();
      sseParser.on('event', (e) => {
        const raw = (e.data || '').trim();
        if (raw === '' || raw === '[DONE]') return;
        const data = safeJsonParse(raw);
        if (!data) return;
        chatAgg.feed(e); // for the logged normalized response
        for (const frame of translator.feed(data)) clientRes.write(frame);
      });

      const onDecoded = (buf) => sseParser.push(buf.toString('utf8'));
      if (decoder) {
        decoder.on('data', onDecoded);
        decoder.on('error', () => {});
      }
      upstreamRes.on('data', (chunk) => {
        if (decoder) decoder.write(chunk);
        else onDecoded(chunk);
      });
      upstreamRes.on('end', () => {
        const finish = () => {
          sseParser.flush();
          for (const frame of translator.end()) clientRes.write(frame);
          clientRes.end();
          try {
            exchange.response = chatAgg.result();
          } catch (err) {
            exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
          }
          exchange.durationMs = Date.now() - started;
          recorder.record(exchange);
        };
        if (decoder) decoder.end(() => finish());
        else finish();
      });
      upstreamRes.on('error', (err) => {
        exchange.error = `upstream stream error: ${err.message}`;
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        clientRes.destroy();
      });
      return;
    }

    // ---- buffered (non-SSE) translation ----
    // Covers both a plain JSON chat completion and an error body. We may still
    // owe the client an SSE stream (if it asked for one), in which case we
    // synthesize the Anthropic event sequence from the full message.
    const rawCopy = [];
    const onDecoded = (buf) => rawCopy.push(buf);
    if (decoder) {
      decoder.on('data', onDecoded);
      decoder.on('error', () => {});
    }
    upstreamRes.on('data', (chunk) => {
      if (decoder) decoder.write(chunk);
      else onDecoded(chunk);
    });
    upstreamRes.on('end', () => {
      const finish = () => {
        const bodyText = Buffer.concat(rawCopy).toString('utf8');
        const obj = safeJsonParse(bodyText);
        const isError = status >= 400 || (obj && typeof obj === 'object' && obj.error);

        if (isError) {
          const anthErr = chatErrorToAnthropic(obj || bodyText);
          if (wantStream) {
            clientRes.writeHead(status, { 'content-type': 'text/event-stream; charset=utf-8', connection: 'close' });
            clientRes.end(anthropicErrorSSE(obj || bodyText));
          } else {
            const buf = Buffer.from(JSON.stringify(anthErr));
            clientRes.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': String(buf.length) });
            clientRes.end(buf);
          }
          exchange.error = anthErr.error?.message || 'upstream error';
        } else {
          const anthObj = chatResponseToAnthropic(obj || {}, originalModel || mappedModel);
          if (wantStream) {
            clientRes.writeHead(status, { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-cache', connection: 'close' });
            for (const frame of anthropicMessageToSSE(anthObj)) clientRes.write(frame);
            clientRes.end();
          } else {
            const buf = Buffer.from(JSON.stringify(anthObj));
            clientRes.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': String(buf.length) });
            clientRes.end(buf);
          }
        }
        try {
          exchange.response = openaiChat.parseResponse(bodyText);
        } catch (err) {
          exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
        }
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
      };
      if (decoder) decoder.end(() => finish());
      else finish();
    });
    upstreamRes.on('error', (err) => {
      exchange.error = `upstream stream error: ${err.message}`;
      exchange.durationMs = Date.now() - started;
      recorder.record(exchange);
      clientRes.destroy();
    });
  });

  upstreamReq.on('error', (err) => {
    exchange.error = `upstream request error: ${err.message}`;
    exchange.durationMs = Date.now() - started;
    recorder.record(exchange);
    if (!clientRes.headersSent) clientRes.writeHead(502, { 'content-type': 'application/json' });
    clientRes.end(JSON.stringify(chatErrorToAnthropic({ error: { type: 'proxy_error', message: err.message } })));
  });

  upstreamReq.write(chatBodyBuf);
  upstreamReq.end();
}

// =============================================================================
// Resilient transparent path: provider failover + circuit breaker + (Anthropic)
// thinking rectification. Reached only when isResilient() is true. The default
// transparent path above is left untouched so non-resilient traffic is
// byte-for-byte identical to before.
//
// Per client request we walk the ordered provider candidates. For each:
//   - skip it if its breaker is OPEN (and cooldown not elapsed);
//   - send the request; on a 2xx we COMMIT (stream the bytes to the client,
//     exactly like the transparent tee) and stop;
//   - on a connection error or a "failover status" (default 429/5xx) we record
//     the failure against the breaker and try the NEXT provider;
//   - on a rectifiable Anthropic error we rewrite the body and retry the SAME
//     provider ONCE (this is a client-compat fix, NOT counted as a breaker
//     failure);
//   - any other error (e.g. 401/403/400) is committed to the client as-is.
// Failover is only possible BEFORE we start streaming a 2xx to the client — once
// bytes are flowing we cannot cleanly switch providers, so we never do.
// =============================================================================

// One single HTTP attempt. Resolves with the upstream response object (caller
// inspects the status) or a connection error — it never writes to the client.
function transparentAttempt({ mod, upstreamUrl, method, outHeaders, bodyBuf, agent }) {
  return new Promise((resolve) => {
    const req = mod.request(upstreamUrl, { method, headers: outHeaders, agent }, (res) => resolve({ kind: 'response', res }));
    req.on('error', (error) => resolve({ kind: 'connError', error }));
    if (bodyBuf.length > 0) req.write(bodyBuf);
    req.end();
  });
}

// Buffer a (typically error) response fully, returning the raw bytes plus a
// decoded text/JSON view so we can both inspect it (rectifier) and replay it
// verbatim to the client if it turns out to be terminal.
function drainResponse(upstreamRes, config) {
  return new Promise((resolve) => {
    const decoder = makeDecoder(upstreamRes.headers['content-encoding']);
    const raw = [];
    const dec = [];
    const onDecoded = (buf) => dec.push(buf);
    if (decoder) {
      decoder.on('data', onDecoded);
      decoder.on('error', () => {});
    }
    upstreamRes.on('data', (chunk) => {
      if (raw.reduce((n, b) => n + b.length, 0) < config.maxBodyBytes) raw.push(chunk);
      if (decoder) decoder.write(chunk);
      else onDecoded(chunk);
    });
    upstreamRes.on('end', () => {
      const finish = () => {
        const rawBuf = Buffer.concat(raw);
        const text = Buffer.concat(dec).toString('utf8');
        resolve({ rawBuf, text, obj: safeJsonParse(text) });
      };
      if (decoder) decoder.end(() => finish());
      else finish();
    });
    upstreamRes.on('error', () => resolve({ rawBuf: Buffer.concat(raw), text: Buffer.concat(dec).toString('utf8'), obj: null }));
  });
}

// Commit a successful 2xx response: stream the bytes to the client (fidelity
// first) while teeing a decoded copy into the parser. Mirrors the original
// transparent tee. Resolves after the exchange is recorded.
function streamUpstreamToClient(upstreamRes, clientRes, exchange, parser, config, recorder, started, wire) {
  return new Promise((resolve) => {
    // Opt-in: rewrite the Anthropic response instead of the verbatim tee.
    if (responseRewriteActive(config, wire)) {
      forwardRewrittenResponse(upstreamRes, clientRes, exchange, config, recorder, started).then(resolve);
      return;
    }
    clientRes.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
    exchange.resStatus = upstreamRes.statusCode || 0;
    exchange.resHeaders = upstreamRes.headers;
    const contentType = String(upstreamRes.headers['content-type'] || '');
    const isSSE = contentType.includes('text/event-stream');
    const decoder = makeDecoder(upstreamRes.headers['content-encoding']);
    const sse = isSSE ? new SSEParser() : null;
    const agg = isSSE ? parser.createStreamAggregator() : null;
    if (sse) sse.on('event', (e) => agg.feed(e));
    const rawCopy = [];
    let rawCopyLen = 0;
    const onDecoded = (buf) => {
      if (sse) sse.push(buf.toString('utf8'));
      else if (rawCopyLen < config.maxBodyBytes) {
        rawCopy.push(buf);
        rawCopyLen += buf.length;
      }
    };
    if (decoder) {
      decoder.on('data', onDecoded);
      decoder.on('error', () => {});
    }
    upstreamRes.on('data', (chunk) => {
      clientRes.write(chunk);
      if (decoder) decoder.write(chunk);
      else onDecoded(chunk);
    });
    upstreamRes.on('end', () => {
      clientRes.end();
      const finalize = () => {
        try {
          if (sse) {
            sse.flush();
            exchange.response = agg.result();
          } else {
            exchange.response = parser.parseResponse(Buffer.concat(rawCopy).toString('utf8'));
          }
        } catch (err) {
          exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
        }
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        resolve();
      };
      if (decoder) decoder.end(() => finalize());
      else finalize();
    });
    upstreamRes.on('error', (err) => {
      exchange.error = `upstream stream error: ${err.message}`;
      exchange.durationMs = Date.now() - started;
      recorder.record(exchange);
      clientRes.destroy();
      resolve();
    });
  });
}

function errMessage(obj) {
  if (obj && typeof obj === 'object') return obj.error?.message ?? obj.message ?? null;
  return null;
}

function makeExchange({ started, wire, method, url, clientReq, bodyBuf, parser, config }) {
  let parsedRequest;
  try {
    parsedRequest = parser.parseRequest(bodyBuf.toString('utf8'));
  } catch {
    parsedRequest = { wire, model: null, messages: [], tools: [], stream: false, raw: null };
  }
  return {
    id: randomUUID(),
    ts: new Date(started).toISOString(),
    durationMs: 0,
    wire,
    method,
    url,
    reqHeaders: redactHeaders(clientReq.headers, config.redactAuth),
    requestBodyRaw: truncate(bodyBuf, config.maxBodyBytes).text,
    request: parsedRequest,
    resStatus: 0,
    resHeaders: {},
    response: null,
    error: null,
  };
}

async function handleTransparentResilient(config, recorder, breakers, clientReq, clientRes, reqBodyBuf, started, wire) {
  const parser = PARSERS[wire];
  const candidates = resolveCandidates(config, wire);
  const group = wireToGroup(wire);
  const poolConfigured = (config.providers?.pools?.[group]?.length || 0) > 0;
  const useBreaker = !!(config.breaker?.enabled || poolConfigured);
  const failoverStatuses = config.breaker?.failoverStatuses || new Set([429, 500, 502, 503, 504]);
  const rectifyOn = !!(config.rectifier?.enabled && wire === 'anthropic');

  // Verbatim client headers minus hop-by-hop (host/content-length recomputed).
  const baseHeaders = {};
  for (const [k, v] of Object.entries(clientReq.headers)) {
    if (!HOP_BY_HOP.has(k.toLowerCase())) baseHeaders[k] = v;
  }

  const anthObj = wire === 'anthropic' ? (safeJsonParse(reqBodyBuf.toString('utf8')) || {}) : null;

  let lastError = null; // {statusCode, headers, rawBuf} of the most recent failover
  let attemptNo = 0;
  let attemptedAny = false;

  for (let i = 0; i < candidates.length; i++) {
    const cand = candidates[i];
    const isLast = i === candidates.length - 1;

    let permit = { allowed: true, halfOpen: false };
    if (useBreaker) {
      permit = breakers.canRequest(cand.id);
      if (!permit.allowed) continue; // breaker OPEN: skip this provider
    }
    attemptedAny = true;

    let bodyBuf = reqBodyBuf;
    let rectifiedKind = null;
    let rectifyTried = false;

    // Inner loop runs the candidate at most twice: once normally, once more
    // after a rectification rewrite. The breaker permit is held across BOTH
    // sub-attempts and released exactly once at the candidate's terminal outcome.
    for (;;) {
      attemptNo += 1;
      const upstreamUrl = new URL(clientReq.url, cand.baseUrl);
      const mod = upstreamUrl.protocol === 'https:' ? https : http;
      const outHeaders = { ...baseHeaders, host: upstreamUrl.host };
      // Provider-specific key override: swap in this provider's credential in
      // the auth style its wire expects. Without an override we forward the
      // client's own header unchanged (transparent default).
      if (cand.apiKey) {
        if (wire === 'anthropic') {
          outHeaders['x-api-key'] = cand.apiKey;
          delete outHeaders['authorization'];
        } else {
          outHeaders['authorization'] = `Bearer ${cand.apiKey}`;
          delete outHeaders['x-api-key'];
        }
      }

      const exchange = makeExchange({ started, wire, method: clientReq.method, url: upstreamUrl.toString(), clientReq, bodyBuf, parser, config });
      exchange.resilience = { providerId: cand.id, attempt: attemptNo };
      if (rectifiedKind) exchange.resilience.rectified = rectifiedKind;

      // Opt-in request mutation (filters + tool-name) scoped to this provider.
      const sendBody = applyOutboundMutation(config, wire, cand.id, outHeaders, bodyBuf, exchange);
      if (sendBody.length > 0) outHeaders['content-length'] = String(sendBody.length);
      const agent = config.outbound ? config.outbound.agentFor(upstreamUrl) : undefined;

      const attempt = await transparentAttempt({ mod, upstreamUrl, method: clientReq.method, outHeaders, bodyBuf: sendBody, agent });

      if (attempt.kind === 'connError') {
        exchange.error = `upstream request error: ${attempt.error.message}`;
        exchange.durationMs = Date.now() - started;
        exchange.resilience.failedOver = !isLast;
        recorder.record(exchange);
        if (useBreaker) breakers.recordFailure(cand.id, permit.halfOpen);
        break; // try next candidate
      }

      const upstreamRes = attempt.res;
      const status = upstreamRes.statusCode || 0;

      if (status >= 200 && status < 300) {
        await streamUpstreamToClient(upstreamRes, clientRes, exchange, parser, config, recorder, started, wire);
        if (useBreaker) breakers.recordSuccess(cand.id, permit.halfOpen);
        return;
      }

      const drained = await drainResponse(upstreamRes, config);
      exchange.resStatus = status;
      exchange.resHeaders = upstreamRes.headers;
      exchange.error = errMessage(drained.obj) || `upstream ${status}`;
      try {
        exchange.response = parser.parseResponse(drained.text);
      } catch (err) {
        exchange.response = { text: '', toolCalls: [], stopReason: null, usage: null, raw: null, parseError: err.message };
      }

      // (a) rectifiable Anthropic error -> rewrite + retry SAME provider once.
      if (rectifyOn && !rectifyTried) {
        const kind = detectRectification(status, drained.obj, config.rectifier);
        if (kind) {
          rectifyTried = true;
          rectifiedKind = kind;
          exchange.resilience.rectifyTriggered = kind;
          exchange.durationMs = Date.now() - started;
          recorder.record(exchange); // log the pre-rectify failure (no breaker change)
          bodyBuf = Buffer.from(JSON.stringify(applyRectification(kind, anthObj)));
          continue; // retry same candidate with rectified body (permit still held)
        }
      }

      // (b) failover-eligible and another provider remains -> try next.
      if (failoverStatuses.has(status) && !isLast) {
        exchange.resilience.failedOver = true;
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        if (useBreaker) breakers.recordFailure(cand.id, permit.halfOpen);
        lastError = { statusCode: status, headers: upstreamRes.headers, rawBuf: drained.rawBuf };
        break;
      }

      // (c) terminal error -> commit to client as-is.
      {
        const headers = {};
        for (const [k, v] of Object.entries(upstreamRes.headers)) {
          if (!HOP_BY_HOP.has(k.toLowerCase())) headers[k] = v;
        }
        headers['content-length'] = String(drained.rawBuf.length);
        clientRes.writeHead(status, headers);
        clientRes.end(drained.rawBuf);
        exchange.durationMs = Date.now() - started;
        recorder.record(exchange);
        if (useBreaker) {
          // 5xx/429 reflect provider health; other 4xx are client/usage errors.
          if (failoverStatuses.has(status)) breakers.recordFailure(cand.id, permit.halfOpen);
          else breakers.recordNeutral(cand.id, permit.halfOpen);
        }
        return;
      }
    }
  }

  // All candidates exhausted (failed over or all breakers open). Replay the last
  // buffered error if we have one, else synthesize a 502.
  if (!clientRes.headersSent) {
    if (lastError) {
      const headers = {};
      for (const [k, v] of Object.entries(lastError.headers)) {
        if (!HOP_BY_HOP.has(k.toLowerCase())) headers[k] = v;
      }
      headers['content-length'] = String(lastError.rawBuf.length);
      clientRes.writeHead(lastError.statusCode, headers);
      clientRes.end(lastError.rawBuf);
    } else {
      const msg = attemptedAny ? 'all upstream providers failed' : 'all providers unavailable (circuit open)';
      const payload = wire === 'anthropic'
        ? { type: 'error', error: { type: 'proxy_error', message: msg } }
        : { error: { type: 'proxy_error', message: msg } };
      const buf = Buffer.from(JSON.stringify(payload));
      clientRes.writeHead(502, { 'content-type': 'application/json; charset=utf-8', 'content-length': String(buf.length) });
      clientRes.end(buf);
    }
  } else {
    clientRes.end();
  }
}
