"""Render a normalized outbound spec -> Xray-core config dict.

Port of the Node ``src/kernel/xray.js``. Produces a minimal config with a single
SOCKS inbound on loopback and one proxy outbound for the requested protocol.
Xray-core has no native Hysteria2 / TUIC outbound; those must use sing-box.
"""


def _stream_settings(spec):
    tls = spec.get("tls")
    tr = spec.get("transport")
    network = tr["type"] if tr else "tcp"
    if not tls and (not tr or network == "tcp"):
        return None

    ss = {"network": "h2" if network == "http" else network}
    if tls:
        if tls.get("reality"):
            ss["security"] = "reality"
            ss["realitySettings"] = {
                "serverName": tls.get("serverName") or "",
                "publicKey": tls["reality"].get("publicKey") or "",
                "shortId": tls["reality"].get("shortId") or "",
                "spiderX": tls["reality"].get("spiderX") or "",
                "fingerprint": tls.get("fingerprint") or "chrome",
            }
        else:
            ss["security"] = "tls"
            ss["tlsSettings"] = {
                "serverName": tls.get("serverName") or "",
                "allowInsecure": bool(tls.get("insecure")),
            }
            if tls.get("alpn"):
                ss["tlsSettings"]["alpn"] = tls["alpn"]
            if tls.get("fingerprint"):
                ss["tlsSettings"]["fingerprint"] = tls["fingerprint"]
    if tr:
        if network == "ws":
            ws = {"path": tr.get("path") or "/"}
            if tr.get("host"):
                ws["host"] = tr["host"]
            ss["wsSettings"] = ws
        elif network == "grpc":
            ss["grpcSettings"] = {"serviceName": tr.get("serviceName") or ""}
        elif network == "http":
            ss["httpSettings"] = {"path": tr.get("path") or "/",
                                  "host": [tr["host"]] if tr.get("host") else []}
        elif network == "httpupgrade":
            ss["httpupgradeSettings"] = {"path": tr.get("path") or "/",
                                         "host": tr.get("host") or ""}
    return ss


def _outbound_settings(spec):
    t = spec["type"]
    if t == "vmess":
        return {
            "protocol": "vmess",
            "settings": {"vnext": [{
                "address": spec["server"],
                "port": spec["port"],
                "users": [{"id": spec["uuid"], "alterId": spec.get("alterId") or 0,
                           "security": spec.get("security") or "auto"}],
            }]},
        }
    if t == "vless":
        user = {"id": spec["uuid"], "encryption": spec.get("encryption") or "none"}
        if spec.get("flow"):
            user["flow"] = spec["flow"]
        return {
            "protocol": "vless",
            "settings": {"vnext": [{"address": spec["server"], "port": spec["port"],
                                    "users": [user]}]},
        }
    if t == "trojan":
        server = {"address": spec["server"], "port": spec["port"],
                  "password": spec.get("password")}
        if spec.get("flow"):
            server["flow"] = spec["flow"]
        return {"protocol": "trojan", "settings": {"servers": [server]}}
    if t == "shadowsocks":
        return {
            "protocol": "shadowsocks",
            "settings": {"servers": [{"address": spec["server"], "port": spec["port"],
                                      "method": spec.get("method"),
                                      "password": spec.get("password")}]},
        }
    raise ValueError(f"xray: unsupported outbound protocol: {t}")


def build_xray_config(spec, socks_port, listen="127.0.0.1"):
    """Build a full Xray config. socks_port is the loopback SOCKS5 inbound port."""
    out = {"tag": "proxy"}
    out.update(_outbound_settings(spec))
    ss = _stream_settings(spec)
    if ss:
        out["streamSettings"] = ss
    return {
        "log": {"loglevel": "warning"},
        "inbounds": [{
            "tag": "socks-in",
            "listen": listen,
            "port": socks_port,
            "protocol": "socks",
            "settings": {"auth": "noauth", "udp": True},
        }],
        "outbounds": [out, {"tag": "direct", "protocol": "freedom"}],
    }
