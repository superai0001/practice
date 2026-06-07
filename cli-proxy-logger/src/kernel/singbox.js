// Render a normalized outbound spec -> sing-box config JSON.
//
// Produces a minimal config with a single SOCKS inbound on loopback and one
// proxy outbound for the requested protocol. sing-box additionally supports
// Hysteria2 and TUIC (which xray-core does not).

function tlsBlock(spec) {
  const tls = spec.tls;
  if (!tls || !tls.enabled) return undefined;
  const out = { enabled: true };
  if (tls.serverName) out.server_name = tls.serverName;
  if (tls.insecure) out.insecure = true;
  if (tls.alpn && tls.alpn.length) out.alpn = tls.alpn;
  if (tls.fingerprint) out.utls = { enabled: true, fingerprint: tls.fingerprint };
  if (tls.reality) {
    out.reality = {
      enabled: true,
      public_key: tls.reality.publicKey || '',
      short_id: tls.reality.shortId || '',
    };
    // REALITY relies on uTLS fingerprint mimicry.
    if (!out.utls) out.utls = { enabled: true, fingerprint: tls.fingerprint || 'chrome' };
  }
  return out;
}

function transportBlock(spec) {
  const tr = spec.transport;
  if (!tr || tr.type === 'tcp') return undefined;
  if (tr.type === 'ws') {
    const t = { type: 'ws', path: tr.path || '/' };
    if (tr.host) t.headers = { Host: tr.host };
    return t;
  }
  if (tr.type === 'grpc') return { type: 'grpc', service_name: tr.serviceName || '' };
  if (tr.type === 'http') {
    const t = { type: 'http', path: tr.path || '/' };
    if (tr.host) t.host = [tr.host];
    return t;
  }
  if (tr.type === 'httpupgrade') {
    const t = { type: 'httpupgrade', path: tr.path || '/' };
    if (tr.host) t.host = tr.host;
    return t;
  }
  return undefined;
}

function outboundFor(spec) {
  const base = { type: spec.type, tag: 'proxy', server: spec.server, server_port: spec.port };
  const tls = tlsBlock(spec);
  const transport = transportBlock(spec);
  switch (spec.type) {
    case 'vmess':
      return {
        ...base,
        uuid: spec.uuid,
        security: spec.security || 'auto',
        ...(spec.alterId ? { alter_id: spec.alterId } : {}),
        ...(tls ? { tls } : {}),
        ...(transport ? { transport } : {}),
      };
    case 'vless':
      return {
        ...base,
        uuid: spec.uuid,
        ...(spec.flow ? { flow: spec.flow } : {}),
        ...(tls ? { tls } : {}),
        ...(transport ? { transport } : {}),
      };
    case 'trojan':
      return {
        ...base,
        password: spec.password,
        ...(tls ? { tls } : {}),
        ...(transport ? { transport } : {}),
      };
    case 'shadowsocks':
      return { ...base, method: spec.method, password: spec.password };
    case 'hysteria2':
      return {
        ...base,
        password: spec.password,
        ...(spec.obfs ? { obfs: { type: spec.obfs.type, password: spec.obfs.password } } : {}),
        ...(tls ? { tls } : { tls: { enabled: true, server_name: spec.server } }),
      };
    case 'tuic':
      return {
        ...base,
        uuid: spec.uuid,
        password: spec.password,
        ...(spec.congestionControl ? { congestion_control: spec.congestionControl } : {}),
        ...(spec.udpRelayMode ? { udp_relay_mode: spec.udpRelayMode } : {}),
        ...(tls ? { tls } : { tls: { enabled: true, server_name: spec.server } }),
      };
    default:
      throw new Error(`sing-box: unsupported outbound protocol: ${spec.type}`);
  }
}

// Build a full sing-box config. socksPort is the loopback SOCKS5 inbound port.
export function buildSingboxConfig(spec, socksPort, { listen = '127.0.0.1' } = {}) {
  return {
    log: { level: 'warn' },
    inbounds: [{
      type: 'socks',
      tag: 'socks-in',
      listen,
      listen_port: socksPort,
    }],
    outbounds: [outboundFor(spec), { type: 'direct', tag: 'direct' }],
  };
}
