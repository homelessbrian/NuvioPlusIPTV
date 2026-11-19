'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const { Readable } = require('node:stream');
const { GuideDb, norm } = require('../src/guidedb');
const { textChunks, guideSource } = require('../src/guideimport');

const NOW = Date.UTC(2026, 10, 16, 12, 0, 0);
const H = 3600e3;
const ts = (ms) => {
  const d = new Date(ms); const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}00 +0000`;
};
const prog = (ch, s, e, title, desc = '') =>
  `<programme start="${ts(s)}" stop="${ts(e)}" channel="${ch}"><title>${title}</title>${desc ? `<desc>${desc}</desc>` : ''}</programme>`;

function xml(items) {
  return `<?xml version="1.0" encoding="UTF-8"?><tv><channel id="BBC1.uk"><display-name>BBC One</display-name></channel>` +
    `<channel id="sp2"><display-name>Sport 2</display-name></channel>${items.join('')}</tv>`;
}
const baseItems = () => [
  prog('BBC1.uk', NOW - 1 * H, NOW + 1 * H, 'Morning News', 'Headlines &amp; weather'),
  prog('BBC1.uk', NOW + 1 * H, NOW + 2 * H, 'Quiz Show'),
  prog('BBC1.uk', NOW + 2 * H, NOW + 3 * H, 'Café Röstí Special', 'Accents galore'),
  prog('sp2', NOW - 30 * 60e3, NOW + 90 * 60e3, 'Real Madrid v Barcelona', 'La Liga live'),
  prog('sp2', NOW + 3 * H, NOW + 5 * H, 'Surreal Madrid Tales', 'Fiction'),
  prog('sp2', NOW + 5 * H, NOW + 6 * H, 'Tom &amp; Jerry', 'Cartoon'),
  prog('sp2', NOW - 20 * H, NOW - 10 * H, 'Too Old'),
  prog('sp2', NOW + 9 * 24 * H, NOW + 9 * 24 * H + H, 'Too Far'),
];
const chunks = async function* (s) { yield s; };
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'guidedb-'));

async function loaded(items = baseItems(), file = ':memory:') {
  const db = new GuideDb(file);
  await db.replaceFrom(chunks(xml(items)), { now: NOW, signature: 's1' });
  return db;
}

test('norm matches the Android normalisation', () => {
  assert.equal(norm('Tom & Jerry!'), 'tom and jerry');
  assert.equal(norm('  Café—Röstí  '), 'cafe rosti');
  assert.equal(norm('A.B/C'), 'a b c');
});

test('import trims the window, stores channels and now/next', async () => {
  const db = await loaded();
  assert.deepEqual(db.stats(), { channels: 2, programmes: 6 });
  const nn = db.nowNext(['BBC1.uk', 'bbc1.UK', 'nope'], NOW);
  assert.equal(nn['BBC1.uk'].now.title, 'Morning News');
  assert.equal(nn['BBC1.uk'].now.desc, 'Headlines & weather');
  assert.equal(nn['BBC1.uk'].next.title, 'Quiz Show');
  assert.equal(nn['bbc1.UK'].now.title, 'Morning News');
  assert.equal(nn.nope, undefined);
  db.close();
});

test('schedule and range', async () => {
  const db = await loaded();
  const s = db.schedule('BBC1.uk', NOW, NOW + 90 * 60e3);
  assert.deepEqual(s.map((p) => p.title), ['Morning News', 'Quiz Show']);
  assert.equal(db.schedule('BBC1.uk').length, 3);
  const r = db.range(['BBC1.uk', 'sp2', 'zzz'], NOW + 2.5 * H, NOW + 4 * H);
  assert.deepEqual(Object.keys(r).sort(), ['BBC1.uk', 'sp2']);
  assert.equal(r['BBC1.uk'][0].title, 'Café Röstí Special');
  assert.equal(r.sp2[0].title, 'Surreal Madrid Tales');
  db.close();
});

test('search is word-prefix over title and description', async () => {
  const db = await loaded();
  assert.deepEqual(db.search('madr', 10, NOW).map((h) => h.title), ['Real Madrid v Barcelona', 'Surreal Madrid Tales']);
  assert.deepEqual(db.search('la liga', 10, NOW).map((h) => h.tvgId), ['sp2']);
  assert.deepEqual(db.search('cafe', 10, NOW).map((h) => h.title), ['Café Röstí Special']);
  assert.deepEqual(db.search('', 10, NOW), []);
  assert.deepEqual(db.search('"; DROP', 10, NOW), []);
  db.close();
});

test('findByWords: exact phrases, & and accents', async () => {
  const db = await loaded();
  const f = (w, o) => db.findByWords(w, NOW - H, NOW + 24 * H, o).map((h) => h.title);
  assert.deepEqual(f(['real madrid']), ['Real Madrid v Barcelona']); // not "Surreal Madrid"
  assert.deepEqual(f(['madrid']), ['Real Madrid v Barcelona', 'Surreal Madrid Tales']);
  assert.deepEqual(f(['madrid barcelona']), []); // phrase must be contiguous
  assert.deepEqual(f(['tom and jerry']), ['Tom & Jerry']);
  assert.deepEqual(f(['Tom & Jerry']), ['Tom & Jerry']);
  assert.deepEqual(f(['CAFE rosti']), ['Café Röstí Special']);
  assert.deepEqual(f(['Café']), ['Café Röstí Special']);
  assert.deepEqual(f(['la liga']), ['Real Madrid v Barcelona']);
  assert.deepEqual(f(['la liga'], { titleOnly: true }), []);
  assert.deepEqual(f(['weather', 'quiz show']), ['Morning News', 'Quiz Show']);
  assert.deepEqual(f(['headlines and weather']), ['Morning News']); // & in the description
  assert.deepEqual(f([]), []);
  assert.deepEqual(f(['   ', '!!']), []);
  const h = db.findByWords(['real madrid'], NOW, NOW + H)[0];
  assert.equal(h.tvgId, 'sp2');
  assert.deepEqual(db.findByWords(['real madrid'], NOW + 2 * H, NOW + 3 * H), []); // outside window
  db.close();
});

test('findByWords never skips non-ASCII phrases (scan fallback)', async () => {
  const db = await loaded([
    prog('sp2', NOW, NOW + H, 'Спорт сегодня', 'Обзор'),
    prog('sp2', NOW + H, NOW + 2 * H, 'Match of the day'),
  ]);
  assert.deepEqual(db.findByWords(['спорт'], NOW - H, NOW + 5 * H).map((h) => h.title), ['Спорт сегодня']);
  assert.deepEqual(db.findByWords(['спорт', 'match of'], NOW - H, NOW + 5 * H).map((h) => h.title), ['Спорт сегодня', 'Match of the day']);
  assert.deepEqual(db.search('спо', 5, NOW).map((h) => h.title), ['Спорт сегодня']);
  db.close();
});

test('gzip import and persistence across reopen', async () => {
  const dir = tmp();
  const file = path.join(dir, 'g.db');
  let db = new GuideDb(file);
  const gz = zlib.gzipSync(Buffer.from(xml(baseItems())));
  // split the stream into small chunks, also splits multi-byte characters
  const parts = []; for (let i = 0; i < gz.length; i += 37) parts.push(gz.subarray(i, i + 37));
  const stats = await db.replaceFrom(textChunks(Readable.from(parts)), { now: NOW, signature: 'abc' });
  assert.deepEqual(stats, { channels: 2, programmes: 6 });
  assert.equal(db.isFresh('abc', NOW + H), true);
  assert.equal(db.isFresh('other', NOW + H), false);
  assert.equal(db.isFresh('abc', NOW + 13 * H), false);
  db.close();
  db = new GuideDb(file);
  assert.equal(db.nowNext(['sp2'], NOW).sp2.now.title, 'Real Madrid v Barcelona');
  assert.equal(db.findByWords(['real madrid'], NOW, NOW + H).length, 1);
  db.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

test('plain text chunks pass through textChunks', async () => {
  let s = '';
  for await (const c of textChunks(Readable.from([Buffer.from('héllo '), Buffer.from('wörld')]))) s += c;
  assert.equal(s, 'héllo wörld');
});

test('a failed refresh keeps the old guide (file db, readers unaffected during import)', async () => {
  const dir = tmp();
  const db = new GuideDb(path.join(dir, 'g.db'));
  await db.replaceFrom(chunks(xml(baseItems())), { now: NOW, signature: 'good' });
  // bad xml
  await assert.rejects(db.replaceFrom(chunks('<tv><programme start="x"><title>unclosed'), { now: NOW, signature: 'bad' }));
  // stream error half way
  await assert.rejects(db.replaceFrom((async function* () { yield xml([prog('x', NOW, NOW + H, 'Half')]).slice(0, 300); throw new Error('network'); })(), { now: NOW, signature: 'bad2' }));
  // empty guide
  await assert.rejects(db.replaceFrom(chunks('<tv></tv>'), { now: NOW, signature: 'empty' }), /no programmes/);
  assert.deepEqual(db.stats(), { channels: 2, programmes: 6 });
  assert.equal(db.getMeta('signature'), 'good');
  assert.equal(db.nowNext(['BBC1.uk'], NOW)['BBC1.uk'].now.title, 'Morning News');
  assert.equal(db.findByWords(['quiz show'], NOW, NOW + 5 * H).length, 1);
  // readers see the old data while a new import is in flight
  const slow = (async function* () {
    yield xml([prog('new', NOW, NOW + H, 'Brand New')]);
    await new Promise((r) => setTimeout(r, 50));
  })();
  const p = db.replaceFrom(slow, { now: NOW, signature: 'new' });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(db.stats().programmes, 6);
  await p;
  assert.deepEqual(db.stats(), { channels: 1, programmes: 1 });
  assert.equal(db.nowNext(['BBC1.uk'], NOW)['BBC1.uk'], undefined);
  db.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

test('prune drops listings that ended long ago', async () => {
  const db = await loaded();
  db.prune(NOW + 8 * H); // drops everything that ended before NOW+2h
  assert.equal(db.stats().programmes, 3);
  assert.deepEqual(db.findByWords(['real madrid', 'quiz show'], NOW - H, NOW + 24 * H).map((h) => h.title), []);
  assert.equal(db.findByWords(['tom and jerry'], NOW - H, NOW + 24 * H).length, 1);
  db.close();
});

test('guideSource falls back to the Xtream xmltv.php', () => {
  assert.deepEqual(guideSource({ guide: { kind: 'url', value: 'https://a/b.xml' } }), { kind: 'url', value: 'https://a/b.xml' });
  assert.equal(guideSource({}), null);
  assert.equal(guideSource({ xtream: { server: 'http://h:80/', username: 'u', password: 'p w' } }).value, 'http://h:80/xmltv.php?username=u&password=p%20w');
});

test('feature registers the IPC contract', async () => {
  const dir = tmp();
  const handlers = {}; const sent = [];
  const file = path.join(dir, 'guide.xml');
  const now = Date.now();
  fs.writeFileSync(file, xml([prog('BBC1.uk', now - H, now + H, 'Live Now'), prog('BBC1.uk', now + H, now + 2 * H, 'Later')]));
  const ctx = {
    userData: dir, state: {},
    settings: { get: () => ({ guide: { kind: 'file', value: file } }) },
    handle: (c, f) => { handlers[c] = f; }, send: (c, p) => sent.push([c, p]),
  };
  require('../src/features/guide').register(ctx);
  assert.deepEqual(await handlers['guide:load'](), { channels: 1, programmes: 2 });
  assert.equal(sent.length, 1); assert.equal(sent[0][0], 'guide:updated');
  assert.deepEqual(await handlers['guide:load'](), { channels: 1, programmes: 2 }); // cached, no reimport
  assert.equal(sent.length, 1);
  await handlers['guide:load']({ force: true });
  assert.equal(sent.length, 2);
  assert.equal((await handlers['guide:nowNext'](['BBC1.uk']))['BBC1.uk'].now.title, 'Live Now');
  assert.equal((await handlers['guide:schedule']('BBC1.uk', now, now + 30 * 60e3)).length, 1);
  assert.equal((await handlers['guide:range'](['BBC1.uk'], now, now + 3 * H))['BBC1.uk'].length, 2);
  assert.equal((await handlers['guide:search']('later'))[0].title, 'Later');
  assert.equal(require('../src/guidedb').getDb().findByWords(['live now'], now, now + H).length, 1);
  require('../src/guidedb').getDb().close();
  fs.rmSync(dir, { recursive: true, force: true });
});
