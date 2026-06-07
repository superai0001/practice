"""Share-link parsing -> normalized outbound spec (pure, zero-dependency).

Port of the Node ``src/kernel/links.js``. Turns common proxy share links into a
kernel-agnostic dict that the xray / sing-box config renderers consume.
Supported schemes::

    vmess://      (v2rayN base64-JSON form)
    vless://      (uuid@host:port?query#name)
    trojan://     (password@host:port?query#name)
    ss://         (SIP002: base64(method:pass)@host:port, or fully base64'd)
    hysteria2://  (also hy2://) password@host:port?query#name
    tuic://       (uuid:password@host:port?query#name)
"""

import base64
import json
import re
from urllib.parse import parse_qs, unquote, urlparse

ADVANCED_SCHEMES = {
    "vmess", "vless", "trojan", "ss", "shadowsocks", "hysteria2", "hy2", "tuic",
}

# Protocols only sing-box can handle (xray-core has no native hy2/tuic outbound).
SINGBOX_ONLY = {"hysteria2", "tuic"}

_SCHEME_RE = re.compile(r"^([a-z0-9]+)://", re.IGNORECASE)


def is_kernel_scheme(raw):
    """Is this a link that needs a kernel (vs. a plain http/socks proxy)?"""
    if not isinstance(raw, str):
        return False
    m = _SCHEME_RE.match(raw.strip())
    return bool(m) and m.group(1).lower() in ADVANCED_SCHEMES


def _b64decode(s):
    txt = str(s).strip().replace("-", "+").replace("_", "/")
    txt += "=" * (-len(txt) % 4)
    return base64.b64decode(txt).decode("utf-8")


class _Query:
    """Tiny wrapper over parse_qs giving JS URLSearchParams.get semantics."""

    def __init__(self, query):
        self._d = parse_qs(query, keep_blank_values=True)

    def get(self, key, default=""):
        vals = self._d.get(key)
        return vals[0] if vals else default


def _tls_from_query(q, fallback_sni):
    security = (q.get("security") or "").lower()
    sni = q.get("sni") or q.get("peer") or fallback_sni or ""
    alpn_raw = q.get("alpn")
    alpn = [s.strip() for s in alpn_raw.split(",") if s.strip()] if alpn_raw else []
    fp = q.get("fp") or ""
    insecure = (q.get("allowInsecure") == "1" or q.get("insecure") == "1"
                or q.get("allow_insecure") == "1")
    is_reality = security == "reality"
    enabled = security in ("tls", "xtls") or is_reality
    if not enabled and not sni and not alpn and not fp and not insecure:
        return None
    tls = {
        "enabled": enabled or is_reality,
        "serverName": sni,
        "insecure": insecure,
        "alpn": alpn,
        "fingerprint": fp,
        "reality": None,
    }
    if is_reality:
        tls["reality"] = {
            "publicKey": q.get("pbk") or "",
            "shortId": q.get("sid") or "",
            "spiderX": q.get("spx") or "",
        }
    return tls


def _transport_from_query(q):
    type_ = (q.get("type") or "tcp").lower()
    if type_ in ("tcp", "", "none", "raw"):
        return None
    host = q.get("host") or ""
    path = q.get("path") or ""
    service_name = q.get("serviceName") or q.get("servicename") or path
    return {
        "type": "http" if type_ == "h2" else type_,
        "path": path,
        "host": host,
        "serviceName": service_name,
        "headers": {"Host": host} if host else {},
    }


def _parse_vmess(raw):
    body = raw[len("vmess://"):]
    try:
        obj = json.loads(_b64decode(body))
    except (ValueError, json.JSONDecodeError) as exc:
        raise ValueError("vmess: not a base64-encoded JSON link") from exc
    net = str(obj.get("net") or "tcp").lower()
    tls_on = str(obj.get("tls") or "").lower() == "tls"
    host = obj.get("host") or ""
    transport = None
    if net not in ("tcp", ""):
        transport = {
            "type": "http" if net == "h2" else net,
            "path": obj.get("path") or "",
            "host": host,
            "serviceName": obj.get("path") or "",
            "headers": {"Host": host} if host else {},
        }
    tls = None
    if tls_on:
        alpn = obj.get("alpn")
        tls = {
            "enabled": True,
            "serverName": obj.get("sni") or host or "",
            "insecure": False,
            "alpn": [s for s in str(alpn).split(",") if s] if alpn else [],
            "fingerprint": obj.get("fp") or "",
            "reality": None,
        }
    return {
        "type": "vmess",
        "tag": "proxy",
        "server": obj.get("add"),
        "port": int(obj.get("port")),
        "uuid": obj.get("id"),
        "alterId": int(obj.get("aid") or 0),
        "security": obj.get("scy") or obj.get("security") or "auto",
        "tls": tls,
        "transport": transport,
        "name": obj.get("ps") or "",
    }


def _parse_user_host_link(raw, type_):
    u = urlparse(raw)
    q = _Query(u.query)
    spec = {
        "type": type_,
        "tag": "proxy",
        "server": unquote(u.hostname or ""),
        "port": u.port,
        "name": unquote(u.fragment) if u.fragment else "",
        "tls": _tls_from_query(q, u.hostname or ""),
        "transport": _transport_from_query(q),
    }
    if type_ == "vless":
        spec["uuid"] = unquote(u.username or "")
        spec["flow"] = q.get("flow") or ""
        spec["encryption"] = q.get("encryption") or "none"
    elif type_ == "trojan":
        spec["password"] = unquote(u.username or "")
    return spec


def _parse_shadowsocks(raw):
    hash_idx = raw.find("#")
    name = unquote(raw[hash_idx + 1:]) if hash_idx >= 0 else ""
    body = (raw[:hash_idx] if hash_idx >= 0 else raw)[len("ss://"):]
    q_idx = body.find("?")
    query = ""
    if q_idx >= 0:
        query = body[q_idx + 1:]
        body = body[:q_idx]

    if "@" in body:
        at = body.rfind("@")
        userinfo = body[:at]
        hostport = body[at + 1:]
        if ":" in userinfo and not re.fullmatch(r"[A-Za-z0-9+/_=-]+", userinfo):
            decoded = userinfo
        else:
            decoded = _b64decode(userinfo)
    else:
        decoded_all = _b64decode(body)
        at = decoded_all.rfind("@")
        decoded = decoded_all[:at]
        hostport = decoded_all[at + 1:]
    ci = decoded.find(":")
    method = decoded[:ci]
    password = decoded[ci + 1:]
    hp = hostport.rfind(":")
    server = hostport[:hp]
    port = int(hostport[hp + 1:])

    q = _Query(query)
    return {
        "type": "shadowsocks",
        "tag": "proxy",
        "server": server,
        "port": port,
        "method": method,
        "password": password,
        "plugin": q.get("plugin") or "",
        "tls": None,
        "transport": None,
        "name": name,
    }


def _parse_hysteria2(raw):
    u = urlparse(re.sub(r"^hy2://", "hysteria2://", raw))
    q = _Query(u.query)
    tls = _tls_from_query(q, u.hostname or "") or {
        "enabled": True, "serverName": u.hostname or "", "insecure": False,
        "alpn": [], "fingerprint": "", "reality": None,
    }
    tls["enabled"] = True
    obfs = None
    if q.get("obfs"):
        obfs = {"type": q.get("obfs"),
                "password": q.get("obfs-password") or q.get("obfs_password") or ""}
    return {
        "type": "hysteria2",
        "tag": "proxy",
        "server": unquote(u.hostname or ""),
        "port": u.port or 443,
        "password": unquote(u.username or u.password or ""),
        "obfs": obfs,
        "tls": tls,
        "transport": None,
        "name": unquote(u.fragment) if u.fragment else "",
    }


def _parse_tuic(raw):
    u = urlparse(raw)
    q = _Query(u.query)
    tls = _tls_from_query(q, u.hostname or "") or {
        "enabled": True, "serverName": u.hostname or "", "insecure": False,
        "alpn": [], "fingerprint": "", "reality": None,
    }
    tls["enabled"] = True
    alpn_raw = q.get("alpn")
    return {
        "type": "tuic",
        "tag": "proxy",
        "server": unquote(u.hostname or ""),
        "port": u.port or 443,
        "uuid": unquote(u.username or ""),
        "password": unquote(u.password or ""),
        "congestionControl": q.get("congestion_control") or q.get("congestion") or "",
        "udpRelayMode": q.get("udp_relay_mode") or "",
        "alpn": [s.strip() for s in alpn_raw.split(",") if s.strip()] if alpn_raw else [],
        "tls": tls,
        "transport": None,
        "name": unquote(u.fragment) if u.fragment else "",
    }


def parse_share_link(raw):
    """Parse a share link into a normalized outbound spec. Raises on bad input."""
    if not isinstance(raw, str) or not raw:
        raise ValueError("empty link")
    link = raw.strip()
    m = _SCHEME_RE.match(link)
    if not m:
        raise ValueError("link missing scheme")
    scheme = m.group(1).lower()
    if scheme == "vmess":
        return _parse_vmess(link)
    if scheme == "vless":
        return _parse_user_host_link(link, "vless")
    if scheme == "trojan":
        return _parse_user_host_link(link, "trojan")
    if scheme in ("ss", "shadowsocks"):
        return _parse_shadowsocks(link)
    if scheme in ("hysteria2", "hy2"):
        return _parse_hysteria2(link)
    if scheme == "tuic":
        return _parse_tuic(link)
    raise ValueError(f"unsupported link scheme: {scheme}")
