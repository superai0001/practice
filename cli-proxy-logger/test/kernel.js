// Tests for the xray/sing-box kernel integration (advanced outbound protocols):
//   1. share-link parsing -> normalized outbound spec (unit)
//   2. spec -> xray / sing-box config JSON rendering (unit)
//   3. kernel manager spawn + readiness + e2e tunnel through a fake kernel
// The integration test uses test/fixtures/fake-kernel.js (a JS SOCKS5 shim) in
// place of the real Go binaries, so no Go toolchain is required.

import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../src/config.js';
import { Recorder } from '../src/recorder.js';
import { startProxy } from '../src/proxy.js';
import { parseShareLink, isKernelScheme, SINGBOX_ONLY } from '../src/kernel/links.js';
import { buildXrayConfig } from '../src/kernel/xray.js';
import { buildSingboxConfig } from '../src/kernel/singbox.js';
import { createKernel } from '../src/kernel/index.js';
import { createOutbound } from '../src/outbound.js';

let pass = 0;
const check = (name, cond) => { assert.ok(cond, name); console.log('  ok -', name); pass++; };
const eq = (name, a, b) => check(`${name} (got ${JSON.stringify(a)})`, JSON.stringify(a) === JSON.stringify(b));

const FAKE_KERNEL = fileURLToPath(new URL('./fixtures/fake-kernel.js', import.meta.url));
const logDir = fileURLToPath(new URL('./.tmp-logs', import.meta.url));

function makeServer(handler) {
  return new Promise((resolve) => {
    const requests = [];
    const srv = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => { requests.push({ url: req.url }); handler(req, res); });
    });
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port, url: `http://127.0.0.1:${srv.address().port}`, requests }));
  });
}

function post(port, p, bodyObj) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(bodyObj);
    const req = http.request({ host: '127.0.0.1', port, path: p, method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    req.write(body); req.end();
  });
}

function startTestProxy(overrides) {
  const config = loadConfig({ proxyPort: 0, uiPort: 0, logDir, ...overrides });
  const recorder = new Recorder(config);
  const server = startProxy(config, recorder);
  return new Promise((resolve) => {
    const t = setInterval(() => { if (server.address()) { clearInterval(t); resolve({ server, port: server.address().port }); } }, 5);
  });
}

async function run() {
  console.log('kernel: scheme detection (unit)');
  {
    check('vmess is kernel scheme', isKernelScheme('vmess://abc'));
    check('vless is kernel scheme', isKernelScheme('vless://u@h:1'));
    check('hy2 alias is kernel scheme', isKernelScheme('hy2://p@h:1'));
    check('http is NOT kernel scheme', !isKernelScheme('http://h:8080'));
    check('socks5 is NOT kernel scheme', !isKernelScheme('socks5://h:1080'));
    check('empty is NOT kernel scheme', !isKernelScheme(''));
    check('hysteria2/tuic are sing-box only', SINGBOX_ONLY.has('hysteria2') && SINGBOX_ONLY.has('tuic'));
  }

  console.log('kernel: share-link parsing (unit)');
  {
    const vmessLink = `vmess://${Buffer.from(JSON.stringify({ add: 'example.com', port: '443', id: 'uuid-1', aid: '0', scy: 'auto', net: 'ws', host: 'cdn.example.com', path: '/ray', tls: 'tls', sni: 'example.com', ps: 'node-a' })).toString('base64')}`;
    const v = parseShareLink(vmessLink);
    eq('vmess server/port', [v.server, v.port], ['example.com', 443]);
    eq('vmess uuid/security', [v.uuid, v.security], ['uuid-1', 'auto']);
    eq('vmess ws transport', [v.transport.type, v.transport.path, v.transport.host], ['ws', '/ray', 'cdn.example.com']);
    eq('vmess tls sni', [v.tls.enabled, v.tls.serverName], [true, 'example.com']);

    const vl = parseShareLink('vless://uuid-2@host.net:8443?encryption=none&security=tls&sni=h.net&type=grpc&serviceName=gs&flow=xtls-rprx-vision#vl');
    eq('vless uuid/flow', [vl.uuid, vl.flow], ['uuid-2', 'xtls-rprx-vision']);
    eq('vless grpc service', [vl.transport.type, vl.transport.serviceName], ['grpc', 'gs']);
    eq('vless tls', [vl.tls.enabled, vl.tls.serverName], [true, 'h.net']);

    const vr = parseShareLink('vless://uuid-3@h.net:443?security=reality&pbk=PUBKEY&sid=ab12&sni=www.apple.com&fp=chrome&type=tcp#r');
    eq('vless reality keys', [vr.tls.reality.publicKey, vr.tls.reality.shortId], ['PUBKEY', 'ab12']);

    const tj = parseShareLink('trojan://secret@h.net:443?security=tls&sni=h.net#t');
    eq('trojan pass/server', [tj.password, tj.server, tj.port], ['secret', 'h.net', 443]);

    const ss = parseShareLink(`ss://${Buffer.from('aes-256-gcm:pw123').toString('base64')}@1.2.3.4:8388#s`);
    eq('ss sip002 method/pass', [ss.method, ss.password], ['aes-256-gcm', 'pw123']);
    eq('ss sip002 host/port', [ss.server, ss.port], ['1.2.3.4', 8388]);
    const ssLegacy = parseShareLink(`ss://${Buffer.from('chacha20-ietf-poly1305:pw@5.6.7.8:9999').toString('base64')}#s2`);
    eq('ss legacy method/host', [ssLegacy.method, ssLegacy.server, ssLegacy.port], ['chacha20-ietf-poly1305', '5.6.7.8', 9999]);

    const hy = parseShareLink('hysteria2://pw@h.net:8443?sni=h.net&insecure=1&obfs=salamander&obfs-password=op#h');
    eq('hy2 pass/obfs', [hy.password, hy.obfs.type, hy.obfs.password], ['pw', 'salamander', 'op']);
    eq('hy2 tls insecure', [hy.tls.enabled, hy.tls.insecure], [true, true]);
    const hy2alias = parseShareLink('hy2://pw@h.net:443#h2');
    eq('hy2 alias parsed', hy2alias.type, 'hysteria2');

    const tu = parseShareLink('tuic://uuid-9:pw@h.net:443?congestion_control=bbr&alpn=h3&sni=h.net#u');
    eq('tuic uuid/pass/cc', [tu.uuid, tu.password, tu.congestionControl], ['uuid-9', 'pw', 'bbr']);

    let threw = false;
    try { parseShareLink('ftp://nope'); } catch { threw = true; }
    check('unsupported scheme throws', threw);
  }

  console.log('kernel: xray config rendering (unit)');
  {
    const v = parseShareLink('vless://u@h.net:443?encryption=none&security=tls&sni=h.net&type=ws&path=/p&host=cdn#x');
    const cfg = buildXrayConfig(v, 10800);
    eq('xray socks inbound', [cfg.inbounds[0].protocol, cfg.inbounds[0].port, cfg.inbounds[0].listen], ['socks', 10800, '127.0.0.1']);
    const out = cfg.outbounds[0];
    eq('xray vless outbound', [out.protocol, out.settings.vnext[0].address, out.settings.vnext[0].users[0].id], ['vless', 'h.net', 'u']);
    eq('xray stream tls+ws', [out.streamSettings.security, out.streamSettings.network, out.streamSettings.wsSettings.path], ['tls', 'ws', '/p']);
    check('xray has freedom direct', cfg.outbounds.some((o) => o.protocol === 'freedom'));

    const vm = buildXrayConfig(parseShareLink(`vmess://${Buffer.from(JSON.stringify({ add: 'a', port: 443, id: 'id', aid: 2, net: 'tcp' })).toString('base64')}`), 10801);
    eq('xray vmess vnext user', [vm.outbounds[0].settings.vnext[0].users[0].id, vm.outbounds[0].settings.vnext[0].users[0].alterId], ['id', 2]);

    const ssCfg = buildXrayConfig(parseShareLink(`ss://${Buffer.from('aes-256-gcm:pw').toString('base64')}@1.1.1.1:80#s`), 10802);
    eq('xray ss server', [ssCfg.outbounds[0].protocol, ssCfg.outbounds[0].settings.servers[0].method], ['shadowsocks', 'aes-256-gcm']);
  }

  console.log('kernel: sing-box config rendering (unit)');
  {
    const v = parseShareLink('vless://u@h.net:443?encryption=none&security=tls&sni=h.net&type=ws&path=/p&host=cdn#x');
    const cfg = buildSingboxConfig(v, 10810);
    eq('sb socks inbound', [cfg.inbounds[0].type, cfg.inbounds[0].listen_port], ['socks', 10810]);
    const out = cfg.outbounds[0];
    eq('sb vless outbound', [out.type, out.server, out.server_port, out.uuid], ['vless', 'h.net', 443, 'u']);
    eq('sb tls+ws', [out.tls.enabled, out.tls.server_name, out.transport.type, out.transport.path], [true, 'h.net', 'ws', '/p']);

    const hy = buildSingboxConfig(parseShareLink('hysteria2://pw@h.net:8443?sni=h.net&obfs=salamander&obfs-password=op#h'), 10811);
    eq('sb hy2 outbound', [hy.outbounds[0].type, hy.outbounds[0].password, hy.outbounds[0].obfs.type], ['hysteria2', 'pw', 'salamander']);

    const tu = buildSingboxConfig(parseShareLink('tuic://uuid:pw@h.net:443?congestion_control=bbr&sni=h.net#u'), 10812);
    eq('sb tuic outbound', [tu.outbounds[0].type, tu.outbounds[0].uuid, tu.outbounds[0].congestion_control], ['tuic', 'uuid', 'bbr']);
  }

  console.log('kernel: selection rules (unit)');
  {
    const k1 = createKernel({ link: 'hysteria2://pw@h:443', kernel: 'auto', singboxBin: FAKE_KERNEL });
    eq('auto picks sing-box for hy2', k1.kind, 'sing-box');
    const k2 = createKernel({ link: 'vmess://' + Buffer.from(JSON.stringify({ add: 'a', port: 1, id: 'x', net: 'tcp' })).toString('base64'), kernel: 'auto' });
    eq('auto picks xray for vmess', k2.kind, 'xray');
    let threw = false;
    try { createKernel({ link: 'tuic://u:p@h:443', kernel: 'xray' }); } catch { threw = true; }
    check('xray + tuic rejected', threw);
  }

  console.log('kernel: native config injects socks inbound (unit, sing-box)');
  {
    const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'cpl-native-')), 'sb.json');
    fs.writeFileSync(tmp, JSON.stringify({ outbounds: [{ type: 'vmess', tag: 'proxy', server: 'h', server_port: 443, uuid: 'x', security: 'auto' }] }));
    const k = createKernel({ configPath: tmp, kernel: 'sing-box', singboxBin: FAKE_KERNEL });
    eq('native config uses provided kernel', k.kind, 'sing-box');
  }

  // =========================================================================
  console.log('kernel: e2e tunnel through fake kernel (vless via sing-box shim)');
  {
    const up = await makeServer((req, res) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ ok: true })); });
    const seenFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'cpl-seen-')), 'seen.txt');
    // The fake kernel just runs a SOCKS5 server (ignoring the outbound spec) so
    // we can verify the full createOutbound -> kernel -> socks -> upstream chain.
    process.env.FAKE_KERNEL_SEEN = seenFile;
    const link = 'vless://u@127.0.0.1:1?encryption=none#e2e';
    const outbound = createOutbound(link, { kernel: 'sing-box', singboxBin: FAKE_KERNEL });
    await outbound.whenReady;
    check('kernel became ready (socks port assigned)', outbound.proxy && outbound.proxy.kind === 'socks5' && outbound.proxy.port > 0);

    const { server, port } = await startTestProxy({ upstream: { anthropic: up.url, openai: up.url }, outbound });
    const r = await post(port, '/v1/messages', { model: 'claude', messages: [] });
    check('request succeeded through kernel socks tunnel', r.status === 200);
    check('upstream received the request', up.requests.length === 1);
    const seen = fs.existsSync(seenFile) ? fs.readFileSync(seenFile, 'utf8') : '';
    check('fake kernel tunneled to upstream host:port', seen.includes(`:${up.port}`));

    server.close(); up.srv.close(); outbound.stop();
    delete process.env.FAKE_KERNEL_SEEN;
  }

  console.log('kernel: startup fails fast when binary is missing');
  {
    const outbound = createOutbound('vmess://' + Buffer.from(JSON.stringify({ add: 'h', port: 1, id: 'x', net: 'tcp' })).toString('base64'), { kernel: 'xray', xrayBin: '/nonexistent/xray-binary-xyz' });
    let failed = false;
    try { await outbound.whenReady; } catch { failed = true; }
    check('whenReady rejects on missing/failed binary', failed);
    outbound.stop();
  }

  console.log(`\nkernel: ${pass} checks passed`);
}

run().catch((e) => { console.error(e); process.exit(1); });
