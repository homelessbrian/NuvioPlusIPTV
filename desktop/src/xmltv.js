'use strict';
// Streaming XMLTV parser. Keeps only programmes that overlap [fromMs, toMs] so a huge
// guide file never has to fit in memory.
const sax = require('sax');
const zlib = require('node:zlib');

// XMLTV times look like "20261110180000 +0000" (offset optional, minutes/seconds optional).
function parseXmltvTime(s) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})?(\d{2})?(\d{2})?\s*([+-]\d{4}|Z)?/.exec((s || '').trim());
  if (!m) return NaN;
  const [, y, mo, d, h = '00', mi = '00', se = '00', tz] = m;
  let ms = Date.UTC(+y, +mo - 1, +d, +h, +mi, +se);
  if (tz && tz !== 'Z') {
    const sign = tz[0] === '-' ? -1 : 1;
    ms -= sign * (parseInt(tz.slice(1, 3), 10) * 60 + parseInt(tz.slice(3, 5), 10)) * 60000;
  }
  return ms;
}

// onProgramme/onChannel (optional) receive items as they are parsed instead of collecting them in memory.
function createXmltvParser({ fromMs, toMs, maxProgrammes = 400000, onProgramme, onChannel }) {
  const parser = sax.parser(true, { trim: true });
  const programmes = new Map(); // channelId -> [{start, stop, title, desc}]
  const channelNames = new Map(); // channelId -> display name
  let cur = null;
  let curChannel = null;
  let text = '';
  let count = 0;

  parser.onopentag = (node) => {
    text = '';
    if (node.name === 'programme') {
      cur = {
        channel: node.attributes.channel || '',
        start: parseXmltvTime(node.attributes.start),
        stop: parseXmltvTime(node.attributes.stop),
        title: '',
        desc: '',
      };
    } else if (node.name === 'channel') {
      curChannel = { id: node.attributes.id || '', name: '' };
    }
  };
  parser.ontext = (t) => { text += t; };
  parser.oncdata = (t) => { text += t; };
  parser.onclosetag = (name) => {
    if (cur) {
      if (name === 'title' && !cur.title) cur.title = text;
      else if (name === 'desc' && !cur.desc) cur.desc = text;
      else if (name === 'programme') {
        const ok = cur.channel && Number.isFinite(cur.start) && Number.isFinite(cur.stop) &&
          cur.stop > fromMs && cur.start < toMs && (onProgramme || count < maxProgrammes);
        if (ok && onProgramme) {
          onProgramme({ channel: cur.channel, start: cur.start, stop: cur.stop, title: cur.title, desc: cur.desc });
          count++;
        } else if (ok) {
          if (!programmes.has(cur.channel)) programmes.set(cur.channel, []);
          programmes.get(cur.channel).push({ start: cur.start, stop: cur.stop, title: cur.title, desc: cur.desc });
          count++;
        }
        cur = null;
      }
    } else if (curChannel) {
      if (name === 'display-name' && !curChannel.name) curChannel.name = text;
      else if (name === 'channel') {
        if (curChannel.id && curChannel.name) { if (onChannel) onChannel(curChannel.id, curChannel.name); else channelNames.set(curChannel.id, curChannel.name); }
        curChannel = null;
      }
    }
    text = '';
  };
  parser.onerror = (e) => { throw e; };

  return {
    write(chunk) { parser.write(chunk); },
    end() {
      parser.close();
      for (const list of programmes.values()) list.sort((a, b) => a.start - b.start);
      return { programmes, channelNames, count };
    },
  };
}

function parseXmltvString(xml, window) {
  const p = createXmltvParser(window);
  p.write(xml);
  return p.end();
}

// Streams a Buffer/Readable (optionally gzip) through the parser.
async function parseXmltvStream(stream, window, { gzip = false } = {}) {
  const p = createXmltvParser(window);
  const src = gzip ? stream.pipe(zlib.createGunzip()) : stream;
  src.setEncoding('utf8');
  for await (const chunk of src) p.write(chunk);
  return p.end();
}

function nowNext(list, atMs) {
  if (!list) return { now: null, next: null };
  let now = null;
  let next = null;
  for (const p of list) {
    if (p.start <= atMs && p.stop > atMs) now = p;
    else if (p.start > atMs) { next = p; break; }
  }
  return { now, next };
}

module.exports = { parseXmltvTime, createXmltvParser, parseXmltvString, parseXmltvStream, nowNext };
