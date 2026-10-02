'use strict';
// /search?q=... -> MIDBROWSE/1 result lines.
// Tries DuckDuckGo Lite, then Bing, then Wikipedia (works even when the
// big engines rate-limit datacenter IPs).

const { UA, proto, protoErr, clean, stripTags, decodeEntities, decodeBody, attr } = require('./convert');

const MAX_RESULTS = 20;

async function ddg(q) {
  const resp = await fetch('https://lite.duckduckgo.com/lite/?q=' + encodeURIComponent(q), {
    redirect: 'follow',
    signal: AbortSignal.timeout(15000),
    headers: {
      'User-Agent': UA,
      'Accept': 'text/html',
      'Accept-Language': 'en-US,en;q=0.8'
    }
  });
  const html = decodeBody(await resp.arrayBuffer(), resp.headers.get('content-type') || '');
  const out = [];
  const rx = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = rx.exec(html)) !== null) {
    const href = attr(m[1], 'href');
    if (!href) continue;
    let url = href;
    if (url.indexOf('uddg=') >= 0) {
      const mm = /[?&]uddg=([^&]+)/.exec(url);
      if (!mm) continue;
      try { url = decodeURIComponent(mm[1]); } catch { continue; }
    } else if (url.indexOf('//') === 0) {
      url = 'https:' + url;
    }
    if (!/^https?:/i.test(url)) continue;
    if (/duckduckgo\.com/i.test(url)) continue;
    const title = clean(stripTags(decodeEntities(m[2])));
    if (!title) continue;
    out.push({ url, title, snippet: '' });
    if (out.length >= MAX_RESULTS) break;
  }
  // snippets appear in <td class="result-snippet">, in result order
  const snips = [];
  const snipRx = /<td[^>]*class="[^"]*result-snippet[^"]*"[^>]*>([\s\S]*?)<\/td>/gi;
  let sm;
  while ((sm = snipRx.exec(html)) !== null) {
    snips.push(clean(stripTags(decodeEntities(sm[1]))));
  }
  for (let i = 0; i < out.length && i < snips.length; i++) {
    out[i].snippet = snips[i];
  }
  return out;
}

async function bing(q) {
  const resp = await fetch('https://www.bing.com/search?q=' + encodeURIComponent(q) + '&count=20', {
    redirect: 'follow',
    signal: AbortSignal.timeout(15000),
    headers: {
      'User-Agent': UA,
      'Accept': 'text/html',
      'Accept-Language': 'en-US,en;q=0.8'
    }
  });
  const html = decodeBody(await resp.arrayBuffer(), resp.headers.get('content-type') || '');
  const out = [];
  const rx = /<h2[^>]*>\s*<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = rx.exec(html)) !== null) {
    const href = attr(m[1], 'href');
    if (!href || !/^https?:/i.test(href)) continue;
    const title = clean(stripTags(decodeEntities(m[2])));
    if (!title) continue;
    out.push({ url: href, title, snippet: '' });
    if (out.length >= MAX_RESULTS) break;
  }
  return out;
}

async function wiki(q) {
  const resp = await fetch(
    'https://en.wikipedia.org/w/api.php?action=opensearch&limit=15&namespace=0&format=json&search='
    + encodeURIComponent(q), {
      signal: AbortSignal.timeout(15000),
      headers: { 'User-Agent': UA }
    });
  const data = JSON.parse(await resp.text());
  if (!Array.isArray(data) || !Array.isArray(data[1])) return [];
  const titles = data[1];
  const descs = Array.isArray(data[2]) ? data[2] : [];
  const urls = Array.isArray(data[3]) ? data[3] : [];
  const out = [];
  for (let i = 0; i < titles.length; i++) {
    if (!urls[i]) continue;
    out.push({ url: urls[i], title: titles[i], snippet: descs[i] || '' });
  }
  return out;
}

async function handleSearch(u) {
  const q = (u.searchParams.get('q') || '').trim();
  if (!q) return protoErr('Missing query');

  let results = [];
  let source = '';
  try { results = await ddg(q); if (results.length) source = 'DuckDuckGo'; } catch { /* try next */ }
  if (!results.length) {
    try { results = await bing(q); if (results.length) source = 'Bing'; } catch { /* try next */ }
  }
  if (!results.length) {
    try { results = await wiki(q); if (results.length) source = 'Wikipedia'; } catch { /* last resort */ }
  }

  const out = [];
  if (!results.length) {
    out.push('X\tNo results found. Search engines may be rate-limiting this server.');
    out.push('X\tTry again later, or open a site directly via Menu > Open URL.');
  } else {
    out.push('X\tResults from ' + source + ':');
    let i = 0;
    for (const r of results) {
      out.push('L\t' + i + '\t' + String(r.url).replace(/[\t\r\n]/g, '') + '\t' + clean(r.title));
      i++;
      if (r.snippet) out.push('X\t' + clean(r.snippet));
    }
  }
  return proto(out, 'Search: ' + q, 'https://lite.duckduckgo.com/lite/?q=' + encodeURIComponent(q), null);
}

module.exports = { handleSearch };
