#!/usr/bin/env bash
# fetch-kernel.sh — download official xray-core / sing-box release binaries into ./vendor
#
# No Go / build needed: this grabs the prebuilt official release for your OS/arch
# from GitHub Releases and drops the binary into <variant>/vendor/ where the app
# auto-discovers it (vendor/ is checked before PATH).
#
# Usage:
#   bash scripts/fetch-kernel.sh            # both kernels
#   bash scripts/fetch-kernel.sh xray       # only xray
#   bash scripts/fetch-kernel.sh sing-box   # only sing-box
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
vendor_dir="$(cd "$script_dir/.." && pwd)/vendor"
mkdir -p "$vendor_dir"

what="${1:-all}"

# ---- detect OS / arch ----
uname_s="$(uname -s)"
uname_m="$(uname -m)"
case "$uname_s" in
  Linux)                         os_xray=linux;   os_sb=linux  ;;
  Darwin)                        os_xray=macos;   os_sb=darwin ;;
  MINGW*|MSYS*|CYGWIN*|Windows*) os_xray=windows; os_sb=windows;;
  *) echo "unsupported OS: $uname_s (use fetch-kernel.ps1 on Windows)"; exit 1 ;;
esac
case "$uname_m" in
  x86_64|amd64)   arch_xray="64";          arch_sb=amd64 ;;
  aarch64|arm64)  arch_xray="arm64-v8a";   arch_sb=arm64 ;;
  *) echo "unsupported arch: $uname_m"; exit 1 ;;
esac
[ "$os_xray" = windows ] && binext=".exe" || binext=""

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1"; exit 1; }; }
need curl
need tar

extract_zip() { # <zipfile> <destdir>
  if command -v unzip >/dev/null 2>&1; then unzip -o -q "$1" -d "$2"; else tar -xf "$1" -C "$2"; fi
}

fetch_xray() {
  local asset="Xray-${os_xray}-${arch_xray}.zip"
  local url="https://github.com/XTLS/Xray-core/releases/latest/download/${asset}"
  local tmp; tmp="$(mktemp -d)"
  echo "[xray] downloading $asset ..."
  curl -fL --retry 3 -o "$tmp/x.zip" "$url"
  extract_zip "$tmp/x.zip" "$tmp"
  cp "$tmp/xray${binext}" "$vendor_dir/xray${binext}"
  chmod +x "$vendor_dir/xray${binext}" 2>/dev/null || true
  rm -rf "$tmp"
  echo "[xray] -> $vendor_dir/xray${binext}"
}

fetch_singbox() {
  echo "[sing-box] resolving latest version ..."
  local tag ver
  tag="$(curl -fsSL https://api.github.com/repos/SagerNet/sing-box/releases/latest | grep '"tag_name"' | head -1 | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')"
  ver="${tag#v}"
  local ext asset
  if [ "$os_sb" = windows ]; then ext=zip; else ext=tar.gz; fi
  asset="sing-box-${ver}-${os_sb}-${arch_sb}.${ext}"
  local url="https://github.com/SagerNet/sing-box/releases/download/${tag}/${asset}"
  local tmp; tmp="$(mktemp -d)"
  echo "[sing-box] downloading $asset ..."
  curl -fL --retry 3 -o "$tmp/sb.$ext" "$url"
  if [ "$ext" = zip ]; then extract_zip "$tmp/sb.$ext" "$tmp"; else tar -xzf "$tmp/sb.$ext" -C "$tmp"; fi
  cp "$tmp/sing-box-${ver}-${os_sb}-${arch_sb}/sing-box${binext}" "$vendor_dir/sing-box${binext}"
  chmod +x "$vendor_dir/sing-box${binext}" 2>/dev/null || true
  rm -rf "$tmp"
  echo "[sing-box] -> $vendor_dir/sing-box${binext}"
}

case "$what" in
  all)      fetch_xray; fetch_singbox ;;
  xray)     fetch_xray ;;
  sing-box) fetch_singbox ;;
  *) echo "usage: fetch-kernel.sh [all|xray|sing-box]"; exit 1 ;;
esac
echo "done. binaries in $vendor_dir (auto-discovered by the app)."
