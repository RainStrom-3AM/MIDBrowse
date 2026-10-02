#!/usr/bin/env node
'use strict';
// MIDBrowse desktop backend — listens on 0.0.0.0, the phone app points here.

const http = require('http');
const os = require('os');
const { handlePage, proto, protoErr } = require('./convert');
const { handleSearch } = require('./search');

const PORT = parseInt(process.env.PORT || '8765', 10);

function send(res, code, body, type) {
  res.writeHead(code, {
    'Content-Type': type || 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  res.end(body);
}

function localAddresses() {
  const out = [];
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const a of nets[name] || []) {
      if ((a.family === 'IPv4' || a.family === 4) && !a.internal) {
        out.push(a.address);
      }
    }
  }
  return out;
}

function statusPage() {
  const ips = localAddresses();
  const rows = ips.map(ip => `<li><code>http://${ip}:${PORT}</code></li>`).join('');
  return `<!doctype html>
<html><head><meta charset="utf-8"><title>MIDBrowse server</title></head>
<body style="font-family:sans-serif;max-width:40em;margin:2em auto;padding:0 1em">
<h1>MIDBrowse server is running</h1>
<p>Point the phone app at one of these addresses (Menu &gt; Server address...):</p>
<ul>${rows || '<li><em>no LAN IPv4 found</em></li>'}</ul>
<p>Same Wi-Fi/network as the PC? Use a LAN address. Over the internet (RDP/cloud
VM)? Use the machine's public IP and allow inbound TCP ${PORT} in the firewall/NSG.</p>
<h3>Endpoints</h3>
<ul>
<li><code>/ping</code> &mdash; connectivity check</li>
<li><code>/page?url=&lt;enc&gt;</code> &mdash; fetch + simplify a page (no url = home)</li>
<li><code>/search?q=&lt;enc&gt;</code> &mdash; web search results</li>
</ul>
<p>Pages/search require header <code>X-MIDBROWSE: 1</code> so the server cannot be
trivially abused as an open proxy by browsers.</p>
</body></html>`;
}

const server = http.createServer(async (req, res) => {
  const t0 = Date.now();
  let u;
  try {
    u = new URL(req.url, 'http://internal');
  } catch {
    send(res, 400, protoErr('Bad request'));
    return;
  }
  const p = u.pathname;
  const guarded = p === '/page' || p === '/search';
  if (guarded && req.headers['x-midbrowse'] !== '1') {
    send(res, 403, protoErr('Missing X-MIDBROWSE header. Use the MIDBrowse app.'));
    logIt(req, 403, t0);
    return;
  }
  try {
    let out;
    let code = 200;
    if (p === '/ping') {
      out = proto([], 'MIDBrowse', '', 'pong');
    } else if (p === '/page') {
      out = await handlePage(u);
    } else if (p === '/search') {
      out = await handleSearch(u);
    } else if (p === '/') {
      send(res, 200, statusPage(), 'text/html; charset=utf-8');
      logIt(req, 200, t0);
      return;
    } else {
      code = 404;
      out = protoErr('Not found');
    }
    send(res, code, out);
    logIt(req, code, t0);
  } catch (e) {
    send(res, 502, protoErr('Server error: ' + ((e && e.message) || e)));
    logIt(req, 502, t0);
  }
});

function logIt(req, code, t0) {
  console.log(req.method + ' ' + req.url + ' -> ' + code + ' (' + (Date.now() - t0) + 'ms)');
}

server.listen(PORT, '0.0.0.0', () => {
  console.log('MIDBrowse server: listening on 0.0.0.0:' + PORT);
  for (const ip of localAddresses()) {
    console.log('  LAN   http://' + ip + ':' + PORT);
  }
  console.log('  Open this address in the phone app (Menu > Server address).');
  console.log('  Internet-facing machine? Allow inbound TCP ' + PORT + ' and use your public IP.');
  fetch('https://api.ipify.org', { signal: AbortSignal.timeout(4000) })
    .then(r => r.text())
    .then(ip => console.log('  Public http://' + ip + ':' + PORT))
    .catch(() => {});
});
