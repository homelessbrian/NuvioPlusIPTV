'use strict';
const fs = require('node:fs');
const path = require('node:path');

const RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

// Safe single path component for Windows (and everywhere else).
function sanitizeName(s, max = 80) {
  let t = String(s == null ? '' : s).normalize('NFC')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f<>:"/\|?*]+/g, '_')
    .replace(/\s+/g, ' ')
    .replace(/_{2,}/g, '_')
    .trim();
  if (t.length > max) t = t.slice(0, max);
  t = t.replace(/[. ]+$/g, '').replace(/^[. ]+/, '');
  if (!t) return 'Recording';
  if (RESERVED.test(t.split('.')[0])) t = '_' + t;
  return t;
}

const pad2 = (n) => String(n).padStart(2, '0');
function stamp(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}-${pad2(d.getMinutes())}`;
}

// "<Channel> - <Title> - 2026-11-16 20-00.ts", made unique against existing files.
function buildFileName(folder, channelName, title, startMs, ext, part, exists = fs.existsSync) {
  const base = [sanitizeName(channelName, 40), title ? sanitizeName(title, 60) : null, stamp(startMs)].filter(Boolean).join(' - ')
    + (part > 1 ? ` part${part}` : '');
  let name = `${base}.${ext}`;
  for (let i = 2; exists(path.join(folder, name)) && i < 1000; i++) name = `${base} (${i}).${ext}`;
  return path.join(folder, name);
}

function ffmpegPath(existsSync = fs.existsSync) {
  try {
    let p = require('ffmpeg-static');
    if (p) {
      p = p.replace(/app\.asar([\/])/, 'app.asar.unpacked$1');
      if (existsSync(p)) return p;
    }
  } catch { /* fall through */ }
  return 'ffmpeg';
}

function normTitle(s) {
  return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

module.exports = { sanitizeName, buildFileName, ffmpegPath, normTitle, stamp };
