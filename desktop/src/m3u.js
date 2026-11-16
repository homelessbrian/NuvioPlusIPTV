'use strict';
// M3U / M3U8 playlist parser. Pure function, no I/O.

function parseAttrs(s) {
  const out = {};
  const re = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  let m;
  while ((m = re.exec(s))) out[m[1].toLowerCase()] = m[2];
  return out;
}

function parseM3u(text) {
  const lines = String(text).replace(/^\uFEFF/, '').split(/\r?\n/);
  if (!lines.some((l) => l.trim().startsWith('#EXTM3U') || l.trim().startsWith('#EXTINF'))) {
    throw new Error('Not an M3U playlist (no #EXTM3U or #EXTINF lines found).');
  }
  const channels = [];
  let pending = null;
  let group = '';
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTINF')) {
      const comma = findNameComma(line);
      const head = comma >= 0 ? line.slice(0, comma) : line;
      const attrs = parseAttrs(head);
      const name = (comma >= 0 ? line.slice(comma + 1).trim() : '') || attrs['tvg-name'] || '';
      pending = {
        name,
        tvgId: attrs['tvg-id'] || '',
        logo: attrs['tvg-logo'] || '',
        group: attrs['group-title'] || group || 'Ungrouped',
      };
    } else if (line.startsWith('#EXTGRP:')) {
      group = line.slice(8).trim();
    } else if (line.startsWith('#')) {
      continue;
    } else if (pending) {
      if (/^(https?|rtmp|rtsp|udp):\/\//i.test(line)) {
        channels.push({ id: String(channels.length), ...pending, url: line });
      }
      pending = null;
    }
  }
  return channels;
}

// The channel name follows the first comma outside quotes (the name itself may contain commas).
function findNameComma(line) {
  let inQuote = false;
  let last = -1;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') inQuote = !inQuote;
    else if (c === ',' && !inQuote) return i;
  }
  return last;
}

function groupChannels(channels) {
  const map = new Map();
  for (const ch of channels) {
    if (!map.has(ch.group)) map.set(ch.group, []);
    map.get(ch.group).push(ch);
  }
  return [...map.entries()].map(([name, list]) => ({ name, channels: list }));
}

module.exports = { parseM3u, groupChannels };
