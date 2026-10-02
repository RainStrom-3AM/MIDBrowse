'use strict';
// Page fetching + HTML -> MIDBROWSE/1 conversion. Zero dependencies (Node 18+).

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
const MAX_BYTES = 4 * 1024 * 1024;
const MAX_LINES = 500;
const MAX_LINKS = 150;

// ---------------------------------------------------------------- protocol

// lines: array of protocol body lines ('X\t...', 'L\tidx\turl\tlabel', ...)
function proto(lines, title, url, status) {
  const out = ['MIDBROWSE/1', 'T\t' + clean(String(title || '')), 'U\t' + String(url || '').replace(/[\t\r\n]/g, '')];
  if (status) out.push('S\t' + String(status).replace(/[\t\r\n]/g, ' '));
  for (const l of lines) out.push(l);
  out.push('.');
  return out.join('\n') + '\n';
}

function protoErr(msg) {
  return proto(['E\t' + clean(String(msg))], 'Error', '', null);
}

// ------------------------------------------------------------- text helpers

const NAMED = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ',
  hellip: '\u2026', mdash: '\u2014', ndash: '\u2013',
  lsquo: '\u2018', rsquo: '\u2019', ldquo: '\u201c', rdquo: '\u201d',
  copy: '\u00a9', reg: '\u00ae', trade: '\u2122', middot: '\u00b7', bull: '\u2022',
  laquo: '\u00ab', raquo: '\u00bb', times: '\u00d7', divide: '\u00f7',
  deg: '\u00b0', euro: '\u20ac', pound: '\u00a3', yen: '\u00a5', cent: '\u00a2',
  sect: '\u00a7', para: '\u00b6', dagger: '\u2020', permil: '\u2030', shy: ''
};

function decodeEntities(s) {
  return String(s).replace(/&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z][a-zA-Z0-9]*);/g, (m, body) => {
    if (body[0] === '#') {
      const code = body[1] === 'x' || body[1] === 'X' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
      if (!isFinite(code) || code < 0 || code > 0x10ffff) return m;
      try { return String.fromCodePoint(code); } catch { return m; }
    }
    const v = NAMED[body.toLowerCase()];
    return v !== undefined ? v : m;
  });
}

function stripTags(s) {
  return String(s).replace(/<[^>]*>/g, ' ');
}

// decode entities, strip control chars/tabs, collapse whitespace
function clean(s) {
  return decodeEntities(String(s))
    .replace(/[\u0000-\u001f\u007f]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function isJunk(t) {
  return !/[\p{L}\p{N}]/u.test(t);
}

function attr(s, name) {
  const m = s.match(new RegExp('\\b' + name + '\\s*=\\s*("([^"]*)"|\'([^\']*)\'|([^\\s>]+))', 'i'));
  if (!m) return null;
  return m[2] !== undefined ? m[2] : (m[3] !== undefined ? m[3] : m[4]);
}

function resolveUrl(href, base) {
  if (!href) return null;
  href = decodeEntities(String(href)).trim();
  if (!href) return null;
  const lc = href.toLowerCase();
  if (lc.startsWith('javascript:') || lc.startsWith('mailto:') || lc.startsWith('tel:')
      || lc.startsWith('data:') || lc.startsWith('#')) return null;
  try {
    const u = new URL(href, base);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    u.hash = '';
    return u.toString();
  } catch {
    return null;
  }
}

function decodeBody(buf, ct) {
  let cs = null;
  let m = /charset\s*=\s*["']?([A-Za-z0-9._-]+)/i.exec(ct || '');
  if (m) cs = m[1];
  if (!cs) {
    const head = Buffer.from(buf.slice(0, 4096)).toString('latin1');
    m = /<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9._-]+)/i.exec(head);
    if (m) cs = m[1];
  }
  if (!cs || /^utf-?8$/i.test(cs)) return Buffer.from(buf).toString('utf8');
  try {
    return new TextDecoder(cs).decode(buf);
  } catch {
    return Buffer.from(buf).toString('utf8');
  }
}

// ------------------------------------------------------- line accumulator

class Doc {
  constructor() {
    this.out = [];
    this.links = 0;
    this.lastText = '';
  }

  text(t) {
    if (this.out.length >= MAX_LINES) return;
    t = clean(t);
    if (!t || t === this.lastText || isJunk(t)) return;
    this.out.push('X\t' + t);
    this.lastText = t;
  }

  link(url, label) {
    if (this.out.length >= MAX_LINES || this.links >= MAX_LINKS) return;
    url = String(url || '').replace(/[\t\r\n]/g, '');
    label = clean(label);
    if (!url || !label) return;
    this.out.push('L\t' + this.links + '\t' + url + '\t' + label);
    this.links++;
    this.lastText = '';
  }
}

// ------------------------------------------------------------ HTML convert

// Combined pass: anchors become link lines, block boundaries become line
// breaks, everything else collapses into running text.
const RX = new RegExp(
  '<a\\b([^>]*)>([\\s\\S]*?)<\\/a>' +
  '|<br\\s*\\/?>|<hr\\s*\\/?>' +
  '|<\\/(p|div|h[1-6]|li|tr|section|article|aside|header|footer|nav|ul|ol|table|blockquote|pre|form|figcaption|dt|dd|address)\\b[^>]*>' +
  '|<[^>]+>',
  'gi');

function htmlToLines(html, finalUrl) {
  let title = '';
  const tm = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html);
  if (tm) title = clean(stripTags(tm[1]));

  let body = html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<head\b[^>]*>[\s\S]*?<\/head>/gi, ' ')
    .replace(/<script\b[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style\b[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript\b[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<svg\b[\s\S]*?<\/svg>/gi, ' ')
    .replace(/<template\b[\s\S]*?<\/template>/gi, ' ');
  const bm = /<body[^>]*>([\s\S]*?)<\/body>/i.exec(body);
  if (bm) body = bm[1];

  const doc = new Doc();
  let buf = '';
  let last = 0;
  let m;
  const flush = () => { doc.text(buf); buf = ''; };
  while ((m = RX.exec(body)) !== null) {
    buf += body.slice(last, m.index);
    last = RX.lastIndex;
    if (m[1] !== undefined) {
      // anchor
      const href = attr(m[1], 'href');
      const label = stripTags(m[2]);
      const abs = resolveUrl(href, finalUrl);
      if (abs && clean(label)) {
        flush();
        doc.link(abs, label);
      } else {
        buf += ' ' + label + ' ';
      }
    } else if (m[3] !== undefined || /^<(br|hr)/i.test(m[0])) {
      flush();
    } else {
      buf += ' ';
    }
  }
  buf += body.slice(last);
  flush();
  return { title, lines: doc.out };
}

// ------------------------------------------------------------------ pages

function home() {
  const doc = new Doc();
  doc.text('MIDBrowse \u2014 a small web browser for J2ME phones.');
  doc.text('The phone talks to this server on your PC; the server fetches real websites and sends back simple text and links.');
  doc.text('Use Menu > Open URL to type an address, or Menu > Search to search.');
  doc.text('On screen: Up/Down moves the cursor, Select opens a link, digits 1-9 jump to a link, Left/Right scrolls.');
  doc.text('Try these:');
  doc.link('https://en.m.wikipedia.org/wiki/Main_Page', 'Wikipedia (mobile)');
  doc.link('https://news.ycombinator.com/', 'Hacker News');
  doc.link('https://example.com/', 'Example Domain');
  doc.link('https://lite.duckduckgo.com/lite/', 'DuckDuckGo Lite');
  doc.link('https://www.bbc.com/news', 'BBC News');
  return proto(doc.out, 'MIDBrowse home', '', 'home');
}

async function handlePage(u) {
  const target = u.searchParams.get('url');
  if (!target) return home();

  let abs;
  try {
    abs = new URL(target).toString();
  } catch {
    return protoErr('Invalid URL: ' + target);
  }
  if (!/^https?:/i.test(abs)) return protoErr('Only http/https URLs are supported');

  let resp;
  try {
    resp = await fetch(abs, {
      redirect: 'follow',
      signal: AbortSignal.timeout(20000),
      headers: {
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5',
        'Accept-Language': 'en-US,en;q=0.8'
      }
    });
  } catch (e) {
    const m = (e && (e.cause && e.cause.message || e.message)) || String(e);
    return protoErr('Fetch failed: ' + m);
  }

  const finalUrl = resp.url || abs;
  const ct = resp.headers.get('content-type') || '';
  const status = resp.status + ' ' + (resp.statusText || '').trim();

  let buf = await resp.arrayBuffer();
  if (buf.byteLength > MAX_BYTES) buf = buf.slice(0, MAX_BYTES);
  const body = decodeBody(buf, ct);

  const looksHtml = /text\/html|xhtml/i.test(ct) || /^\s*(<!doctype\s+html|<html[\s>])/i.test(body);
  if (looksHtml) {
    const r = htmlToLines(body, finalUrl);
    if (!r.lines.length && resp.status >= 400) {
      return protoErr('HTTP ' + status + ' (no readable content)');
    }
    return proto(r.lines, r.title || finalUrl, finalUrl, status);
  }

  const looksJson = /json/i.test(ct) || /^\s*[\[{]/.test(body);
  if (looksJson) {
    const doc = new Doc();
    const flat = body.replace(/[\t\r\n]+/g, ' ');
    for (let i = 0; i < flat.length; i += 400) doc.text(flat.substr(i, 400));
    if (!doc.out.length) doc.text('(empty)');
    return proto(doc.out, finalUrl, finalUrl, status);
  }

  if (/text\/plain|text\/|\bxml\b/i.test(ct) || !ct) {
    const doc = new Doc();
    for (const line of body.split(/\r?\n/)) doc.text(line);
    if (!doc.out.length) doc.text('(empty response)');
    return proto(doc.out, finalUrl, finalUrl, status);
  }

  return protoErr('Unsupported content type: ' + ct);
}

module.exports = {
  UA, proto, protoErr, clean, stripTags, decodeEntities, decodeBody,
  Doc, resolveUrl, attr, htmlToLines, home, handlePage
};
