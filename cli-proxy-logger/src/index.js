#!/usr/bin/env node
// Entry point: load config, start proxy + UI, print connection instructions.

import { loadConfig } from './config.js';
import { Recorder } from './recorder.js';
import { startProxy } from './proxy.js';
import { startUi } from './ui-server.js';

const config = loadConfig();
const recorder = new Recorder(config);

// When an outbound kernel (xray/sing-box) is configured, wait for it to come up
// so the banner reflects a working tunnel and we fail fast on misconfig.
if (config.outbound && config.outbound.kernel) {
  try {
    await config.outbound.whenReady;
  } catch (err) {
    console.error(`[kernel] failed to start outbound kernel: ${err.message}`);
    process.exit(1);
  }
  const stopKernel = () => { try { config.outbound.stop(); } catch { /* ignore */ } };
  process.on('exit', stopKernel);
  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => { stopKernel(); process.exit(0); });
  }
}

startProxy(config, recorder);
startUi(config, recorder);

const proxy = `http://127.0.0.1:${config.proxyPort}`;
console.log(`
cli-proxy-logger running.
  logs -> ${config.logDir}

Point Claude Code at the proxy:
  export ANTHROPIC_BASE_URL=${proxy}
  # (non-official host disables MCP tool search by default)
  # export ENABLE_TOOL_SEARCH=true

Point Codex at the proxy (~/.codex/config.toml):
  openai_base_url = "${proxy}/v1"
  # or a custom provider:
  # [model_providers.proxy]
  # name = "local proxy"
  # base_url = "${proxy}/v1"
  # wire_api = "responses"
`);
