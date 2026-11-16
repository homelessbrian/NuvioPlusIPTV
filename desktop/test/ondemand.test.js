'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const mock = require('./xtream-mock');
const { createService } = require('../src/features/ondemand');

async function withService(over, fn, xtreamOver) {
  const m = await mock.start(over);
  const x = { server: m.url, username: mock.USER, password: mock.PASS, ...xtreamOver };
  const svc = createService(() => x);
  try { await fn(svc, m, x); } finally { await m.close(); }
}

test('status: not configured / ok / bad login', async () => {
  const none = createService(() => null);
  assert.equal((await none.status()).configured, false);
  await assert.rejects(none.categories('movies'), (e) => e.code === 'config');
  await withService({}, async (svc) => {
    const s = await svc.status();
    assert.equal(s.ok, true); assert.equal(s.maxConnections, 2); assert.equal(s.activeConnections, 1); assert.ok(s.expiry > Date.now());
  });
  await withService({}, async (svc) => {
    const s = await svc.status();
    assert.equal(s.ok, false); assert.equal(s.configured, true); assert.ok(!s.message.includes('nope'));
  }, { password: 'nope' });
});

test('categories carry counts; uncategorised items get their own bucket; movies filter and page', async () => {
  await withService({}, async (svc) => {
    const cats = await svc.categories('movies');
    assert.deepEqual(cats.map((c) => [c.id, c.count]), [['10', 1], ['11', 1], ['', 1]]);
    const all = await svc.movies();
    assert.equal(all.total, 3);
    assert.equal(all.items[0].name, 'Mystery Box'); // newest first
    assert.deepEqual((await svc.movies('10')).items.map((m) => m.id), ['501']);
    assert.deepEqual((await svc.movies('')).total, 3);
    const p = await svc.movies('all', 0);
    assert.equal(p.items.length, 3); assert.equal(p.pageSize, 100);
    assert.equal((await svc.movies('all', 5)).items.length, 0);
    assert.equal((await svc.series('20')).items[0].name, 'Long Show');
  });
});

test('lists are cached (one upstream request for repeated calls) and refresh clears', async () => {
  await withService({}, async (svc, m) => {
    await Promise.all([svc.movies('10'), svc.movies('11'), svc.categories('movies')]);
    assert.equal(m.hits.filter((u) => u.includes('get_vod_streams')).length, 1);
    svc.refresh();
    await svc.movies('10');
    assert.equal(m.hits.filter((u) => u.includes('get_vod_streams')).length, 2);
  });
});

test('info and playUrl', async () => {
  await withService({}, async (svc, m) => {
    assert.equal((await svc.info('movies', '501')).plot, 'Things explode.');
    const p = await svc.playUrl('movie', '501');
    assert.equal(p.url, `${m.url}/movie/bob/${encodeURIComponent(mock.PASS)}/501.mkv`);
    assert.equal(p.name, 'Big Explosion (2019)');
    const e = await svc.playUrl('series', '900', '7001');
    assert.equal(e.url, `${m.url}/series/bob/${encodeURIComponent(mock.PASS)}/7001.mkv`);
    assert.match(e.name, /Long Show S1E1/);
    assert.match((await svc.playUrl('series', '900', 7003, 'mp4')).url, /7003\.mp4$/);
    await assert.rejects(svc.playUrl('series', '900'), /episode/);
  });
});

test('playUrl surfaces an expired account clearly', async () => {
  await withService({ user_info: { auth: 1, status: 'Expired' } }, async (svc) => {
    await assert.rejects(svc.playUrl('movie', '501'), (e) => e.code === 'expired');
  });
});

test('search is client-side, multi-word, case-insensitive, over movies and series', async () => {
  await withService({}, async (svc, m) => {
    assert.deepEqual((await svc.search('explosion big')).movies.map((x) => x.id), ['501']);
    assert.deepEqual((await svc.search('LONG')).series.map((x) => x.id), ['900']);
    assert.deepEqual(await svc.search('x'), { movies: [], series: [], truncated: false });
    assert.equal((await svc.search('zzzz')).movies.length, 0);
    assert.equal(m.hits.filter((u) => u.includes('get_vod_streams')).length, 1);
  });
});

test('changing credentials drops the cache', async () => {
  const m = await mock.start();
  let x = { server: m.url, username: mock.USER, password: mock.PASS };
  const svc = createService(() => x);
  try {
    await svc.movies();
    x = { ...x, password: 'badpw9' };
    await assert.rejects(svc.movies(), (e) => e.code === 'auth' && !e.message.includes('badpw9'));
  } finally { await m.close(); }
});
