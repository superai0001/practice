// Render a normalized outbound spec -> Xray-core config JSON.
//
// Produces a minimal config with a single SOCKS inbound on loopback and one
// proxy outbound for the requested protocol. Xray-core has no native
// Hysteria2 / TUIC outbound; those must use sing-box (see SINGBOX_ONLY).

function streamSettings(spec) {
  const tls = spec.tls;
  const tr = spec.transport;
  const network = tr ? tr.type : 'tcp';
  if (!tls && (!tr || network === 'tcp')) return undefined;

  const ss = { network: network === 'http' ? 'h2' : network };
  if (tls) {
    if (tls.reality) {
      ss.security = 'reality';
      ss.realitySettings = {
        serverName: tls.serverName || '',
        publicKey: tls.reality.publicKey || '',
        shortId: tls.reality.shortId || '',
        spiderX: tls.reality.spiderX || '',
        fingerprint: tls.fingerprint || 'chrome',
      };
    } else {
      ss.security = 'tls';
      ss.tlsSettings = {
        serverName: tls.serverName || '',
        allowInsecure: !!tls.insecure,
      };
      if (tls.alpn && tls.alpn.length) ss.tlsSettings.alpn = tls.alpn;
      if (tls.fingerprint) ss.tlsSettings.fingerprint = tls.fingerprint;
    }
  }
  if (tr) {
    if (network === 'ws') {
      // xray prefers an independent `host` field over a Host header.
      ss.wsSettings = { path: tr.path || '/', ...(tr.host ? { host: tr.host } : {}) };
    } else if (network === 'grpc') {
      ss.grpcSettings = { serviceName: tr.serviceName || '' };
    } else if (network === 'http') {
      ss.httpSettings = { path: tr.path || '/', host: tr.host ? [tr.host] : [] };
    } else if (network === 'httpupgrade') {
      ss.httpupgradeSettings = { path: tr.path || '/', host: tr.host || '' };
    }
  }
  return ss;
}

function outboundSettings(spec) {
  switch (spec.type) {
    case 'vmess':
      return {
        protocol: 'vmess',
        settings: {
          vnext: [{
            address: spec.server,
            port: spec.port,
            users: [{ id: spec.uuid, alterId: spec.alterId || 0, security: spec.security || 'auto' }],
          }],
        },
      };
    case 'vless':
      return {
        protocol: 'vless',
        settings: {
          vnext: [{
            address: spec.server,
            port: spec.port,
            users: [{ id: spec.uuid, encryption: spec.encryption || 'none', ...(spec.flow ? { flow: spec.flow } : {}) }],
          }],
        },
      };
    case 'trojan':
      return {
        protocol: 'trojan',
        settings: { servers: [{ address: spec.server, port: spec.port, password: spec.password, ...(spec.flow ? { flow: spec.flow } : {}) }] },
      };
    case 'shadowsocks':
      return {
        protocol: 'shadowsocks',
        settings: { servers: [{ address: spec.server, port: spec.port, method: spec.method, password: spec.password }] },
      };
    default:
      throw new Error(`xray: unsupported outbound protocol: ${spec.type}`);
  }
}

// Build a full Xray config. socksPort is the loopback SOCKS5 inbound port.
export function buildXrayConfig(spec, socksPort, { listen = '127.0.0.1' } = {}) {
  const out = { tag: 'proxy', ...outboundSettings(spec) };
  const ss = streamSettings(spec);
  if (ss) out.streamSettings = ss;
  return {
    log: { loglevel: 'warning' },
    inbounds: [{
      tag: 'socks-in',
      listen,
      port: socksPort,
      protocol: 'socks',
      settings: { auth: 'noauth', udp: true },
    }],
    outbounds: [out, { tag: 'direct', protocol: 'freedom' }],
  };
}
