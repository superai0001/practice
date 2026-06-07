// Kernel manager: run xray-core / sing-box as a local child process that
// exposes a loopback SOCKS5 inbound, so the existing outbound SOCKS5 tunnel can
// egress through advanced protocols (VMess/VLESS/Trojan/Shadowsocks/Hysteria2/
// TUIC). Zero npm dependencies — we shell out to the official kernel binary.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseShareLink, isKernelScheme, SINGBOX_ONLY } from './links.js';
import { buildXrayConfig } from './xray.js';
import { buildSingboxConfig } from './singbox.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const IS_WIN = process.platform === 'win32';

function binCandidates(kind, explicit) {
  const exe = IS_WIN ? '.exe' : '';
  const names = kind === 'xray' ? [`xray${exe}`] : [`sing-box${exe}`];
  const out = [];
  if (explicit) out.push(explicit);
  // vendored next to the project (sibling "vendor/" dir), then bare name on PATH.
  for (const n of names) {
    out.push(path.join(__dirname, '..', '..', 'vendor', n));
    out.push(n);
  }
  return out;
}

// Resolve an executable: absolute/relative path that exists, else assume it is
// resolvable via PATH (spawn will surface ENOENT if not).
function resolveBin(candidates) {
  for (const c of candidates) {
    if (c.includes('/') || c.includes('\\')) {
      if (fs.existsSync(c)) return c;
    } else {
      return c; // bare name -> let the OS resolve via PATH
    }
  }
  return candidates[candidates.length - 1];
}

// Pick which kernel to use given the spec + explicit preference.
function chooseKernel(spec, preference) {
  if (preference === 'xray' || preference === 'sing-box') {
    if (preference === 'xray' && spec && SINGBOX_ONLY.has(spec.type)) {
      throw new Error(`xray-core has no native ${spec.type} outbound; use PROXY_KERNEL=sing-box`);
    }
    return preference;
  }
  // auto: hysteria2/tuic require sing-box; otherwise prefer xray.
  if (spec && SINGBOX_ONLY.has(spec.type)) return 'sing-box';
  return 'xray';
}

// Grab a free loopback TCP port (best-effort; small race window).
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

// Poll until a TCP connect to 127.0.0.1:port succeeds, or time out.
function waitForPort(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const sock = net.connect(port, '127.0.0.1');
      sock.once('connect', () => { sock.destroy(); resolve(); });
      sock.once('error', () => {
        sock.destroy();
        if (Date.now() > deadline) reject(new Error(`kernel SOCKS port ${port} not ready within ${timeoutMs}ms`));
        else setTimeout(attempt, 150);
      });
    };
    attempt();
  });
}

// Ensure a config object has a loopback socks inbound; return the port it uses.
// Used for PROXY_KERNEL_CONFIG (user-provided native config) of either kernel.
function ensureSocksInbound(cfg, kind, port) {
  if (!cfg || typeof cfg !== 'object') throw new Error('kernel config must be a JSON object');
  if (!Array.isArray(cfg.inbounds)) cfg.inbounds = [];
  if (kind === 'sing-box') {
    const existing = cfg.inbounds.find((i) => i && i.type === 'socks');
    if (existing) return existing.listen_port || port;
    cfg.inbounds.push({ type: 'socks', tag: 'cpl-socks-in', listen: '127.0.0.1', listen_port: port });
  } else {
    const existing = cfg.inbounds.find((i) => i && i.protocol === 'socks');
    if (existing) return existing.port || port;
    cfg.inbounds.push({ tag: 'cpl-socks-in', listen: '127.0.0.1', port, protocol: 'socks', settings: { auth: 'noauth', udp: true } });
  }
  return port;
}

// Create a kernel descriptor from options. Does not spawn — call start().
//   opts: { link?, configPath?, kernel?: 'auto'|'xray'|'sing-box',
//           xrayBin?, singboxBin?, socksPort?, readyTimeoutMs?, logger? }
export function createKernel(opts = {}) {
  const logger = opts.logger || ((line) => console.error(`[kernel] ${line}`));
  let spec = null;
  let nativeConfig = null;

  if (opts.configPath) {
    nativeConfig = JSON.parse(fs.readFileSync(opts.configPath, 'utf8'));
  } else if (opts.link) {
    spec = parseShareLink(opts.link);
  } else {
    throw new Error('createKernel: provide either { link } or { configPath }');
  }

  const kind = chooseKernel(spec, opts.kernel || 'auto');
  const explicit = kind === 'xray' ? opts.xrayBin : opts.singboxBin;
  const bin = resolveBin(binCandidates(kind, explicit));

  let child = null;
  let configFile = null;
  let socksPort = opts.socksPort || 0;
  let stopped = false;

  async function start() {
    if (!socksPort) socksPort = await freePort();
    let cfg;
    if (nativeConfig) {
      cfg = nativeConfig;
      socksPort = ensureSocksInbound(cfg, kind, socksPort);
    } else {
      cfg = kind === 'xray'
        ? buildXrayConfig(spec, socksPort)
        : buildSingboxConfig(spec, socksPort);
    }
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cpl-kernel-'));
    configFile = path.join(dir, kind === 'xray' ? 'xray.json' : 'sing-box.json');
    fs.writeFileSync(configFile, JSON.stringify(cfg, null, 2));

    // Both xray-core and sing-box use `run -c <config>`. If the configured
    // binary is a JS shim (path ends in .js/.mjs) run it through Node — handy
    // for custom wrappers and for testing without the Go binaries.
    const runArgs = ['run', '-c', configFile];
    const isJsShim = /\.(c|m)?js$/i.test(bin);
    const command = isJsShim ? process.execPath : bin;
    const args = isJsShim ? [bin, ...runArgs] : runArgs;
    child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (d) => String(d).split(/\r?\n/).filter(Boolean).forEach((l) => logger(l)));
    child.stderr.on('data', (d) => String(d).split(/\r?\n/).filter(Boolean).forEach((l) => logger(l)));
    child.on('exit', (code, signal) => {
      if (!stopped) logger(`${kind} exited unexpectedly (code=${code} signal=${signal})`);
    });

    const exitedEarly = new Promise((_, reject) => {
      child.once('exit', (code) => reject(new Error(`${kind} exited before becoming ready (code=${code}). Is the binary "${bin}" installed and the config valid?`)));
      child.once('error', (err) => reject(new Error(`failed to spawn ${kind} (${bin}): ${err.message}`)));
    });
    await Promise.race([waitForPort(socksPort, opts.readyTimeoutMs || 10000), exitedEarly]);
    logger(`${kind} ready: SOCKS5 127.0.0.1:${socksPort} -> ${describe()}`);
    return { socksPort };
  }

  function stop() {
    stopped = true;
    if (child && !child.killed) {
      try { child.kill(); } catch { /* ignore */ }
    }
    if (configFile) {
      try { fs.rmSync(path.dirname(configFile), { recursive: true, force: true }); } catch { /* ignore */ }
    }
  }

  function describe() {
    if (spec) {
      const via = spec.tls && spec.tls.reality ? '+reality' : (spec.tls && spec.tls.enabled ? '+tls' : '');
      const net = spec.transport ? `/${spec.transport.type}` : '';
      return `${kind}:${spec.type}${net}${via} ${spec.server}:${spec.port}`;
    }
    return `${kind}:config(${path.basename(opts.configPath || '')})`;
  }

  return {
    kind,
    bin,
    get socksPort() { return socksPort; },
    get spec() { return spec; },
    start,
    stop,
    describe,
  };
}

export { isKernelScheme };
