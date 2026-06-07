// Outbound proxy (出站代理) — opt-in, zero-dependency.
//
// Routes the proxy's UPSTREAM connections through an external HTTP/HTTPS or
// SOCKS5 proxy (common on locked-down intranets that only allow egress via a
// corporate proxy). Configure with a single URL, e.g.
//   UPSTREAM_PROXY=http://user:pass@proxy.corp:8080
//   UPSTREAM_PROXY=socks5://10.0.0.1:1080
// When unset, connections are made directly (default, unchanged behavior).
//
// Implementation: we expose a custom http(s).Agent whose createConnection()
// establishes the tunnel itself — an HTTP CONNECT for http/https proxies, or a
// SOCKS5 handshake for socks proxies — then, for https upstreams, performs the
// TLS handshake on top of the tunneled socket. No third-party packages.

import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import tls from 'node:tls';
import { createKernel, isKernelScheme } from './kernel/index.js';

// Parse a proxy URL into a normalized descriptor, or null when nothing/invalid.
// Supported protocols: http, https, socks/socks5/socks5h (all SOCKS5).
export function parseProxyUrl(raw) {
  if (!raw || typeof raw !== 'string' || raw.trim() === '') return null;
  let u;
  try {
    u = new URL(raw.trim());
  } catch {
    return null;
  }
  const scheme = u.protocol.replace(':', '').toLowerCase();
  let kind;
  if (scheme === 'http') kind = 'http';
  else if (scheme === 'https') kind = 'https';
  else if (scheme === 'socks' || scheme === 'socks5' || scheme === 'socks5h') kind = 'socks5';
  else return null;
  const defaultPort = kind === 'http' ? 80 : kind === 'https' ? 443 : 1080;
  return {
    kind,
    hostname: u.hostname,
    port: u.port ? Number.parseInt(u.port, 10) : defaultPort,
    username: u.username ? decodeURIComponent(u.username) : '',
    password: u.password ? decodeURIComponent(u.password) : '',
  };
}

// --- HTTP CONNECT tunnel -----------------------------------------------------
function httpConnect(proxy, host, port, cb) {
  const mod = proxy.kind === 'https' ? https : http;
  const headers = {};
  if (proxy.username || proxy.password) {
    const token = Buffer.from(`${proxy.username}:${proxy.password}`).toString('base64');
    headers['Proxy-Authorization'] = `Basic ${token}`;
  }
  const req = mod.request({
    host: proxy.hostname,
    port: proxy.port,
    method: 'CONNECT',
    path: `${host}:${port}`,
    headers,
    // The CONNECT request to the proxy itself; for an https proxy we don't
    // verify its cert chain against the target name (it's the proxy, not target).
    rejectUnauthorized: false,
  });
  req.once('connect', (res, socket) => {
    if (res.statusCode !== 200) {
      socket.destroy();
      cb(new Error(`proxy CONNECT failed: ${res.statusCode}`));
      return;
    }
    cb(null, socket);
  });
  req.once('error', cb);
  req.end();
}

// --- SOCKS5 handshake --------------------------------------------------------
// A tiny incremental reader so we can consume exact byte counts from the socket.
function makeReader(socket) {
  let buf = Buffer.alloc(0);
  const waiters = [];
  const pump = () => {
    while (waiters.length && buf.length >= waiters[0].n) {
      const w = waiters.shift();
      const out = buf.subarray(0, w.n);
      buf = buf.subarray(w.n);
      w.resolve(out);
    }
  };
  socket.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    pump();
  });
  return (n) => new Promise((resolve, reject) => {
    waiters.push({ n, resolve, reject });
    pump();
  });
}

function socks5Connect(proxy, host, port, cb) {
  const socket = net.connect(proxy.port, proxy.hostname);
  let done = false;
  const fail = (err) => {
    if (done) return;
    done = true;
    socket.destroy();
    cb(err);
  };
  socket.once('error', fail);
  socket.once('connect', async () => {
    try {
      const read = makeReader(socket);
      const useAuth = !!(proxy.username || proxy.password);
      // Greeting: VER=5, methods = [no-auth] (+ user/pass when creds given).
      socket.write(Buffer.from(useAuth ? [0x05, 0x02, 0x00, 0x02] : [0x05, 0x01, 0x00]));
      const greet = await read(2);
      if (greet[0] !== 0x05) throw new Error('socks5: bad version in greeting');
      const method = greet[1];
      if (method === 0xff) throw new Error('socks5: no acceptable auth method');
      if (method === 0x02) {
        const u = Buffer.from(proxy.username, 'utf8');
        const p = Buffer.from(proxy.password, 'utf8');
        socket.write(Buffer.concat([Buffer.from([0x01, u.length]), u, Buffer.from([p.length]), p]));
        const authRes = await read(2);
        if (authRes[1] !== 0x00) throw new Error('socks5: authentication failed');
      } else if (method !== 0x00) {
        throw new Error(`socks5: unsupported auth method ${method}`);
      }
      // CONNECT command with a domain-name target (ATYP=3) — lets the proxy
      // resolve DNS (socks5h semantics), which is what intranet setups want.
      const hostBuf = Buffer.from(host, 'utf8');
      const reqBuf = Buffer.concat([
        Buffer.from([0x05, 0x01, 0x00, 0x03, hostBuf.length]),
        hostBuf,
        Buffer.from([(port >> 8) & 0xff, port & 0xff]),
      ]);
      socket.write(reqBuf);
      const head = await read(4);
      if (head[1] !== 0x00) throw new Error(`socks5: connect failed (reply ${head[1]})`);
      const atyp = head[3];
      if (atyp === 0x01) await read(4);
      else if (atyp === 0x04) await read(16);
      else if (atyp === 0x03) {
        const len = (await read(1))[0];
        await read(len);
      } else throw new Error('socks5: bad ATYP in reply');
      await read(2); // BND.PORT
      done = true;
      socket.removeListener('error', fail);
      cb(null, socket);
    } catch (err) {
      fail(err);
    }
  });
}

function rawConnect(proxy, host, port, cb) {
  if (proxy.kind === 'socks5') socks5Connect(proxy, host, port, cb);
  else httpConnect(proxy, host, port, cb);
}

// Build the http/https tunnel agents for a (possibly deferred) proxy descriptor.
//   getProxy()  -> the current proxy descriptor (null until ready)
//   whenReady   -> resolves once getProxy() returns a usable descriptor
function buildAgents(getProxy, whenReady) {
  // Resolve readiness then perform the tunnel connect.
  function connectVia(host, port, cb) {
    whenReady.then(() => {
      const proxy = getProxy();
      if (!proxy) { cb(new Error('outbound proxy unavailable')); return; }
      rawConnect(proxy, host, port, cb);
    }, cb);
  }

  // http upstream: hand back the tunneled raw socket as-is.
  class HttpTunnelAgent extends http.Agent {
    createConnection(options, callback) {
      const host = options.host || options.hostname;
      const port = Number(options.port) || 80;
      connectVia(host, port, callback);
    }
  }
  // https upstream: wrap the tunneled socket in TLS for the target host.
  class HttpsTunnelAgent extends https.Agent {
    createConnection(options, callback) {
      const host = options.host || options.hostname;
      const port = Number(options.port) || 443;
      connectVia(host, port, (err, socket) => {
        if (err) {
          callback(err);
          return;
        }
        const tlsSock = tls.connect({
          socket,
          servername: options.servername || host,
          rejectUnauthorized: options.rejectUnauthorized !== false,
        });
        tlsSock.once('error', callback);
        tlsSock.once('secureConnect', () => {
          tlsSock.removeListener('error', callback);
          callback(null, tlsSock);
        });
      });
    }
  }

  const httpAgent = new HttpTunnelAgent({ keepAlive: false });
  const httpsAgent = new HttpsTunnelAgent({ keepAlive: false });
  return (upstreamUrl) => {
    const isHttps = String(upstreamUrl.protocol || upstreamUrl).includes('https');
    return isHttps ? httpsAgent : httpAgent;
  };
}

// Build the outbound proxy. Returns null when nothing is configured.
//
//   raw         a proxy/share URL: http/https/socks5 (direct, no kernel) OR an
//               advanced share link (vmess/vless/trojan/ss/hysteria2/tuic)
//               which is routed through a local xray/sing-box kernel.
//   kernelOpts  { kernel, configPath, xrayBin, singboxBin, socksPort } — when
//               configPath is set, the kernel is used regardless of `raw`.
export function createOutbound(raw, kernelOpts = {}) {
  const useKernel = isKernelScheme(raw) || !!(kernelOpts && kernelOpts.configPath);

  // --- direct path (today's behavior, unchanged) ---------------------------
  if (!useKernel) {
    const proxy = parseProxyUrl(raw);
    if (!proxy) return null;
    return {
      proxy,
      kernel: null,
      whenReady: Promise.resolve(),
      describe: `${proxy.kind}://${proxy.hostname}:${proxy.port}${proxy.username ? ' (auth)' : ''}`,
      agentFor: buildAgents(() => proxy, Promise.resolve()),
      stop() {},
    };
  }

  // --- kernel path (advanced protocols via xray/sing-box) ------------------
  const kernel = createKernel({
    link: isKernelScheme(raw) ? raw : undefined,
    configPath: kernelOpts.configPath,
    kernel: kernelOpts.kernel,
    xrayBin: kernelOpts.xrayBin,
    singboxBin: kernelOpts.singboxBin,
    socksPort: kernelOpts.socksPort,
  });
  let proxy = null; // socks5 -> local kernel inbound, set once ready
  const whenReady = kernel.start().then(({ socksPort }) => {
    proxy = { kind: 'socks5', hostname: '127.0.0.1', port: socksPort, username: '', password: '' };
  });
  // Surface a clear error rather than an unhandled rejection if the kernel dies.
  whenReady.catch((err) => console.error(`[kernel] startup failed: ${err.message}`));

  return {
    get proxy() { return proxy; },
    kernel,
    whenReady,
    describe: `kernel ${kernel.describe()}`,
    agentFor: buildAgents(() => proxy, whenReady),
    stop() { kernel.stop(); },
  };
}
