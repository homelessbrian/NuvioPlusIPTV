'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const mock = require('./xtream-mock');
const X = require('../src/xtream');

const cfg = (m, over) => ({ server: m.url, username: mock.USER, password: mock.PASS, ...over });
const future = () => String(Math.floor(Date.now() / 1000) + 86400);

async function withServer(over, fn) {
  const m = await mock.start(over);
  try { await fn(m); } finally { await m.close(); }
}

test('login success reports expiry, connections, formats, timezone', async () => {
  await withServer({}, async (m) => {
    const i = await X.createClient(cfg(m)).login();
    assert.equal(i.ok, true);
    assert.equal(i.maxConnections, 2);
    assert.equal(i.activeConnections, 1);
    assert.deepEqual(i.formats, ['m3u8', 'ts']);
    assert.equal(i.timezone, 'America/New_York');
    assert.ok(i.expiry > Date.now());
  });
});

test('login failure, expired, disabled give clear errors without the password', async () => {
  await withServer({}, async (m) => {
    await assert.rejects(X.createClient(cfg(m, { password: 'badpw9' })).login(), (e) => e.code === 'auth' && /wrong username or password/.test(e.message));
  });
  await withServer({ user_info: { auth: 1, status: 'Expired', exp_date: '1000' } }, async (m) => {
    await assert.rejects(X.createClient(cfg(m)).login(), (e) => e.code === 'expired');
  });
  await withServer({ user_info: { auth: 1, status: 'Active', exp_date: '1000' } }, async (m) => {
    await assert.rejects(X.createClient(cfg(m)).login(), (e) => e.code === 'expired' && /1970/.test(e.message));
  });
  await withServer({ user_info: { auth: 1, status: 'Banned', exp_date: future() } }, async (m) => {
    await assert.rejects(X.createClient(cfg(m)).login(), (e) => e.code === 'disabled');
  });
  await withServer({ user_info: { auth: 1, status: 'Active', exp_date: null } }, async (m) => {
    assert.equal((await X.createClient(cfg(m)).login()).expiry, null); // unlimited
  });
});

test('errors never contain the password (http error, bad JSON, network, server echo)', async () => {
  const check = (e) => { assert.ok(!e.message.includes(mock.PASS), e.message); assert.ok(!e.message.includes(encodeURIComponent(mock.PASS)), e.message); assert.ok(!JSON.stringify(e).includes(mock.PASS)); return true; };
  await withServer({ status: 500 }, async (m) => { await assert.rejects(X.createClient(cfg(m)).login(), (e) => e.code === 'http' && check(e)); });
  await withServer({ rawResponse: '<html>login</html>' }, async (m) => { await assert.rejects(X.createClient(cfg(m)).login(), (e) => e.code === 'parse' && check(e)); });
  await withServer({ user_info: { auth: 0, message: `bad password ${mock.PASS}` } }, async (m) => {
    await assert.rejects(X.createClient(cfg(m, { password: mock.PASS + 'x' })).login(), check);
  });
  const c = X.createClient({ server: 'http://127.0.0.1:1', username: 'u', password: mock.PASS });
  await assert.rejects(c.login(), (e) => e.code === 'network' && check(e));
  // an injected fetch that throws an error containing the full URL
  const c2 = X.createClient({ server: 'http://h', username: 'u', password: mock.PASS }, { fetch: async (url) => { throw new Error('boom ' + url); } });
  await assert.rejects(c2.login(), (e) => e.code === 'network' && check(e));
  assert.throws(() => X.createClient({ server: '', username: '', password: '' }), (e) => e.code === 'config');
});

test('live streams map to Channel objects in provider order, with catch-up and m3u8 preferred', async () => {
  await withServer({}, async (m) => {
    const ch = await X.createClient(cfg(m)).liveStreams();
    assert.deepEqual(ch.map((c) => c.name), ['News 24', 'Sports One', 'Orphan']);
    const sports = ch[1];
    assert.equal(sports.id, '102');
    assert.equal(sports.tvgId, 'sports1.us');
    assert.equal(sports.group, 'Sports');
    assert.equal(sports.logo, 'http://i/2.png');
    assert.deepEqual(sports.catchup, { type: 'xtream', days: 3, streamId: '102' });
    assert.equal(sports.url, `${m.url}/live/bob/${encodeURIComponent(mock.PASS)}/102.m3u8`);
    assert.equal(ch[0].catchup, undefined);
    assert.equal(ch[0].tvgId, '');
    assert.equal(ch[2].group, 'Uncategorised');
  });
});

test('live format selection honours allowed_output_formats', async () => {
  await withServer({ user_info: { auth: 1, status: 'Active', exp_date: future(), allowed_output_formats: ['ts'] } }, async (m) => {
    const c = X.createClient(cfg(m));
    assert.match((await c.liveStreams())[0].url, /\.ts$/);
    assert.match((await c.liveStreams({ format: 'm3u8' }))[0].url, /\.ts$/); // not offered, so not used
  });
  await withServer({}, async (m) => {
    assert.match((await X.createClient(cfg(m)).liveStreams({ format: 'ts' }))[0].url, /\.ts$/);
  });
});

test('url builders: live, movie, series, xmltv, credentials encoded', async () => {
  const c = X.createClient({ server: 'HTTP://Host.example:8080/c/', username: 'a b', password: 'p/w?' });
  assert.equal(c.liveUrl(5, 'ts'), 'http://Host.example:8080/live/a%20b/p%2Fw%3F/5.ts'.replace('Host.example', 'host.example'));
  assert.equal(c.movieUrl('9', '.mkv'), 'http://host.example:8080/movie/a%20b/p%2Fw%3F/9.mkv');
  assert.equal(c.movieUrl('9'), 'http://host.example:8080/movie/a%20b/p%2Fw%3F/9.mp4');
  assert.equal(c.episodeUrl('77', 'avi'), 'http://host.example:8080/series/a%20b/p%2Fw%3F/77.avi');
  assert.equal(c.xmltvUrl(), 'http://host.example:8080/xmltv.php?username=a+b&password=p%2Fw%3F');
  assert.equal(X.xmltvUrl({ server: 'host:80', username: 'u', password: 'p' }), 'http://host/xmltv.php?username=u&password=p');
  assert.equal(X.xmltvUrl(null), null);
  assert.equal(X.xmltvUrl({ server: 'host', username: '', password: '' }), null);
});

test('credentials can come from a pasted get.php link', () => {
  const n = X.normalizeConfig({ server: 'http://h:80/get.php?username=u1&password=p1&type=m3u', username: '', password: '' });
  assert.deepEqual(n, { server: 'http://h', username: 'u1', password: 'p1' });
});

test('catch-up url: server timezone maths (DST aware), minute rounding, extension', () => {
  const base = { server: 'http://h', username: 'u', password: 'p', streamId: '42' };
  // 2024-01-15 18:30:45Z  == 13:30 in New York (EST), 18:30 in London, 03:30 next day in Tokyo
  const ms = Date.UTC(2024, 0, 15, 18, 30, 45);
  assert.equal(X.buildCatchupUrl({ ...base, startMs: ms, durationMin: 60, timezone: 'America/New_York' }), 'http://h/timeshift/u/p/60/2024-01-15:13-30/42.ts');
  assert.equal(X.buildCatchupUrl({ ...base, startMs: ms, durationMin: 60, timezone: 'Europe/London' }), 'http://h/timeshift/u/p/60/2024-01-15:18-30/42.ts');
  assert.equal(X.buildCatchupUrl({ ...base, startMs: ms, durationMin: 59.6, timezone: 'Asia/Tokyo', ext: 'm3u8' }), 'http://h/timeshift/u/p/60/2024-01-16:03-30/42.m3u8');
  // summer time: same UTC instant, New York is UTC-4
  assert.equal(X.formatCatchupStamp(Date.UTC(2024, 6, 15, 18, 30), 'America/New_York'), '2024-07-15:14-30');
  // midnight must be 00 not 24
  assert.equal(X.formatCatchupStamp(Date.UTC(2024, 0, 15, 0, 5), 'UTC'), '2024-01-15:00-05');
  assert.equal(X.formatCatchupStamp(Date.UTC(2024, 0, 15, 0, 0), 'Nowhere/Invalid').length, 16); // bad zone falls back, no throw
  assert.match(X.buildCatchupUrl({ ...base, startMs: ms, durationMin: 0, timezone: 'UTC' }), /\/p\/1\/2024/); // at least a minute
});

test('client.catchupUrl uses the login timezone and the channel catch-up stream id', async () => {
  await withServer({}, async (m) => {
    const c = X.createClient(cfg(m));
    const [, sports] = await c.liveStreams();
    const url = await c.catchupUrl(sports, Date.UTC(2024, 0, 15, 18, 30), 45);
    assert.equal(url, `${m.url}/timeshift/bob/${encodeURIComponent(mock.PASS)}/45/2024-01-15:13-30/102.ts`);
    assert.match(await c.catchupUrl('102', Date.UTC(2024, 0, 15, 18, 30), 45, { format: 'm3u8' }), /102\.m3u8$/);
  });
});

test('vod and series parsing with provider quirks', async () => {
  await withServer({}, async (m) => {
    const c = X.createClient(cfg(m));
    assert.deepEqual((await c.vodCategories()).map((x) => [x.id, x.name]), [['10', 'Action'], ['11', 'Drama']]);
    const movies = await c.vodStreams();
    const big = movies.find((x) => x.id === '501');
    assert.equal(big.year, 2019); assert.equal(big.rating, 7.5); assert.equal(big.ext, 'mkv'); assert.equal(big.categoryId, '10');
    const quiet = movies.find((x) => x.id === '502'); // missing poster/rating/ext
    assert.equal(quiet.poster, ''); assert.equal(quiet.rating, 0); assert.equal(quiet.ext, 'mp4'); assert.equal(quiet.year, 0);
    assert.equal((await c.vodStreams('10')).length, 1);

    const info = await c.vodInfo('501');
    assert.equal(info.plot, '0'.length ? 'Things explode.' : ''); assert.equal(info.durationSecs, 7200); assert.equal(info.year, 2019);
    assert.equal(info.backdrop, 'http://i/bd.jpg'); assert.equal(info.ext, 'mkv'); assert.equal(info.cast, 'A, B');
    const empty = await c.vodInfo('502'); // provider answered [] instead of {}
    assert.equal(empty.plot, ''); assert.equal(empty.ext, 'mp4'); assert.equal(empty.id, '502');
    assert.equal((await c.vodInfo('nonexistent')).plot, '');

    const [s] = await c.seriesList();
    assert.equal(s.id, '900'); assert.equal(s.rating, 4); assert.equal(s.year, 2015);
    const si = await c.seriesInfo('900');
    assert.equal(si.name, 'Long Show');
    assert.deepEqual(si.seasons.map((x) => x.number), [1, 2]);
    assert.deepEqual(si.seasons[0].episodes.map((e) => [e.id, e.episode, e.ext]), [['7001', 1, 'mkv'], ['7002', 2, 'mp4']]);
    assert.equal(si.seasons[1].name, 'Season 2'); // no season metadata
    assert.equal((await c.seriesInfo('missing')).seasons.length, 0);
  });
});

test('series episodes delivered as an array (and empty {} / []) are handled', async () => {
  await withServer({ series_info: { 1: { info: [], episodes: [[{ id: 5, episode_num: 1, title: 'a', container_extension: 'mp4' }]] }, 2: { episodes: [] }, 3: { episodes: {} } } }, async (m) => {
    const c = X.createClient(cfg(m));
    assert.equal((await c.seriesInfo('1')).seasons[0].episodes[0].id, '5');
    assert.equal((await c.seriesInfo('2')).seasons.length, 0);
    assert.equal((await c.seriesInfo('3')).seasons.length, 0);
  });
});

test('short EPG decodes base64 and returns ms', async () => {
  await withServer({}, async (m) => {
    const [p] = await X.createClient(cfg(m)).shortEpg('102');
    assert.deepEqual(p, { start: 1700000000000, stop: 1700003600000, title: 'Evening News', desc: 'Headlines' });
  });
});

test('channelsFromSettings: unconfigured gives [], configured gives channels', async () => {
  assert.deepEqual(await X.channelsFromSettings(null), []);
  await withServer({}, async (m) => { assert.equal((await X.channelsFromSettings(cfg(m))).length, 3); });
});
