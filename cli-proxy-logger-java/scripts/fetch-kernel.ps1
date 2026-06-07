# fetch-kernel.ps1 — download official xray-core / sing-box release binaries into .\vendor
#
# No Go / build needed: grabs the prebuilt official Windows release from GitHub
# Releases and drops the .exe into <variant>\vendor\ where the app auto-discovers
# it (vendor\ is checked before PATH).
#
# Usage (PowerShell):
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-kernel.ps1            # both
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-kernel.ps1 xray       # only xray
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-kernel.ps1 sing-box   # only sing-box
param([string]$What = "all")
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$vendorDir = Join-Path (Split-Path -Parent $scriptDir) "vendor"
New-Item -ItemType Directory -Force -Path $vendorDir | Out-Null

# ---- detect arch ----
$archRaw = $env:PROCESSOR_ARCHITECTURE
switch ($archRaw) {
  "AMD64" { $archXray = "64";        $archSb = "amd64" }
  "ARM64" { $archXray = "arm64-v8a"; $archSb = "arm64" }
  default { throw "unsupported arch: $archRaw" }
}

function Fetch-Xray {
  $asset = "Xray-windows-$archXray.zip"
  $url   = "https://github.com/XTLS/Xray-core/releases/latest/download/$asset"
  $tmp   = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("xray-" + [guid]::NewGuid()))
  Write-Host "[xray] downloading $asset ..."
  Invoke-WebRequest -Uri $url -OutFile (Join-Path $tmp "x.zip")
  Expand-Archive -Path (Join-Path $tmp "x.zip") -DestinationPath $tmp -Force
  Copy-Item (Join-Path $tmp "xray.exe") (Join-Path $vendorDir "xray.exe") -Force
  Remove-Item -Recurse -Force $tmp
  Write-Host "[xray] -> $vendorDir\xray.exe"
}

function Fetch-SingBox {
  Write-Host "[sing-box] resolving latest version ..."
  $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/SagerNet/sing-box/releases/latest" -Headers @{ "User-Agent" = "fetch-kernel" }
  $tag = $rel.tag_name
  $ver = $tag.TrimStart("v")
  $asset = "sing-box-$ver-windows-$archSb.zip"
  $url   = "https://github.com/SagerNet/sing-box/releases/download/$tag/$asset"
  $tmp   = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("sb-" + [guid]::NewGuid()))
  Write-Host "[sing-box] downloading $asset ..."
  Invoke-WebRequest -Uri $url -OutFile (Join-Path $tmp "sb.zip")
  Expand-Archive -Path (Join-Path $tmp "sb.zip") -DestinationPath $tmp -Force
  Copy-Item (Join-Path $tmp "sing-box-$ver-windows-$archSb\sing-box.exe") (Join-Path $vendorDir "sing-box.exe") -Force
  Remove-Item -Recurse -Force $tmp
  Write-Host "[sing-box] -> $vendorDir\sing-box.exe"
}

switch ($What) {
  "all"      { Fetch-Xray; Fetch-SingBox }
  "xray"     { Fetch-Xray }
  "sing-box" { Fetch-SingBox }
  default    { throw "usage: fetch-kernel.ps1 [all|xray|sing-box]" }
}
Write-Host "done. binaries in $vendorDir (auto-discovered by the app)."
