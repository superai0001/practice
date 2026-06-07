// Share-link parsing -> normalized outbound spec (pure, zero-dependency).
//
// Turns common proxy share links into a kernel-agnostic descriptor that the
// xray / sing-box config renderers consume. Supported schemes:
//   vmess://      (v2rayN base64-JSON form)
//   vless://      (uuid@host:port?query#name)
//   trojan://     (password@host:port?query#name)
//   ss://         (SIP002: base64(method:pass)@host:port, or fully base64'd)
//   hysteria2://  (also hy2://) password@host:port?query#name
//   tuic://       (uuid:password@host:port?query#name)
//
// The returned spec shape (fields present depend on protocol):
//   {
//     type, tag, server, port,
//     uuid, alterId, security,           // vmess
//     flow,                              // vless
//     password,                          // trojan / ss / hysteria2 / tuic
//     method,                            // shadowsocks
//     obfs: { type, password },          // hysteria2
//     congestionControl, alpn: [],       // tuic
//     tls: { enabled, serverName, insecure, alpn, fingerprint,
//            reality: { publicKey, shortId } | null } | null,
//     transport: { type, path, host, serviceName, headers } | null,
//     name,
//   }

const ADVANCED_SCHEMES = new Set([
  'vmess', 'vless', 'trojan', 'ss', 'shadowsocks', 'hysteria2', 'hy2', 'tuic',
]);

// Is this a link that needs a kernel (vs. a plain http/socks proxy)?
export function isKernelScheme(raw) {
  if (!raw || typeof raw !== 'string') return false;
  const m = /^([a-z0-9]+):\/\//i.exec(raw.trim());
  return !!m && ADVANCED_SCHEMES.has(m[1].toLowerCase());
}

function b64decode(s) {
  let str = String(s).trim().replace(/-/g, '+').replace(/_/g, '/');
  while (str.length % 4) str += '=';
  return Buffer.from(str, 'base64').toString('utf8');
}

// Build the tls block from URL query params shared by vless/trojan/tuic/hy2.
function tlsFromQuery(q, fallbackSni) {
  const security = (q.get('security') || '').toLowerCase();
  const sni = q.get('sni') || q.get('peer') || fallbackSni || '';
  const alpn = q.get('alpn') ? q.get('alpn').split(',').map((s) => s.trim()).filter(Boolean) : [];
  const fp = q.get('fp') || '';
  const insecure = q.get('allowInsecure') === '1' || q.get('insecure') === '1' || q.get('allow_insecure') === '1';
  const isReality = security === 'reality';
  const enabled = security === 'tls' || security === 'xtls' || isReality;
  if (!enabled && !sni && !alpn.length && !fp && !insecure) return null;
  const tls = { enabled: enabled || isReality, serverName: sni, insecure, alpn, fingerprint: fp, reality: null };
  if (isReality) {
    tls.reality = {
      publicKey: q.get('pbk') || '',
      shortId: q.get('sid') || '',
      spiderX: q.get('spx') || '',
    };
  }
  return tls;
}

// Build the transport block from URL query params (vless/trojan v2ray style).
function transportFromQuery(q) {
  const type = (q.get('type') || 'tcp').toLowerCase();
  if (type === 'tcp' || type === '' || type === 'none' || type === 'raw') return null;
  const host = q.get('host') || '';
  const path = q.get('path') || '';
  const serviceName = q.get('serviceName') || q.get('servicename') || path;
  return {
    type: type === 'h2' ? 'http' : type,
    path,
    host,
    serviceName,
    headers: host ? { Host: host } : {},
  };
}

function parseVmess(raw) {
  // vmess://<base64 json>
  const body = raw.slice('vmess://'.length);
  let obj;
  try {
    obj = JSON.parse(b64decode(body));
  } catch {
    throw new Error('vmess: not a base64-encoded JSON link');
  }
  const net = (obj.net || 'tcp').toLowerCase();
  const tlsOn = String(obj.tls || '').toLowerCase() === 'tls';
  const host = obj.host || '';
  const transport = (net !== 'tcp' && net !== '')
    ? {
      type: net === 'h2' ? 'http' : net,
      path: obj.path || '',
      host,
      serviceName: obj.path || '',
      headers: host ? { Host: host } : {},
    }
    : null;
  return {
    type: 'vmess',
    tag: 'proxy',
    server: obj.add,
    port: Number.parseInt(obj.port, 10),
    uuid: obj.id,
    alterId: Number.parseInt(obj.aid || 0, 10) || 0,
    security: obj.scy || obj.security || 'auto',
    tls: tlsOn
      ? { enabled: true, serverName: obj.sni || host || '', insecure: false, alpn: obj.alpn ? String(obj.alpn).split(',').filter(Boolean) : [], fingerprint: obj.fp || '', reality: null }
      : null,
    transport,
    name: obj.ps || '',
  };
}

function parseUserHostLink(raw, type) {
  // Generic uuid|password @ host:port ?query #name
  const u = new URL(raw);
  const q = u.searchParams;
  const port = Number.parseInt(u.port, 10);
  const spec = {
    type,
    tag: 'proxy',
    server: decodeURIComponent(u.hostname),
    port,
    name: u.hash ? decodeURIComponent(u.hash.slice(1)) : '',
    tls: tlsFromQuery(q, u.hostname),
    transport: transportFromQuery(q),
  };
  if (type === 'vless') {
    spec.uuid = decodeURIComponent(u.username);
    spec.flow = q.get('flow') || '';
    spec.encryption = q.get('encryption') || 'none';
  } else if (type === 'trojan') {
    spec.password = decodeURIComponent(u.username);
  }
  return spec;
}

function parseShadowsocks(raw) {
  // SIP002: ss://base64(method:password)@host:port#name
  // Legacy:  ss://base64(method:password@host:port)#name
  const hashIdx = raw.indexOf('#');
  const name = hashIdx >= 0 ? decodeURIComponent(raw.slice(hashIdx + 1)) : '';
  let body = (hashIdx >= 0 ? raw.slice(0, hashIdx) : raw).slice('ss://'.length);
  const qIdx = body.indexOf('?');
  let query = '';
  if (qIdx >= 0) { query = body.slice(qIdx + 1); body = body.slice(0, qIdx); }

  let method; let password; let server; let port;
  if (body.includes('@')) {
    const at = body.lastIndexOf('@');
    const userinfo = body.slice(0, at);
    const hostport = body.slice(at + 1);
    const decoded = /[:]/.test(userinfo) && !/^[A-Za-z0-9+/_=-]+$/.test(userinfo)
      ? userinfo
      : b64decode(userinfo);
    const ci = decoded.indexOf(':');
    method = decoded.slice(0, ci);
    password = decoded.slice(ci + 1);
    const hp = hostport.lastIndexOf(':');
    server = hostport.slice(0, hp);
    port = Number.parseInt(hostport.slice(hp + 1), 10);
  } else {
    const decoded = b64decode(body);
    const at = decoded.lastIndexOf('@');
    const cred = decoded.slice(0, at);
    const hostport = decoded.slice(at + 1);
    const ci = cred.indexOf(':');
    method = cred.slice(0, ci);
    password = cred.slice(ci + 1);
    const hp = hostport.lastIndexOf(':');
    server = hostport.slice(0, hp);
    port = Number.parseInt(hostport.slice(hp + 1), 10);
  }
  const q = new URLSearchParams(query);
  return {
    type: 'shadowsocks',
    tag: 'proxy',
    server,
    port,
    method,
    password,
    plugin: q.get('plugin') || '',
    tls: null,
    transport: null,
    name,
  };
}

function parseHysteria2(raw) {
  const u = new URL(raw.replace(/^hy2:\/\//, 'hysteria2://'));
  const q = u.searchParams;
  const tls = tlsFromQuery(q, u.hostname) || { enabled: true, serverName: u.hostname, insecure: false, alpn: [], fingerprint: '', reality: null };
  tls.enabled = true;
  return {
    type: 'hysteria2',
    tag: 'proxy',
    server: decodeURIComponent(u.hostname),
    port: Number.parseInt(u.port, 10) || 443,
    password: decodeURIComponent(u.username || u.password || ''),
    obfs: q.get('obfs') ? { type: q.get('obfs'), password: q.get('obfs-password') || q.get('obfs_password') || '' } : null,
    tls,
    transport: null,
    name: u.hash ? decodeURIComponent(u.hash.slice(1)) : '',
  };
}

function parseTuic(raw) {
  const u = new URL(raw);
  const q = u.searchParams;
  const tls = tlsFromQuery(q, u.hostname) || { enabled: true, serverName: u.hostname, insecure: false, alpn: [], fingerprint: '', reality: null };
  tls.enabled = true;
  return {
    type: 'tuic',
    tag: 'proxy',
    server: decodeURIComponent(u.hostname),
    port: Number.parseInt(u.port, 10) || 443,
    uuid: decodeURIComponent(u.username || ''),
    password: decodeURIComponent(u.password || ''),
    congestionControl: q.get('congestion_control') || q.get('congestion') || '',
    udpRelayMode: q.get('udp_relay_mode') || '',
    alpn: q.get('alpn') ? q.get('alpn').split(',').map((s) => s.trim()).filter(Boolean) : [],
    tls,
    transport: null,
    name: u.hash ? decodeURIComponent(u.hash.slice(1)) : '',
  };
}

// Parse a share link into a normalized outbound spec. Throws on malformed input.
export function parseShareLink(raw) {
  if (!raw || typeof raw !== 'string') throw new Error('empty link');
  const link = raw.trim();
  const scheme = (/^([a-z0-9]+):\/\//i.exec(link) || [])[1];
  if (!scheme) throw new Error('link missing scheme');
  switch (scheme.toLowerCase()) {
    case 'vmess': return parseVmess(link);
    case 'vless': return parseUserHostLink(link, 'vless');
    case 'trojan': return parseUserHostLink(link, 'trojan');
    case 'ss':
    case 'shadowsocks': return parseShadowsocks(link);
    case 'hysteria2':
    case 'hy2': return parseHysteria2(link);
    case 'tuic': return parseTuic(link);
    default: throw new Error(`unsupported link scheme: ${scheme}`);
  }
}

// Protocols only sing-box can handle (xray-core has no native hy2/tuic outbound).
export const SINGBOX_ONLY = new Set(['hysteria2', 'tuic']);
