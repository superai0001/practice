#!/usr/bin/env python
"""A stand-in for the xray/sing-box binary used by kernel tests.

It speaks the same CLI surface the manager uses (``run -c <config>``), reads the
SOCKS inbound port out of either kernel's config shape, and runs a minimal
no-auth SOCKS5 CONNECT server on it (recording targets to FAKE_KERNEL_SEEN when
set). This lets us exercise the full create_kernel -> spawn -> ready -> socks
tunnel path without the real Go binaries.
"""

import json
import os
import socket
import sys
import threading


def socks_port_of(cfg):
    for inb in cfg.get("inbounds", []):
        if inb and inb.get("type") == "socks" and inb.get("listen_port"):
            return inb["listen_port"]  # sing-box
        if inb and inb.get("protocol") == "socks" and inb.get("port"):
            return inb["port"]  # xray
    return None


def pump(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d:
                break
            b.sendall(d)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise OSError("closed early")
        buf += chunk
    return buf


def handle(sock, seen_path):
    try:
        head = recv_exact(sock, 2)
        nmethods = head[1]
        recv_exact(sock, nmethods)
        sock.sendall(bytes([0x05, 0x00]))
        req = recv_exact(sock, 4)
        atyp = req[3]
        if atyp == 0x01:
            host = ".".join(str(b) for b in recv_exact(sock, 4))
        elif atyp == 0x03:
            ln = recv_exact(sock, 1)[0]
            host = recv_exact(sock, ln).decode()
        else:
            sock.close()
            return
        dport = int.from_bytes(recv_exact(sock, 2), "big")
        if seen_path:
            try:
                with open(seen_path, "a", encoding="utf-8") as fh:
                    fh.write(f"{host}:{dport}\n")
            except OSError:
                pass
        try:
            upstream = socket.create_connection((host, dport), timeout=10)
        except OSError:
            sock.close()
            return
        sock.sendall(bytes([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]))
        threading.Thread(target=pump, args=(sock, upstream), daemon=True).start()
        pump(upstream, sock)
    except OSError:
        try:
            sock.close()
        except OSError:
            pass


def main():
    args = sys.argv[1:]
    config_path = args[args.index("-c") + 1] if "-c" in args else None
    if not config_path:
        print("fake-kernel: missing -c <config>", file=sys.stderr)
        sys.exit(2)
    with open(config_path, "r", encoding="utf-8") as fh:
        cfg = json.load(fh)
    port = socks_port_of(cfg)
    if not port:
        print("fake-kernel: no socks inbound in config", file=sys.stderr)
        sys.exit(2)
    seen_path = os.environ.get("FAKE_KERNEL_SEEN") or ""

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(16)
    print(f"fake-kernel: socks5 listening on 127.0.0.1:{port}")
    while True:
        try:
            cli, _ = srv.accept()
        except OSError:
            break
        threading.Thread(target=handle, args=(cli, seen_path), daemon=True).start()


if __name__ == "__main__":
    main()
