'use strict';
// Opens the configured guide source (URL or file, plain or .gz) as a stream of UTF-8 text chunks.
const fs = require('node:fs');
const zlib = require('node:zlib');
const { Readable } = require('node:stream');
const { describeSource } = require('./sources');

const HEADER_TIMEOUT_MS = 90000;

// settings.guide, or the Xtream provider's xmltv.php when no guide is configured.
function guideSource(settings) {
  const src = describeSource(settings && settings.guide);
  if (src) return src;
  const x = settings && settings.xtream;
  if (x && x.server && x.username) {
    const base = /^https?:\/\//i.test(x.server) ? x.server.replace(/\/+$/, '') : 'http://' + x.server.replace(/\/+$/, '');
    return { kind: 'url', value: `${base}/xmltv.php?username=${encodeURIComponent(x.username)}&password=${encodeURIComponent(x.password || '')}` };
  }
  return null;
}

// Identifies the source; a changed local file (size/mtime) counts as a changed source.
function sourceSignature(src) {
  if (!src) return '';
  if (src.kind === 'file') {
    try { const s = fs.statSync(src.value); return `file:${src.value}:${s.size}:${Math.floor(s.mtimeMs)}`; } catch { return `file:${src.value}`; }
  }
  return 'url:' + src.value;
}

async function openRaw(src) {
  if (src.kind === 'file') return fs.createReadStream(src.value);
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), HEADER_TIMEOUT_MS);
  try {
    const res = await fetch(src.value, { signal: ac.signal, redirect: 'follow' });
    if (!res.ok) throw new Error(`Guide server answered ${res.status} ${res.statusText}`.trim());
    return Readable.fromWeb(res.body);
  } finally { clearTimeout(timer); }
}

// Async iterable of strings. gzip is detected from the magic bytes, not the file name.
async function* textChunks(raw) {
  const it = raw[Symbol.asyncIterator]();
  const first = await it.next();
  if (first.done) return;
  const gz = first.value[0] === 0x1f && first.value[1] === 0x8b;
  async function* all() { yield first.value; for (;;) { const n = await it.next(); if (n.done) return; yield n.value; } }
  let src = Readable.from(all(), { objectMode: false });
  if (gz) {
    const gunzip = zlib.createGunzip();
    src.on('error', (e) => gunzip.destroy(e));
    src = src.pipe(gunzip);
  }
  src.setEncoding('utf8');
  for await (const c of src) yield c;
}

async function openGuideText(src) { return textChunks(await openRaw(src)); }

module.exports = { guideSource, sourceSignature, openGuideText, textChunks };
