'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseM3u, groupChannels } = require('../src/m3u');
const { parseXmltvString, parseXmltvTime, nowNext } = require('../src/xmltv');

const M3U = `#EXTM3U
#EXTINF:-1 tvg-id="espn.us" tvg-logo="http://x/l.png" group-title="Sports",ESPN, HD
http://host/live/espn.m3u8
#EXTINF:-1 tvg-id="" group-title="News",CNN
#EXTVLCOPT:http-user-agent=x
http://host/live/cnn.m3u8
#EXTINF:-1,No Group
https://host/a.ts
#EXTINF:-1,Bad scheme
file:///c:/secret.txt
`;

test('m3u: attributes, names with commas, groups, bad schemes dropped', () => {
  const ch = parseM3u(M3U);
  assert.equal(ch.length, 3);
  assert.equal(ch[0].name, 'ESPN, HD');
  assert.equal(ch[0].tvgId, 'espn.us');
  assert.equal(ch[0].group, 'Sports');
  assert.equal(ch[2].group, 'Ungrouped');
  assert.equal(groupChannels(ch).length, 3);
});

test('m3u: rejects non-playlists', () => {
  assert.throws(() => parseM3u('<html>nope</html>'), /Not an M3U/);
});

test('xmltv: time parsing with offsets', () => {
  assert.equal(parseXmltvTime('20261110180000 +0000'), Date.UTC(2026, 10, 10, 18));
  assert.equal(parseXmltvTime('20261110180000 -0500'), Date.UTC(2026, 10, 10, 23));
  assert.ok(Number.isNaN(parseXmltvTime('garbage')));
});

test('xmltv: window filtering, now/next, channel names', () => {
  const xml = `<?xml version="1.0"?><tv>
  <channel id="espn.us"><display-name>ESPN</display-name></channel>
  <programme start="20261110170000 +0000" stop="20261110180000 +0000" channel="espn.us"><title>Old</title></programme>
  <programme start="20261110180000 +0000" stop="20261110190000 +0000" channel="espn.us"><title>Game &amp; Show</title><desc><![CDATA[Live <now>]]></desc></programme>
  <programme start="20261110190000 +0000" stop="20261110200000 +0000" channel="espn.us"><title>Later</title></programme>
  <programme start="20261201000000 +0000" stop="20261201010000 +0000" channel="espn.us"><title>Far</title></programme>
  </tv>`;
  const at = Date.UTC(2026, 10, 10, 18, 30);
  const r = parseXmltvString(xml, { fromMs: Date.UTC(2026, 10, 10, 17, 30), toMs: Date.UTC(2026, 10, 11) });
  assert.equal(r.channelNames.get('espn.us'), 'ESPN');
  const list = r.programmes.get('espn.us');
  assert.deepEqual(list.map((p) => p.title), ['Old', 'Game & Show', 'Later']);
  const { now, next } = nowNext(list, at);
  assert.equal(now.title, 'Game & Show');
  assert.equal(now.desc, 'Live <now>');
  assert.equal(next.title, 'Later');
});
