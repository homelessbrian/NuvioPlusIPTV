'use strict';
// Loads playlist / guide sources (URL or local file) with timeouts and size limits.
const fs = require('node:fs');
const { Readable } = require('node:stream');
const { parseM3u, groupChannels } = require('./m3u');
const { parseXmltvStream } = require('./xmltv');

const MAX_PLAYLIST_BYTES = 150 * 1024 * 1024;
const FETCH_TIMEOUT_MS = 90000;

function describeSource(src) {
  if (!src || !src.value || !src.value.trim()) return null;
  const value = src.value.trim();
  if (src.kind === 'file') return { kind: 'file', value };
  if (!/^https?:\/\//i.test(value)) throw new Error('Source must be an http(s) URL or a local file.');
  return { kind: 'url', value };
}

async function openStream(src) {
  if (src.kind === 'file') return { stream: fs.createReadStream(src.value), gzip: /\.gz$/i.test(src.value) };
  const res = await fetch(src.value, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS), redirect: 'follow' });
  if (!res.ok) throw new Error(`Server answered ${res.status} ${res.statusText}`.trim());
  const gzip = /\.gz(\?|$)/i.test(src.value) || /gzip/i.test(res.headers.get('content-type') || '');
  return { stream: Readable.fromWeb(res.body), gzip };
}

async function readLimited(stream, limit) {
  const chunks = [];
  let size = 0;
  for await (const c of stream) {
    size += c.length;
    if (size > limit) { stream.destroy(); throw new Error('Playlist is larger than the 150 MB limit.'); }
    chunks.push(c);
  }
  return Buffer.concat(chunks).toString('utf8');
}

async function loadPlaylist(rawSrc) {
  const src = describeSource(rawSrc);
  if (!src) throw new Error('No playlist configured.');
  const { stream } = await openStream(src);
  const text = await readLimited(stream, MAX_PLAYLIST_BYTES);
  const channels = parseM3u(text);
  if (!channels.length) throw new Error('The playlist has no playable channels.');
  return { channels, groups: groupChannels(channels).map((g) => ({ name: g.name, count: g.channels.length })) };
}

async function loadGuide(rawSrc, now = Date.now()) {
  const src = describeSource(rawSrc);
  if (!src) return null;
  const { stream, gzip } = await openStream(src);
  const window = { fromMs: now - 6 * 3600e3, toMs: now + 48 * 3600e3 };
  return parseXmltvStream(stream, window, { gzip });
}

module.exports = { loadPlaylist, loadGuide, describeSource };
