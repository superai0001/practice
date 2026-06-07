#!/usr/bin/env node
// A stand-in for the xray/sing-box binary used by kernel tests. It speaks the
// same CLI surface the manager uses (`run -c <config>`), reads the SOCKS inbound
// port out of either kernel's config shape, and runs a minimal no-auth SOCKS5
// CONNECT server on it (recording targets to FAKE_KERNEL_SEEN when set). This
// lets us exercise the full createKernel -> spawn -> ready -> socks tunnel path
// without the real Go binaries.

import fs from 'node:fs';
import net from 'node:net';

const args = process.argv.slice(2);
const ci = args.indexOf('-c');
const configPath = ci >= 0 ? args[ci + 1] : null;
if (!configPath) { console.error('fake-kernel: missing -c <config>'); process.exit(2); }
const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'));

function socksPortOf(c) {
  for (const inb of c.inbounds || []) {
    if (inb && inb.type === 'socks' && inb.listen_port) return inb.listen_port;       // sing-box
    if (inb && inb.protocol === 'socks' && inb.port) return inb.port;                 // xray
  }
  return null;
}

const port = socksPortOf(cfg);
if (!port) { console.error('fake-kernel: no socks inbound in config'); process.exit(2); }

const seenPath = process.env.FAKE_KERNEL_SEEN || '';
const recordTarget = (t) => { if (seenPath) try { fs.appendFileSync(seenPath, `${t}\n`); } catch { /* ignore */ } };

const srv = net.createServer((sock) => {
  let stage = 0;
  let buf = Buffer.alloc(0);
  sock.on('error', () => {});
  sock.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    if (stage === 0) {
      if (buf.length < 2) return;
      const n = buf[1];
      if (buf.length < 2 + n) return;
      buf = buf.subarray(2 + n);
      sock.write(Buffer.from([0x05, 0x00]));
      stage = 1;
    }
    if (stage === 1) {
      if (buf.length < 4) return;
      const atyp = buf[3];
      let host; let offset;
      if (atyp === 0x01) { if (buf.length < 10) return; host = `${buf[4]}.${buf[5]}.${buf[6]}.${buf[7]}`; offset = 8; }
      else if (atyp === 0x03) { const len = buf[4]; if (buf.length < 5 + len + 2) return; host = buf.subarray(5, 5 + len).toString(); offset = 5 + len; }
      else { sock.end(); return; }
      const dport = buf.readUInt16BE(offset);
      recordTarget(`${host}:${dport}`);
      buf = buf.subarray(offset + 2);
      stage = 2;
      const upstream = net.connect(dport, host, () => {
        sock.write(Buffer.from([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
        upstream.pipe(sock);
        sock.pipe(upstream);
      });
      upstream.on('error', () => sock.destroy());
    }
  });
});
srv.listen(port, '127.0.0.1', () => console.log(`fake-kernel: socks5 listening on 127.0.0.1:${port}`));

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { srv.close(); process.exit(0); });
