"""Kernel manager: run xray-core / sing-box as a local child process that
exposes a loopback SOCKS5 inbound, so the existing outbound SOCKS5 tunnel can
egress through advanced protocols (VMess/VLESS/Trojan/Shadowsocks/Hysteria2/
TUIC). Zero third-party dependencies -- we shell out to the official binary.

Port of the Node ``src/kernel/index.js``.
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

from .links import SINGBOX_ONLY, parse_share_link
from .singbox import build_singbox_config
from .xray import build_xray_config

_IS_WIN = os.name == "nt"


def _bin_candidates(kind, explicit):
    exe = ".exe" if _IS_WIN else ""
    name = f"xray{exe}" if kind == "xray" else f"sing-box{exe}"
    here = os.path.dirname(os.path.abspath(__file__))
    out = []
    if explicit:
        out.append(explicit)
    # vendored next to the package (sibling "vendor/" dir), then bare name on PATH.
    out.append(os.path.join(here, "..", "..", "vendor", name))
    out.append(name)
    return out


def _resolve_bin(candidates):
    for c in candidates:
        if os.sep in c or (os.altsep and os.altsep in c):
            if os.path.exists(c):
                return c
        else:
            return c  # bare name -> let the OS resolve via PATH
    return candidates[-1]


def _choose_kernel(spec, preference):
    if preference in ("xray", "sing-box"):
        if preference == "xray" and spec and spec.get("type") in SINGBOX_ONLY:
            raise ValueError(
                f"xray-core has no native {spec['type']} outbound; use PROXY_KERNEL=sing-box")
        return preference
    # auto: hysteria2/tuic require sing-box; otherwise prefer xray.
    if spec and spec.get("type") in SINGBOX_ONLY:
        return "sing-box"
    return "xray"


def _free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def _wait_for_port(port, timeout_ms):
    deadline = time.time() + timeout_ms / 1000.0
    while True:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            if time.time() > deadline:
                raise OSError(f"kernel SOCKS port {port} not ready within {timeout_ms}ms")
            time.sleep(0.15)


def _ensure_socks_inbound(cfg, kind, port):
    """Ensure a native config dict has a loopback socks inbound; return its port."""
    if not isinstance(cfg, dict):
        raise ValueError("kernel config must be a JSON object")
    cfg.setdefault("inbounds", [])
    if kind == "sing-box":
        for i in cfg["inbounds"]:
            if i and i.get("type") == "socks":
                return i.get("listen_port") or port
        cfg["inbounds"].append({"type": "socks", "tag": "cpl-socks-in",
                                "listen": "127.0.0.1", "listen_port": port})
    else:
        for i in cfg["inbounds"]:
            if i and i.get("protocol") == "socks":
                return i.get("port") or port
        cfg["inbounds"].append({"tag": "cpl-socks-in", "listen": "127.0.0.1",
                                "port": port, "protocol": "socks",
                                "settings": {"auth": "noauth", "udp": True}})
    return port


class Kernel:
    """A kernel descriptor. Does not spawn until ``start()`` is called."""

    def __init__(self, opts):
        self._opts = opts or {}
        self._logger = self._opts.get("logger") or (lambda line: print(f"[kernel] {line}", file=sys.stderr))
        self._spec = None
        self._native_config = None
        if self._opts.get("configPath"):
            with open(self._opts["configPath"], "r", encoding="utf-8") as fh:
                self._native_config = json.load(fh)
        elif self._opts.get("link"):
            self._spec = parse_share_link(self._opts["link"])
        else:
            raise ValueError("create_kernel: provide either link or configPath")

        self.kind = _choose_kernel(self._spec, self._opts.get("kernel") or "auto")
        explicit = self._opts.get("xrayBin") if self.kind == "xray" else self._opts.get("singboxBin")
        self.bin = _resolve_bin(_bin_candidates(self.kind, explicit))
        self.socks_port = self._opts.get("socksPort") or 0
        self._child = None
        self._config_dir = None
        self._stopped = False

    @property
    def spec(self):
        return self._spec

    def _pump(self, stream):
        for raw in iter(stream.readline, ""):
            line = raw.rstrip("\r\n")
            if line:
                self._logger(line)

    def start(self):
        if not self.socks_port:
            self.socks_port = _free_port()
        if self._native_config is not None:
            cfg = self._native_config
            self.socks_port = _ensure_socks_inbound(cfg, self.kind, self.socks_port)
        elif self.kind == "xray":
            cfg = build_xray_config(self._spec, self.socks_port)
        else:
            cfg = build_singbox_config(self._spec, self.socks_port)

        self._config_dir = tempfile.mkdtemp(prefix="cpl-kernel-")
        config_file = os.path.join(
            self._config_dir, "xray.json" if self.kind == "xray" else "sing-box.json")
        with open(config_file, "w", encoding="utf-8") as fh:
            json.dump(cfg, fh, indent=2)

        # Both xray-core and sing-box use `run -c <config>`. A `.py` binary path
        # is treated as a shim and run through the Python interpreter -- handy
        # for custom wrappers and for testing without the Go binaries.
        run_args = ["run", "-c", config_file]
        is_py_shim = self.bin.lower().endswith(".py")
        command = [sys.executable, self.bin, *run_args] if is_py_shim else [self.bin, *run_args]

        try:
            self._child = subprocess.Popen(
                command, stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, bufsize=1)
        except OSError as exc:
            raise OSError(f"failed to spawn {self.kind} ({self.bin}): {exc}") from exc

        threading.Thread(target=self._pump, args=(self._child.stdout,), daemon=True).start()

        timeout_ms = self._opts.get("readyTimeoutMs") or 10000
        deadline = time.time() + timeout_ms / 1000.0
        while True:
            rc = self._child.poll()
            if rc is not None:
                raise OSError(
                    f"{self.kind} exited before becoming ready (code={rc}). "
                    f"Is the binary \"{self.bin}\" installed and the config valid?")
            try:
                with socket.create_connection(("127.0.0.1", self.socks_port), timeout=0.5):
                    break
            except OSError:
                if time.time() > deadline:
                    raise OSError(
                        f"kernel SOCKS port {self.socks_port} not ready within {timeout_ms}ms")
                time.sleep(0.15)

        self._logger(f"{self.kind} ready: SOCKS5 127.0.0.1:{self.socks_port} -> {self.describe()}")
        return self.socks_port

    def stop(self):
        self._stopped = True
        if self._child and self._child.poll() is None:
            try:
                self._child.terminate()
            except OSError:
                pass
        if self._config_dir:
            shutil.rmtree(self._config_dir, ignore_errors=True)
            self._config_dir = None

    def describe(self):
        if self._spec:
            tls = self._spec.get("tls")
            via = "+reality" if (tls and tls.get("reality")) else ("+tls" if (tls and tls.get("enabled")) else "")
            tr = self._spec.get("transport")
            net = f"/{tr['type']}" if tr else ""
            return f"{self.kind}:{self._spec['type']}{net}{via} {self._spec['server']}:{self._spec['port']}"
        return f"{self.kind}:config({os.path.basename(self._opts.get('configPath') or '')})"


def create_kernel(opts=None):
    """Create a Kernel descriptor from options. Does not spawn -- call start()."""
    return Kernel(opts or {})
