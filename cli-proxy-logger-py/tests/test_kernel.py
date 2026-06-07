"""Tests for the xray/sing-box kernel integration (advanced outbound protocols):
  1. share-link parsing -> normalized outbound spec (unit)
  2. spec -> xray / sing-box config rendering (unit)
  3. kernel manager spawn + readiness + e2e tunnel through a fake kernel

The integration test uses tests/fixtures/fake_kernel.py (a Python SOCKS5 shim)
in place of the real Go binaries, so no Go toolchain is required.

Run with::

    python tests/test_kernel.py      # from the cli-proxy-logger-py directory
"""

import base64
import http.client
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cli_proxy_logger.config import load_config  # noqa: E402
from cli_proxy_logger.proxy import start_proxy  # noqa: E402
from cli_proxy_logger.recorder import Recorder  # noqa: E402
from cli_proxy_logger.kernel import (  # noqa: E402
    SINGBOX_ONLY,
    build_singbox_config,
    build_xray_config,
    create_kernel,
    is_kernel_scheme,
    parse_share_link,
)
from cli_proxy_logger.outbound import create_outbound  # noqa: E402

_pass = 0
_LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp-logs")
FAKE_KERNEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "fake_kernel.py")


def check(name, cond):
    assert cond, name
    print("  ok -", name)
    global _pass
    _pass += 1


def eq(name, a, b):
    check(f"{name} (got {json.dumps(a)})", json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True))


def b64(s):
    return base64.b64encode(s.encode()).decode()


def make_server():
    requests = []

    class Mock(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_POST(self):
            length = int(self.headers.get("content-length") or 0)
            if length:
                self.rfile.read(length)
            requests.append({"url": self.path})
            data = json.dumps({"ok": True}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Mock)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    port = srv.server_address[1]
    return {"srv": srv, "port": port, "url": f"http://127.0.0.1:{port}", "requests": requests}


def post(port, path, body_obj):
    body = json.dumps(body_obj)
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    conn.request("POST", path, body=body, headers={"Content-Type": "application/json"})
    res = conn.getresponse()
    data = res.read().decode()
    status = res.status
    conn.close()
    return {"status": status, "body": data}


def start_test_proxy(**overrides):
    config = load_config(proxyPort=0, uiPort=0, logDir=_LOG_DIR, **overrides)
    recorder = Recorder(config)
    proxy = start_proxy(config, recorder)
    time.sleep(0.1)
    return {"proxy": proxy, "port": config["proxyPort"]}


def run():
    print("kernel: scheme detection (unit)")
    check("vmess is kernel scheme", is_kernel_scheme("vmess://abc"))
    check("vless is kernel scheme", is_kernel_scheme("vless://u@h:1"))
    check("hy2 alias is kernel scheme", is_kernel_scheme("hy2://p@h:1"))
    check("http is NOT kernel scheme", not is_kernel_scheme("http://h:8080"))
    check("socks5 is NOT kernel scheme", not is_kernel_scheme("socks5://h:1080"))
    check("empty is NOT kernel scheme", not is_kernel_scheme(""))
    check("hysteria2/tuic are sing-box only",
          "hysteria2" in SINGBOX_ONLY and "tuic" in SINGBOX_ONLY)

    print("kernel: share-link parsing (unit)")
    vmess_obj = {"add": "example.com", "port": "443", "id": "uuid-1", "aid": "0",
                 "scy": "auto", "net": "ws", "host": "cdn.example.com", "path": "/ray",
                 "tls": "tls", "sni": "example.com", "ps": "node-a"}
    v = parse_share_link("vmess://" + b64(json.dumps(vmess_obj)))
    eq("vmess server/port", [v["server"], v["port"]], ["example.com", 443])
    eq("vmess uuid/security", [v["uuid"], v["security"]], ["uuid-1", "auto"])
    eq("vmess ws transport",
       [v["transport"]["type"], v["transport"]["path"], v["transport"]["host"]],
       ["ws", "/ray", "cdn.example.com"])
    eq("vmess tls sni", [v["tls"]["enabled"], v["tls"]["serverName"]], [True, "example.com"])

    vl = parse_share_link("vless://uuid-2@host.net:8443?encryption=none&security=tls&sni=h.net&type=grpc&serviceName=gs&flow=xtls-rprx-vision#vl")
    eq("vless uuid/flow", [vl["uuid"], vl["flow"]], ["uuid-2", "xtls-rprx-vision"])
    eq("vless grpc service", [vl["transport"]["type"], vl["transport"]["serviceName"]], ["grpc", "gs"])
    eq("vless tls", [vl["tls"]["enabled"], vl["tls"]["serverName"]], [True, "h.net"])

    vr = parse_share_link("vless://uuid-3@h.net:443?security=reality&pbk=PUBKEY&sid=ab12&sni=www.apple.com&fp=chrome&type=tcp#r")
    eq("vless reality keys", [vr["tls"]["reality"]["publicKey"], vr["tls"]["reality"]["shortId"]], ["PUBKEY", "ab12"])

    tj = parse_share_link("trojan://secret@h.net:443?security=tls&sni=h.net#t")
    eq("trojan pass/server", [tj["password"], tj["server"], tj["port"]], ["secret", "h.net", 443])

    ss = parse_share_link("ss://" + b64("aes-256-gcm:pw123") + "@1.2.3.4:8388#s")
    eq("ss sip002 method/pass", [ss["method"], ss["password"]], ["aes-256-gcm", "pw123"])
    eq("ss sip002 host/port", [ss["server"], ss["port"]], ["1.2.3.4", 8388])
    ss_legacy = parse_share_link("ss://" + b64("chacha20-ietf-poly1305:pw@5.6.7.8:9999") + "#s2")
    eq("ss legacy method/host", [ss_legacy["method"], ss_legacy["server"], ss_legacy["port"]],
       ["chacha20-ietf-poly1305", "5.6.7.8", 9999])

    hy = parse_share_link("hysteria2://pw@h.net:8443?sni=h.net&insecure=1&obfs=salamander&obfs-password=op#h")
    eq("hy2 pass/obfs", [hy["password"], hy["obfs"]["type"], hy["obfs"]["password"]], ["pw", "salamander", "op"])
    eq("hy2 tls insecure", [hy["tls"]["enabled"], hy["tls"]["insecure"]], [True, True])
    hy2_alias = parse_share_link("hy2://pw@h.net:443#h2")
    eq("hy2 alias parsed", hy2_alias["type"], "hysteria2")

    tu = parse_share_link("tuic://uuid-9:pw@h.net:443?congestion_control=bbr&alpn=h3&sni=h.net#u")
    eq("tuic uuid/pass/cc", [tu["uuid"], tu["password"], tu["congestionControl"]], ["uuid-9", "pw", "bbr"])

    threw = False
    try:
        parse_share_link("ftp://nope")
    except ValueError:
        threw = True
    check("unsupported scheme throws", threw)

    print("kernel: xray config rendering (unit)")
    v = parse_share_link("vless://u@h.net:443?encryption=none&security=tls&sni=h.net&type=ws&path=/p&host=cdn#x")
    cfg = build_xray_config(v, 10800)
    eq("xray socks inbound",
       [cfg["inbounds"][0]["protocol"], cfg["inbounds"][0]["port"], cfg["inbounds"][0]["listen"]],
       ["socks", 10800, "127.0.0.1"])
    out = cfg["outbounds"][0]
    eq("xray vless outbound",
       [out["protocol"], out["settings"]["vnext"][0]["address"], out["settings"]["vnext"][0]["users"][0]["id"]],
       ["vless", "h.net", "u"])
    eq("xray stream tls+ws",
       [out["streamSettings"]["security"], out["streamSettings"]["network"], out["streamSettings"]["wsSettings"]["path"]],
       ["tls", "ws", "/p"])
    check("xray has freedom direct", any(o.get("protocol") == "freedom" for o in cfg["outbounds"]))

    vm = build_xray_config(parse_share_link("vmess://" + b64(json.dumps({"add": "a", "port": 443, "id": "id", "aid": 2, "net": "tcp"}))), 10801)
    eq("xray vmess vnext user",
       [vm["outbounds"][0]["settings"]["vnext"][0]["users"][0]["id"],
        vm["outbounds"][0]["settings"]["vnext"][0]["users"][0]["alterId"]],
       ["id", 2])

    ss_cfg = build_xray_config(parse_share_link("ss://" + b64("aes-256-gcm:pw") + "@1.1.1.1:80#s"), 10802)
    eq("xray ss server",
       [ss_cfg["outbounds"][0]["protocol"], ss_cfg["outbounds"][0]["settings"]["servers"][0]["method"]],
       ["shadowsocks", "aes-256-gcm"])

    print("kernel: sing-box config rendering (unit)")
    v = parse_share_link("vless://u@h.net:443?encryption=none&security=tls&sni=h.net&type=ws&path=/p&host=cdn#x")
    cfg = build_singbox_config(v, 10810)
    eq("sb socks inbound", [cfg["inbounds"][0]["type"], cfg["inbounds"][0]["listen_port"]], ["socks", 10810])
    out = cfg["outbounds"][0]
    eq("sb vless outbound", [out["type"], out["server"], out["server_port"], out["uuid"]], ["vless", "h.net", 443, "u"])
    eq("sb tls+ws", [out["tls"]["enabled"], out["tls"]["server_name"], out["transport"]["type"], out["transport"]["path"]],
       [True, "h.net", "ws", "/p"])

    hy = build_singbox_config(parse_share_link("hysteria2://pw@h.net:8443?sni=h.net&obfs=salamander&obfs-password=op#h"), 10811)
    eq("sb hy2 outbound", [hy["outbounds"][0]["type"], hy["outbounds"][0]["password"], hy["outbounds"][0]["obfs"]["type"]],
       ["hysteria2", "pw", "salamander"])

    tu = build_singbox_config(parse_share_link("tuic://uuid:pw@h.net:443?congestion_control=bbr&sni=h.net#u"), 10812)
    eq("sb tuic outbound", [tu["outbounds"][0]["type"], tu["outbounds"][0]["uuid"], tu["outbounds"][0]["congestion_control"]],
       ["tuic", "uuid", "bbr"])

    print("kernel: selection rules (unit)")
    k1 = create_kernel({"link": "hysteria2://pw@h:443", "kernel": "auto", "singboxBin": FAKE_KERNEL})
    eq("auto picks sing-box for hy2", k1.kind, "sing-box")
    k2 = create_kernel({"link": "vmess://" + b64(json.dumps({"add": "a", "port": 1, "id": "x", "net": "tcp"})), "kernel": "auto"})
    eq("auto picks xray for vmess", k2.kind, "xray")
    threw = False
    try:
        create_kernel({"link": "tuic://u:p@h:443", "kernel": "xray"})
    except ValueError:
        threw = True
    check("xray + tuic rejected", threw)

    print("kernel: e2e tunnel through fake kernel (vless via sing-box shim)")
    up = make_server()
    seen_dir = os.path.join(_LOG_DIR, "seen")
    os.makedirs(seen_dir, exist_ok=True)
    seen_file = os.path.join(seen_dir, f"seen-{int(time.time()*1000)}.txt")
    os.environ["FAKE_KERNEL_SEEN"] = seen_file
    try:
        link = "vless://u@127.0.0.1:1?encryption=none#e2e"
        outbound = create_outbound(link, {"kernel": "sing-box", "singboxBin": FAKE_KERNEL})
        proxy_dict = outbound["proxy"]()
        check("kernel became ready (socks port assigned)",
              proxy_dict and proxy_dict["kind"] == "socks5" and proxy_dict["port"] > 0)

        t = start_test_proxy(upstream={"anthropic": up["url"], "openai": up["url"]}, outbound=outbound)
        r = post(t["port"], "/v1/messages", {"model": "claude", "messages": []})
        check("request succeeded through kernel socks tunnel", r["status"] == 200)
        check("upstream received the request", len(up["requests"]) == 1)
        seen = ""
        if os.path.exists(seen_file):
            with open(seen_file, "r", encoding="utf-8") as fh:
                seen = fh.read()
        check("fake kernel tunneled to upstream host:port", f":{up['port']}" in seen)
        t["proxy"].shutdown()
        up["srv"].shutdown()
        outbound["stop"]()
    finally:
        os.environ.pop("FAKE_KERNEL_SEEN", None)

    print("kernel: startup fails fast when binary is missing")
    failed = False
    try:
        create_outbound("vmess://" + b64(json.dumps({"add": "h", "port": 1, "id": "x", "net": "tcp"})),
                        {"kernel": "xray", "xrayBin": "/nonexistent/xray-binary-xyz"})
    except OSError:
        failed = True
    check("create_outbound raises on missing/failed binary", failed)

    print(f"\nkernel: {_pass} checks passed")


if __name__ == "__main__":
    run()
