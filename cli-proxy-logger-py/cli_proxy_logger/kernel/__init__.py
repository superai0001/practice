"""Local xray-core / sing-box kernel integration (advanced outbound protocols).

Mirrors the Node ``src/kernel/`` package: parse a share link into a normalized
spec, render it into the chosen kernel's native config, run the kernel as a
loopback SOCKS5 front so the existing outbound tunnel can egress through
VMess/VLESS/Trojan/Shadowsocks/Hysteria2/TUIC.
"""

from .links import (
    SINGBOX_ONLY,
    is_kernel_scheme,
    parse_share_link,
)
from .manager import Kernel, create_kernel
from .singbox import build_singbox_config
from .xray import build_xray_config

__all__ = [
    "SINGBOX_ONLY",
    "is_kernel_scheme",
    "parse_share_link",
    "build_xray_config",
    "build_singbox_config",
    "Kernel",
    "create_kernel",
]
