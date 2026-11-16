'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../renderer/player.js');

const ts = (n) => { const b = new Uint8Array(188 * n); for (let i = 0; i < n; i++) b[i * 188] = 0x47; return b; };
const txt = (s) => Uint8Array.from(Buffer.from(s));

test('classifyUrl', () => {
  assert.equal(C.classifyUrl('http://h/a/b.m3u8?token=1'), 'hls');
  assert.equal(C.classifyUrl('http://h/live/u/p/12.ts'), 'ts');
  assert.equal(C.classifyUrl('http://h/live/u/p/12'), 'unknown');
  assert.equal(C.classifyUrl('http://h/movie/u/p/12.mkv'), 'file');
  assert.equal(C.classifyUrl('http://h/get.php?username=a&type=m3u'), 'unknown');
  for (const u of ['rtmp://x/y', 'RTSP://x', 'udp://@239.0.0.1:1234', 'rtp://x', 'ftp://x/a']) assert.equal(C.classifyUrl(u), 'unsupported', u);
  assert.equal(C.classifyUrl(''), 'unsupported');
  assert.equal(C.schemeOf('rtmp://a'), 'rtmp');
});

test('sniffBytes', () => {
  assert.equal(C.sniffBytes(ts(5)), 'ts');
  assert.equal(C.sniffBytes(ts(1).slice(0, 100)), 'ts');
  const bad = ts(5); bad[188] = 0; assert.equal(C.sniffBytes(bad), 'unknown');
  assert.equal(C.sniffBytes(txt('#EXTM3U\n#EXT-X-VERSION:3')), 'hls');
  assert.equal(C.sniffBytes(txt('﻿ \n#EXTM3U\n')), 'hls');
  assert.equal(C.sniffBytes(Uint8Array.from([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0])), 'file');
  assert.equal(C.sniffBytes(Uint8Array.from([0x1a, 0x45, 0xdf, 0xa3, 0])), 'file');
  assert.equal(C.sniffBytes(txt('<html>no</html>')), 'unknown');
  assert.equal(C.sniffBytes(txt('<html>'), 'application/vnd.apple.mpegurl'), 'hls');
  assert.equal(C.sniffBytes([], 'video/mp2t'), 'ts');
  assert.equal(C.sniffBytes(null), 'unknown');
});

test('enginePlan: HLS<->TS fallback', () => {
  assert.deepEqual(C.enginePlan('hls'), ['hls', 'mpegts']);
  assert.deepEqual(C.enginePlan('ts'), ['mpegts', 'hls']);
  assert.deepEqual(C.enginePlan('file'), ['native']);
  assert.deepEqual(C.enginePlan('unknown'), ['hls', 'mpegts', 'native']);
});

test('backoffMs grows and caps', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6].map((a) => C.backoffMs(a)), [1000, 2000, 4000, 8000, 15000, 15000]);
  assert.equal(C.backoffMs(0), 1000);
  assert.equal(C.backoffMs(1, 1000, 15000, 0.5), 1100);
});

test('buildSources dedupes and drops junk', () => {
  assert.deepEqual(C.buildSources({ url: ' http://a ', backups: ['http://a', 'http://b', '', null, 'http://b', 'http://c'] }), ['http://a', 'http://b', 'http://c']);
  assert.deepEqual(C.buildSources({ url: 'http://a' }), ['http://a']);
  assert.deepEqual(C.buildSources(null), []);
  assert.equal(C.nextSource(['a', 'b'], 0), 1);
  assert.equal(C.nextSource(['a', 'b'], 1), -1);
});

test('track memory: merge, recency, cap', () => {
  let m = C.rememberTrack({}, 'u1', { a: 'en' });
  m = C.rememberTrack(m, 'u1', { s: 'off' });
  assert.deepEqual(m.u1, { a: 'en', s: 'off' });
  m = C.rememberTrack(m, 'u2', { a: 'fr' });
  m = C.rememberTrack(m, 'u1', { a: 'es' }); // u1 becomes most recent
  assert.deepEqual(Object.keys(m), ['u2', 'u1']);
  let big = {};
  for (let i = 0; i < 520; i++) big = C.rememberTrack(big, 'u' + i, { a: 'en' }, 500);
  assert.equal(Object.keys(big).length, 500);
  assert.ok(!('u0' in big) && 'u519' in big);
});

test('pickTrack matches by language or label', () => {
  const list = [{ id: 0, label: 'English', lang: 'en' }, { id: 1, label: 'Deutsch', lang: 'de' }, { id: 2, label: 'Commentary', lang: '' }];
  assert.equal(C.pickTrack(list, C.trackKey(list[1])), 1);
  assert.equal(C.pickTrack(list, 'commentary'), 2);
  assert.equal(C.pickTrack(list, 'xx'), -1);
  assert.equal(C.pickTrack(list, ''), -1);
});
