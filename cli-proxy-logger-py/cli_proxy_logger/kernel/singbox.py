"""Render a normalized outbound spec -> sing-box config dict.

Port of the Node ``src/kernel/singbox.js``. Produces a minimal config with a
single SOCKS inbound on loopback and one proxy outbound for the requested
protocol. sing-box additionally supports Hysteria2 and TUIC (xray does not).
"""


def _tls_block(spec):
    tls = spec.get("tls")
    if not tls or not tls.get("enabled"):
        return None
    out = {"enabled": True}
    if tls.get("serverName"):
        out["server_name"] = tls["serverName"]
    if tls.get("insecure"):
        out["insecure"] = True
    if tls.get("alpn"):
        out["alpn"] = tls["alpn"]
    if tls.get("fingerprint"):
        out["utls"] = {"enabled": True, "fingerprint": tls["fingerprint"]}
    if tls.get("reality"):
        out["reality"] = {
            "enabled": True,
            "public_key": tls["reality"].get("publicKey") or "",
            "short_id": tls["reality"].get("shortId") or "",
        }
        if "utls" not in out:
            out["utls"] = {"enabled": True, "fingerprint": tls.get("fingerprint") or "chrome"}
    return out


def _transport_block(spec):
    tr = spec.get("transport")
    if not tr or tr["type"] == "tcp":
        return None
    if tr["type"] == "ws":
        t = {"type": "ws", "path": tr.get("path") or "/"}
        if tr.get("host"):
            t["headers"] = {"Host": tr["host"]}
        return t
    if tr["type"] == "grpc":
        return {"type": "grpc", "service_name": tr.get("serviceName") or ""}
    if tr["type"] == "http":
        t = {"type": "http", "path": tr.get("path") or "/"}
        if tr.get("host"):
            t["host"] = [tr["host"]]
        return t
    if tr["type"] == "httpupgrade":
        t = {"type": "httpupgrade", "path": tr.get("path") or "/"}
        if tr.get("host"):
            t["host"] = tr["host"]
        return t
    return None


def _outbound_for(spec):
    t = spec["type"]
    base = {"type": t, "tag": "proxy", "server": spec["server"],
            "server_port": spec["port"]}
    tls = _tls_block(spec)
    transport = _transport_block(spec)
    if t == "vmess":
        out = {**base, "uuid": spec["uuid"], "security": spec.get("security") or "auto"}
        if spec.get("alterId"):
            out["alter_id"] = spec["alterId"]
        if tls:
            out["tls"] = tls
        if transport:
            out["transport"] = transport
        return out
    if t == "vless":
        out = {**base, "uuid": spec["uuid"]}
        if spec.get("flow"):
            out["flow"] = spec["flow"]
        if tls:
            out["tls"] = tls
        if transport:
            out["transport"] = transport
        return out
    if t == "trojan":
        out = {**base, "password": spec.get("password")}
        if tls:
            out["tls"] = tls
        if transport:
            out["transport"] = transport
        return out
    if t == "shadowsocks":
        return {**base, "method": spec.get("method"), "password": spec.get("password")}
    if t == "hysteria2":
        out = {**base, "password": spec.get("password")}
        if spec.get("obfs"):
            out["obfs"] = {"type": spec["obfs"]["type"],
                           "password": spec["obfs"].get("password")}
        out["tls"] = tls if tls else {"enabled": True, "server_name": spec["server"]}
        return out
    if t == "tuic":
        out = {**base, "uuid": spec.get("uuid"), "password": spec.get("password")}
        if spec.get("congestionControl"):
            out["congestion_control"] = spec["congestionControl"]
        if spec.get("udpRelayMode"):
            out["udp_relay_mode"] = spec["udpRelayMode"]
        out["tls"] = tls if tls else {"enabled": True, "server_name": spec["server"]}
        return out
    raise ValueError(f"sing-box: unsupported outbound protocol: {t}")


def build_singbox_config(spec, socks_port, listen="127.0.0.1"):
    """Build a full sing-box config. socks_port is the loopback SOCKS5 inbound."""
    return {
        "log": {"level": "warn"},
        "inbounds": [{
            "type": "socks",
            "tag": "socks-in",
            "listen": listen,
            "listen_port": socks_port,
        }],
        "outbounds": [_outbound_for(spec), {"type": "direct", "tag": "direct"}],
    }
