'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const cat = require('../src/sports/catalog');
const espn = require('../src/sports/espn');
const { createFeeds, ttlFor, backoffMs, POLICY } = require('../src/sports/feeds');
const M = require('../src/sports/matcher');
const { createService } = require('../src/sports/service');

const fx = (n) => JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', 'sports', n), 'utf8'));
const NOW = Date.parse('2026-11-15T18:30:00Z');

// ------------------------------------------------------------------------------------------------ catalogue & text
test('catalogue: leagues, ESPN paths and lookups', () => {
  assert.ok(cat.leagues.length > 200);
  for (const id of ['football/nfl', 'basketball/nba', 'hockey/nhl', 'baseball/mlb', 'soccer/usa.1', 'football/college-football',
    'basketball/mens-college-basketball', 'soccer/eng.1', 'soccer/esp.1', 'soccer/ger.1', 'racing/f1', 'racing/nascar-premier', 'racing/irl',
    'mma/ufc', 'golf/pga', 'tennis/atp', 'rugby/180659', 'cricket/8048']) assert.ok(cat.league(id), id);
  assert.equal(cat.league('football/nfl').espnPath, 'football/nfl');
  assert.equal(cat.league('football/college-football').espnQuery, '&groups=80');
  assert.equal(cat.league('racing/f1').kind, 'EVENT');
  assert.equal(cat.league('guide/boxing').espnPath, null);
  assert.equal(cat.league('soccer/fifa.world').national, true);
  assert.equal(cat.league('soccer/esp.copa_del_rey').minutes, 125);
  assert.equal(cat.league('rugby/270557').minutes, 110);
  assert.equal(new Set(cat.leagues.map((l) => l.id)).size, cat.leagues.length, 'ids are unique');
  assert.ok(cat.leagues.every((l) => cat.SPORTS[l.sport]), 'every league has a known sport');
});

test('norm and hasWord behave like SportsCatalog.norm / hasWord', () => {
  assert.equal(cat.norm('Man. Utd v Spurs!'), ' man utd v spurs ');
  assert.equal(cat.norm('Barnes & Noble'), ' barnes and noble ');
  assert.equal(cat.norm('Atlético  Madrid – Süper Lig'), ' atletico madrid super lig ');
  assert.equal(cat.norm(''), '  ');
  assert.equal(cat.norm('!!!'), '  ');
  assert.equal(cat.norm('Collision ᴸᴵⱽᴱ'), ' collision live ');
  assert.equal(cat.withoutSmallMarks('Collision ᴸᴵⱽᴱ'), 'Collision');
  const t = cat.norm('The Detroit Lions at Chicago Bears');
  assert.ok(cat.hasWord(t, 'lions'));
  assert.ok(cat.hasWord(t, 'detroit lions'));
  assert.ok(!cat.hasWord(t, 'lion'), 'whole words only');
  assert.ok(!cat.hasWord(t, 'ears'));
  assert.ok(!cat.hasWord(t, ''));
  assert.ok(cat.hasWord(' fa cup ', 'fa cup'));
  assert.ok(cat.hasWord(cat.norm('Man City v Arsenal'), 'man city'));
  assert.ok(!cat.hasWord(cat.norm('Manchester'), 'man'));
});

test('teamNames: strong/weak names, club bits, aliases, schools', () => {
  const pl = cat.league('soccer/eng.1');
  const [strong, weak] = cat.teamNames({ fullName: 'Manchester United', name: 'Manchester United', location: 'Manchester' }, pl);
  assert.ok(strong.includes('manchester united') && strong.includes('man utd') && strong.includes('man united'));
  assert.deepEqual(weak, ['manchester']);
  const [s2] = cat.teamNames({ fullName: 'AFC Bournemouth', name: 'Bournemouth', location: 'Bournemouth' }, pl);
  assert.ok(s2.includes('afc bournemouth') && s2.includes('bournemouth'));
  const [s3, w3] = cat.teamNames({ fullName: 'Detroit Lions', name: 'Lions', location: 'Detroit' }, cat.league('football/nfl'));
  assert.deepEqual(s3, ['detroit lions', 'lions']);
  assert.deepEqual(w3, ['detroit']);
  const [s4, w4] = cat.teamNames({ fullName: 'Michigan Wolverines', name: 'Wolverines', location: 'Michigan' }, cat.league('football/college-football'));
  assert.ok(s4.includes('michigan') && w4.includes('wolverines'));
});

test('teamFamily, relatedLeagues, region filtering', () => {
  assert.equal(cat.teamFamily('soccer/eng.1'), 'soccer');
  assert.equal(cat.teamFamily('rugby/267979'), 'rugby');
  assert.equal(cat.teamFamily('football/nfl'), 'football/nfl');
  const rel = cat.relatedLeagues('soccer/eng.1');
  assert.ok(rel.includes('soccer/uefa.champions') && rel.includes('soccer/eng.fa') && !rel.includes('soccer/eng.1'));
  assert.ok(cat.relatedLeagues('soccer/eng.2').includes('soccer/eng.trophy'));
  assert.ok(cat.relatedLeagues('soccer/fifa.world').includes('soccer/uefa.euro'));
  assert.deepEqual(cat.relatedLeagues('football/nfl'), []);
  const us = { region: 'US' };
  const uk = { region: 'UK' };
  assert.ok(cat.shown(cat.league('football/nfl'), us));
  assert.ok(!cat.shown(cat.league('football/nfl'), uk));
  assert.ok(cat.shown(cat.league('soccer/eng.2'), uk));
  // user choices: hide an in-region league, add an out-of-region one
  const hidden = cat.toggleShown(cat.league('football/nfl'), us);
  assert.ok(!cat.shown(cat.league('football/nfl'), hidden));
  const added = cat.toggleShown(cat.league('football/nfl'), uk);
  assert.ok(cat.shown(cat.league('football/nfl'), added));
  assert.equal(cat.ordered(uk)[0].sport, 'FOOTBALL');
  assert.equal(cat.ordered(us)[0].sport, 'AMERICAN_FOOTBALL');
});

test('fightSides, wrestlingHeadline, withoutAirDate', () => {
  const [a, b] = cat.fightSides('Canelo Álvarez vs Christian Mbilli');
  assert.deepEqual(a, ['canelo', 'alvarez']);
  assert.deepEqual(b, ['christian', 'mbilli']);
  assert.equal(cat.fightSides('UFC 310'), null);
  assert.equal(cat.wrestlingHeadline('Collision #168 Fright Night Collision'), 'Fright Night Collision');
  assert.equal(cat.wrestlingHeadline('WWE Friday Night SmackDown'), 'Friday Night SmackDown');
  assert.equal(cat.wrestlingHeadline('RAW #1743'), 'RAW');
  assert.equal(cat.withoutAirDate('NXT - October 20th 2026'), 'NXT');
  assert.equal(cat.withoutAirDate('Raw: 20/10/2026'), 'Raw');
  assert.equal(cat.withoutAirDate('2026-10-20'), '2026-10-20');
});

// ------------------------------------------------------------------------------------------------ ESPN normalisation
test('ESPN NFL scoreboard: live, pre, postponed, final; bad events skipped', () => {
  const l = cat.league('football/nfl');
  const { events, logo } = espn.parseScoreboard(l, fx('nfl-scoreboard.json'), NOW);
  assert.equal(logo, 'https://a.espncdn.com/nfl-dark.png');
  assert.deepEqual(events.map((e) => e.id), ['espn:football/nfl:401', 'espn:football/nfl:402', 'espn:football/nfl:403', 'espn:football/nfl:405']);
  const live = events[0];
  assert.equal(live.state, 'live');
  assert.equal(live.title, 'Lions at Bears');
  assert.equal(live.startMs, Date.parse('2026-11-15T18:00:00Z'));
  assert.equal(live.status, '3rd Quarter · 5:12');
  assert.equal(live.home.team.name, 'Bears');
  assert.equal(live.home.score, '17');
  assert.equal(live.away.score, '24');
  assert.equal(live.away.team.logo, 'https://a.espncdn.com/det.png', 'default (non-dark) logo preferred');
  assert.equal(live.home.team.logo, 'https://a.espncdn.com/chi.png');
  assert.equal(live.home.team.color, '#0b162a');
  assert.deepEqual(live.home.periods, ['7', '3', '7']);
  assert.equal(live.home.record, '4-5');
  assert.equal(live.venue, 'Soldier Field');
  assert.equal(live.subtitle, 'Week 11 · Soldier Field');
  assert.equal(live.situation, 'DET ball · 2nd & 7 at CHI 38');
  assert.ok(Math.abs(live.progress - (2 * 900 + 588) / 3600) < 1e-9);
  assert.deepEqual(live.broadcasts, ['FOX', 'NFL Network', 'NFL+ App'], 'national first, apps last');

  const pre = events[1];
  assert.equal(pre.state, 'pre');
  assert.equal(pre.away.score, null, 'no score before the game');
  assert.equal(pre.status, null);
  assert.deepEqual(pre.broadcasts, ['NBC', 'Peacock']);

  assert.equal(events[2].state, 'off');
  assert.equal(events[2].status, 'Postponed');

  const fin = events[3];
  assert.equal(fin.state, 'post');
  assert.equal(fin.status, 'Final');
  assert.equal(fin.home.winner, true);
  assert.equal(fin.away.winner, false);
  assert.equal(fin.away.score, '10');
});

test('ESPN soccer: home-first title, minute progress, geo broadcasts', () => {
  const { events } = espn.parseScoreboard(cat.league('soccer/eng.1'), fx('soccer-scoreboard.json'), NOW);
  assert.equal(events.length, 1);
  const e = events[0];
  assert.equal(e.title, 'Liverpool v Man United');
  assert.equal(e.state, 'live');
  assert.equal(e.status, "52'");
  assert.ok(Math.abs(e.progress - 52 / 90) < 1e-9);
  assert.deepEqual(e.broadcasts, ['Sky Sports Main Event', 'Peacock']);
});

test('ESPN race weekend: sessions, next session and state', () => {
  const l = cat.league('racing/f1');
  const { events } = espn.parseScoreboard(l, fx('f1-scoreboard.json'), Date.parse('2026-11-14T12:00:00Z'));
  assert.equal(events.length, 1);
  const e = events[0];
  assert.equal(e.title, 'Las Vegas Grand Prix');
  assert.equal(e.sessions.map((s) => s.label).join(','), 'Practice 1,Qualifying,Race');
  assert.equal(e.state, 'pre');
  assert.equal(e.startMs, Date.parse('2026-11-15T04:00:00Z'));
  assert.equal(e.note, 'Race');
  assert.deepEqual(e.broadcasts, ['ESPN']);
  // after the race has run
  const done = espn.parseScoreboard(l, fx('f1-scoreboard.json'), Date.parse('2026-11-16T12:00:00Z')).events[0];
  assert.equal(done.state, 'post');
});

test('ESPN standings: groups, kind and columns', () => {
  const st = espn.parseStandings(cat.league('basketball/nba'), fx('nba-standings.json'), NOW);
  assert.equal(st.kind, 'record');
  assert.equal(st.season, '2026-27');
  assert.equal(st.groups.length, 2);
  assert.equal(st.groups[0].name, 'Eastern Conference');
  assert.equal(st.groups[0].rows[0].shortName, 'Celtics');
  assert.equal(st.groups[0].rows[0].values.gamesBehind, '–');
  assert.deepEqual(st.columns.map((c) => c.label), ['W', 'L', 'PCT', 'GB', 'STRK']);
  assert.equal(espn.parseStandings(cat.league('basketball/nba'), { children: [] }), null);
  assert.match(espn.standingsUrl(cat.league('basketball/nba')), /nba\/standings\?level=3$/);
});

test('ESPN urls and day keys (US Eastern)', () => {
  assert.equal(espn.dayKey(Date.parse('2026-11-16T03:00:00Z')), '20261115');
  assert.equal(espn.dayKey(Date.parse('2026-11-16T06:00:00Z')), '20261116');
  assert.match(espn.scoreboardUrl(cat.league('football/college-football'), '20261110', '20261125'), /football\/college-football\/scoreboard\?dates=20261110-20261125&limit=1000&groups=80$/);
  assert.ok(espn.feedable(cat.league('football/nfl')));
  assert.ok(!espn.feedable(cat.league('guide/boxing')));
  assert.ok(!espn.feedable(cat.league('hockeytech/ahl')));
});

// ------------------------------------------------------------------------------------------------ feeds
function fakeClock(t0) { let t = t0; const f = () => t; f.advance = (ms) => { t += ms; }; return f; }

test('feeds: caching, TTL by liveness, conditional refresh', async () => {
  const clock = fakeClock(NOW);
  const calls = [];
  const fetchJson = async (url, o) => { calls.push({ url, etag: o && o.etag }); return { status: 200, json: fx('nfl-scoreboard.json'), etag: 'W/"1"' }; };
  const feeds = createFeeds({ fetchJson, now: clock });
  assert.equal(await feeds.ensure(['football/nfl'], { deadlineMs: 0 }), true);
  assert.equal(calls.length, 2, 'one wide window + one today request');
  assert.ok(calls.some((c) => /dates=20261107-20261123/.test(c.url)), 'wide window request');
  assert.ok(calls.some((c) => /dates=20261114-20261116/.test(c.url)), 'today request');
  assert.equal(feeds.eventsFor(['football/nfl']).length, 4, 'window and today events merged by id');
  assert.equal(feeds.hasLive(['football/nfl']), true);

  await feeds.ensure(['football/nfl'], { deadlineMs: 0 });
  assert.equal(calls.length, 2, 'fresh data is not fetched again');

  clock.advance(POLICY.liveMs + 1000);
  await feeds.ensure(['football/nfl'], { deadlineMs: 0 });
  assert.equal(calls.length, 3, 'live data is refreshed after ~30 s (today entry only)');
  assert.ok(/dates=20261114-20261116/.test(calls[2].url));
  assert.equal(calls[2].etag, 'W/"1"', 'sends the etag it has');

  // not-live entry: slow refresh
  const idle = { events: [{ state: 'pre', startMs: NOW + 5 * 86400000 }] };
  assert.equal(ttlFor('today', idle, NOW), POLICY.todayMs);
  assert.equal(ttlFor('today', { events: [{ state: 'pre', startMs: NOW + 3600000 }] }, NOW), POLICY.soonMs);
  assert.equal(ttlFor('today', { events: [{ state: 'live', startMs: NOW }] }, NOW), POLICY.liveMs);
  assert.equal(ttlFor('window', idle, NOW), POLICY.windowMs);

  // 304: keeps data, no change event
  let notModified = true;
  const feeds2 = createFeeds({ fetchJson: async () => (notModified ? { status: 304 } : { status: 200, json: fx('nfl-scoreboard.json'), etag: 'x' }), now: clock });
  await feeds2.ensure(['football/nfl'], { deadlineMs: 0 }); // first call: no cache, a 304 without cache is treated as a failure
  assert.equal(feeds2.eventsFor(['football/nfl']).length, 0);
  notModified = false;
  clock.advance(60 * 60 * 1000);
  await feeds2.ensure(['football/nfl'], { deadlineMs: 0 });
  assert.equal(feeds2.eventsFor(['football/nfl']).length, 4);
});

test('feeds: failures back off exponentially and stale data is kept', async () => {
  const clock = fakeClock(NOW);
  let fail = false;
  let n = 0;
  const fetchJson = async () => { n++; if (fail) throw new Error('boom'); return { status: 200, json: fx('soccer-scoreboard.json') }; };
  const feeds = createFeeds({ fetchJson, now: clock });
  await feeds.ensure(['soccer/eng.1'], { deadlineMs: 0 });
  assert.equal(n, 2);
  fail = true;
  clock.advance(POLICY.liveMs + 1);
  await feeds.ensure(['soccer/eng.1'], { deadlineMs: 0 }); // today entry (live) fails
  assert.equal(n, 3);
  assert.equal(feeds.eventsFor(['soccer/eng.1']).length, 1, 'stale data still served');
  await feeds.ensure(['soccer/eng.1'], { deadlineMs: 0 });
  assert.equal(n, 3, 'inside the back-off window nothing is fetched');
  clock.advance(backoffMs(1) + 1);
  await feeds.ensure(['soccer/eng.1'], { deadlineMs: 0 });
  assert.equal(n, 4);
  clock.advance(backoffMs(1) + 1); // second failure doubles the wait, so this is not enough
  await feeds.ensure(['soccer/eng.1'], { deadlineMs: 0 });
  assert.equal(n, 4);
  clock.advance(backoffMs(2));
  fail = false;
  await feeds.ensure(['soccer/eng.1'], { deadlineMs: 0 });
  assert.equal(n, 5);
  assert.equal(feeds.status()['today|soccer/eng.1'].failures, 0, 'success resets the back-off');
  assert.equal(backoffMs(1), POLICY.backoffBase);
  assert.equal(backoffMs(2), POLICY.backoffBase * 2);
  assert.equal(backoffMs(40), POLICY.backoffMax);
});

test('feeds: a hung request is cut by the deadline and a never-fetched league backs off', async () => {
  const feeds = createFeeds({ fetchJson: () => new Promise(() => {}), now: Date.now });
  const t = Date.now();
  await feeds.ensure(['football/nfl'], { deadlineMs: 50 });
  assert.ok(Date.now() - t < 1000);
  const bad = createFeeds({ fetchJson: async () => { throw new Error('offline'); }, now: Date.now });
  await bad.ensure(['football/nfl'], { deadlineMs: 0 });
  assert.equal(bad.hasData('football/nfl'), false);
  assert.deepEqual(bad.eventsFor(['football/nfl']), []);
});

test('feeds: disk cache survives a restart', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sports-'));
  try {
    const clock = fakeClock(NOW);
    const a = createFeeds({ fetchJson: async () => ({ status: 200, json: fx('nfl-scoreboard.json') }), dir, now: clock });
    await a.ensure(['football/nfl'], { deadlineMs: 0 });
    a.flush();
    assert.ok(fs.existsSync(path.join(dir, 'sports-cache.json')));
    let called = 0;
    const b = createFeeds({ fetchJson: async () => { called++; return { status: 200, json: { events: [] } }; }, dir, now: clock });
    assert.equal(b.eventsFor(['football/nfl']).length, 4, 'loaded from disk before any network');
    assert.equal(b.hasData('football/nfl'), true);
    await b.ensure(['football/nfl'], { deadlineMs: 0 });
    assert.equal(called, 0, 'still fresh');
    // a corrupt cache file is ignored
    fs.writeFileSync(path.join(dir, 'sports-cache.json'), '{nope');
    const c = createFeeds({ fetchJson: async () => ({ status: 200, json: { events: [] } }), dir, now: clock });
    assert.deepEqual(c.eventsFor(['football/nfl']), []);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('feeds: standings are cached and stale on failure', async () => {
  const clock = fakeClock(NOW);
  let n = 0; let fail = false;
  const feeds = createFeeds({ fetchJson: async () => { n++; if (fail) throw new Error('x'); return { status: 200, json: fx('nba-standings.json') }; }, now: clock });
  const l = cat.league('basketball/nba');
  const a = await feeds.standings(l);
  assert.equal(a.groups.length, 2);
  await feeds.standings(l);
  assert.equal(n, 1);
  clock.advance(POLICY.standingsMs + 1);
  fail = true;
  const b = await feeds.standings(l);
  assert.equal(b.groups.length, 2, 'stale table served on failure');
});

// ------------------------------------------------------------------------------------------------ matcher
const ch = (name, tvgId, extra = {}) => ({ id: name, name, tvgId: tvgId || '', logo: '', group: extra.group || 'Sports', url: 'http://x/' + encodeURIComponent(name) });

function fakeGuide(programs, rev = 1) {
  const g = {
    calls: 0, rev,
    revision() { return g.rev; },
    findByWords(words, from, to) {
      g.calls++;
      return programs.filter((p) => p.start < to && p.stop > from).filter((p) => {
        const t = cat.norm(p.title + ' ' + (p.desc || ''));
        return words.some((w) => cat.hasWord(t, cat.norm(w).trim()));
      });
    },
  };
  return g;
}

function lionsBears() {
  return espn.parseScoreboard(cat.league('football/nfl'), fx('nfl-scoreboard.json'), NOW).events[0];
}

test('matcher: guide match scores, replay/highlights excluded, 24/7 filler excluded, SD/HD copies grouped, best first', async () => {
  const start = lionsBears().startMs;
  const prog = (tvgId, title, over = {}) => ({ tvgId, start: start - 10 * 60000, stop: start + 200 * 60000, title, desc: '', ...over });
  const guide = fakeGuide([
    prog('fox5', 'NFL Football: Detroit Lions at Chicago Bears'),
    prog('fox5sd', 'NFL Football: Detroit Lions at Chicago Bears'),
    prog('espn2', 'Lions at Bears Highlights'),
    prog('espn3', 'Detroit Lions vs Chicago Bears - Replay'),
    prog('loop', 'Detroit Lions at Chicago Bears'),
    prog('nfl247', 'Detroit Lions at Chicago Bears'),
    prog('late', 'Detroit Lions at Chicago Bears', { start: start + 150 * 60000, stop: start + 330 * 60000 }),
    prog('onlylions', 'Lions pre-game show', { desc: 'The Chicago Bears visit' }),
    prog('other', 'Buffalo Bills at Kansas City Chiefs'),
  ]);
  const channels = [
    ch('US: FOX 5 HD', 'fox5'), ch('US: FOX 5 SD', 'fox5sd'), ch('US: ESPN 2', 'espn2'), ch('US: ESPN 3', 'espn3'),
    ch('Loop channel', 'loop'), ch('NFL 24/7', 'nfl247'), ch('Late feed', 'late'), ch('Sports Talk', 'onlylions'), ch('Other', 'other'),
    ch('NFL 01 - Lions vs Bears', ''), ch('Fox News', 'foxnews'), ch('##### USA SPORTS #####', ''),
  ];
  const matcher = M.createMatcher({ getGuide: () => guide, now: () => NOW });
  const res = await matcher.match({ events: [lionsBears()], channels, settings: { region: 'US' } });
  const list = res.get(lionsBears().id);
  const byName = Object.fromEntries(list.map((m) => [m.channel.name, m]));
  assert.equal(byName['US: FOX 5 HD'].score, 100);
  assert.equal(byName['US: FOX 5 HD'].how, 'Guide: NFL Football: Detroit Lions at Chicago Bears');
  assert.equal(byName['US: FOX 5 SD'].score, 100);
  assert.equal(byName['NFL 01 - Lions vs Bears'].score, 95);
  assert.equal(byName['NFL 01 - Lions vs Bears'].how, 'Channel name');
  assert.equal(byName['Sports Talk'].score, 82, 'one team in the title, the other in the description');
  assert.ok(!byName['US: ESPN 2'], 'highlights are not the game');
  assert.ok(!byName['US: ESPN 3'], 'replays are not the game');
  assert.ok(!byName['NFL 24/7'], '24/7 channel is filler');
  assert.ok(!byName['Late feed'], 'show starting 2.5 hours after kick-off is not this game');
  assert.ok(!byName.Other && !byName['Fox News']);
  // best first: HD guide match leads; the SD copy follows; both are "sure"
  assert.equal(list[0].channel.name, 'US: FOX 5 HD');
  assert.ok(list.findIndex((m) => m.channel.name === 'US: FOX 5 SD') > 0);
  assert.ok(list[0].sure);
  // FHD/HD/SD duplicates fold into one row
  const groups = M.groupCopies(list);
  const g = groups.find((x) => x[0].channel.name.startsWith('US: FOX 5'));
  assert.deepEqual(g.map((m) => m.channel.name), ['US: FOX 5 HD', 'US: FOX 5 SD']);
  assert.ok(groups.length < list.length);
});

test('matcher: a time offset (score delay / stream shift) moves the guide window', async () => {
  const ev = lionsBears();
  const guide = fakeGuide([{ tvgId: 'a', start: ev.startMs - 120 * 60000, stop: ev.startMs - 60 * 60000, title: 'Detroit Lions at Chicago Bears' }]);
  const m = M.createMatcher({ getGuide: () => guide, now: () => NOW });
  const channels = [ch('Chan A', 'a')];
  const r0 = await m.match({ events: [ev], channels, settings: {} });
  assert.equal(r0.get(ev.id), undefined, 'a show that ended an hour before kick-off is not it');
});

test('matcher: results cached per guide revision, recomputed when the guide changes', async () => {
  const ev = lionsBears();
  const guide = fakeGuide([{ tvgId: 'a', start: ev.startMs, stop: ev.startMs + 3 * 3600000, title: 'Detroit Lions at Chicago Bears' }], 7);
  const m = M.createMatcher({ getGuide: () => guide, now: () => NOW });
  const channels = [ch('Chan A', 'a')];
  await m.match({ events: [ev], channels, settings: {} });
  const calls = guide.calls;
  assert.ok(calls >= 1);
  const again = await m.match({ events: [ev], channels, settings: {} });
  assert.equal(guide.calls, calls, 'same revision and channels: the half-hour is not searched again');
  assert.equal(again.get(ev.id).length, 1);
  guide.rev = 8;
  await m.match({ events: [ev], channels, settings: {} });
  assert.ok(guide.calls > calls, 'a new guide revision is searched again');
  const calls2 = guide.calls;
  await m.match({ events: [ev], channels: [...channels, ch('Chan B', 'b')], settings: {} });
  assert.ok(guide.calls > calls2, 'a changed channel list is searched again');
});

test('matcher: works with no guide at all (names and networks only), and a throwing guide', async () => {
  const ev = lionsBears();
  const channels = [ch('NFL 01 - Lions vs Bears', ''), ch('Plain', '')];
  for (const getGuide of [() => null, () => { throw new Error('no db'); }, () => ({ revision: () => 1, findByWords() { throw new Error('locked'); } })]) {
    const m = M.createMatcher({ getGuide, now: () => NOW });
    const r = await m.match({ events: [ev], channels, settings: {} });
    assert.equal(r.get(ev.id).length, 1);
    assert.equal(r.get(ev.id)[0].how, 'Channel name');
  }
});

test('matcher: network listed by ESPN needs a sports show on now (names alone give a national network a low score)', async () => {
  const ev = lionsBears(); // FOX / NFL Network
  const channels = [ch('US: FOX 5 HD', 'fox5'), ch('US: FOX NEWS', 'foxnews'), ch('NFL Network HD', ''), ch('FOX Sports 1', 'fs1')];
  const guide = fakeGuide([{ tvgId: 'fox5', start: ev.startMs - 30 * 60000, stop: ev.startMs + 4 * 3600000, title: 'NFL Football' }]);
  const m = M.createMatcher({ getGuide: () => guide, now: () => NOW });
  const r = await m.match({ events: [ev], channels, settings: {} });
  const by = Object.fromEntries(r.get(ev.id).map((x) => [x.channel.name, x]));
  assert.equal(by['US: FOX 5 HD'].score, 52, 'FOX is local (not in the national list): 52');
  assert.ok(/FOX listed by ESPN/.test(by['US: FOX 5 HD'].how));
  assert.equal(by['NFL Network HD'].score, 45, 'no guide id: listed network, national');
  assert.ok(!by['US: FOX NEWS'], 'FOX News is not FOX');
  assert.ok(!by['FOX Sports 1'], 'a channel with a guide that shows something else is not it');
});

test('matcher: race weekend found by the competition and event words', async () => {
  const l = cat.league('racing/f1');
  const ev = espn.parseScoreboard(l, fx('f1-scoreboard.json'), Date.parse('2026-11-14T12:00:00Z')).events[0];
  const now = Date.parse('2026-11-14T12:00:00Z');
  const guide = fakeGuide([
    { tvgId: 'sky', start: ev.startMs, stop: ev.startMs + 2 * 3600000, title: 'Formula 1: Las Vegas Grand Prix' },
    { tvgId: 'rep', start: ev.startMs, stop: ev.startMs + 2 * 3600000, title: 'Formula 1: Las Vegas Grand Prix Highlights' },
  ]);
  const m = M.createMatcher({ getGuide: () => guide, now: () => now });
  const r = await m.match({ events: [ev], channels: [ch('Sky Sports F1', 'sky'), ch('Other Channel', 'rep'), ch('Sky Sports F1 HD', '')], settings: {} });
  const names = r.get(ev.id).map((x) => x.channel.name);
  assert.ok(names.includes('Sky Sports F1'));
  assert.ok(!names.includes('Other Channel'), 'a highlights show is not the race');
});

test('matcher: term scoring (Kotlin Terms.baseScore)', () => {
  const ev = lionsBears();
  const t = M.termsFor(ev);
  const s = (title, desc = '') => t.scoreText(cat.norm(title), cat.norm(desc));
  assert.equal(s('Detroit Lions at Chicago Bears'), 100);
  assert.equal(s('Lions at Bears'), 100);
  assert.equal(s('Lions at Chicago'), 90, 'one strong name + the other team\'s city');
  assert.equal(s('NFL Football: Lions', 'vs the Bears'), 82);
  assert.equal(s('NFL: Lions game'), 58, 'one team and the league');
  assert.equal(s('Lions'), 0);
  assert.equal(s('Buffalo Bills at Kansas City Chiefs'), 0);
  assert.ok(t.otherGame(cat.norm('Bills at Chiefs')));
  assert.ok(!t.otherGame(cat.norm('Lions at Packers')));
  assert.ok(t.inName(cat.norm('DET v CHI')));
  assert.ok(t.inName(cat.norm('01 - Lions vs Bears HD')));
  assert.ok(!t.inName(cat.norm('Lions fan channel')));
});

test('matcher: helpers (banner, quality, copy key, preference, region)', () => {
  assert.ok(M.isBanner('##### USA SPORTS #####'));
  assert.ok(M.isBanner('▬▬▬ UK ▬▬▬'));
  assert.ok(!M.isBanner('ESPN'));
  assert.ok(!M.isBanner('FS1'));
  assert.equal(M.nameQuality('ESPN FHD'), 'FHD');
  assert.equal(M.nameQuality('ESPN 4K'), ' 4K'.trim());
  assert.equal(M.nameQuality('ESPN'), null);
  assert.equal(M.copyKey({ name: 'US: ESPN FHD' }), M.copyKey({ name: 'US: ESPN SD' }));
  assert.notEqual(M.copyKey({ name: 'UK: ESPN HD' }), M.copyKey({ name: 'US: ESPN HD' }));
  assert.equal(M.channelRegionCode({ name: 'UK: Sky Sports', group: '' }), 'UK');
  assert.equal(M.channelRegionCode({ name: 'ES: ESPN', group: '' }), 'lang:es');
  const rank = M.preferenceRank({ region: 'UK', preferredGroups: ['My Sports'] }, 'en-GB');
  assert.equal(rank({ name: 'X', group: 'My Sports' }), 2);
  assert.equal(rank({ name: 'UK: Sky', group: 'Sports' }), 1);
  assert.equal(rank({ name: 'ES: ESPN', group: 'Sports' }), -1);
  assert.equal(rank({ name: 'Sky', group: 'Sports' }), 0);
  const list = [
    { channel: { name: 'B', number: 2 }, score: 100, quality: 'SD' }, { channel: { name: 'A', number: 1 }, score: 100, quality: 'FHD' },
    { channel: { name: 'Mine', number: 3 }, score: 200, quality: null }, { channel: { name: 'Maybe', number: 4 }, score: 45, quality: '4K' },
  ];
  assert.deepEqual(M.sortMatches(list, true).map((m) => m.channel.name), ['Mine', 'A', 'B', 'Maybe']);
});

test('matcher: 25,000 channels, still correct, and the event loop is never blocked for long', async () => {
  const ev = lionsBears();
  const channels = [];
  for (let i = 0; i < 25000; i++) channels.push(ch(`US: Channel ${i} ${i % 3 ? 'HD' : 'SD'} ${['Movies', 'News', 'Kids', 'Music'][i % 4]}`, `tvg${i}`));
  channels.push(ch('NFL 07 - Detroit Lions vs Chicago Bears', 'special'));
  const programs = [];
  for (let i = 0; i < 25000; i += 1) programs.push({ tvgId: `tvg${i}`, start: ev.startMs, stop: ev.startMs + 7200000, title: `Show ${i}`, desc: '' });
  programs.push({ tvgId: 'tvg777', start: ev.startMs, stop: ev.startMs + 3 * 3600000, title: 'Lions at Bears' });
  const guide = {
    revision: () => 1,
    findByWords(words, from, to) { // like an index lookup: only programmes naming a word
      return programs.filter((p) => p.start < to && p.stop > from && words.some((w) => cat.hasWord(cat.norm(p.title), cat.norm(w).trim())));
    },
  };
  const m = M.createMatcher({ getGuide: () => guide, now: () => NOW });
  let maxGap = 0; let last = Date.now(); let ticks = 0;
  const iv = setInterval(() => { const n = Date.now(); maxGap = Math.max(maxGap, n - last); last = n; ticks++; }, 5);
  const t0 = Date.now();
  const r = await m.match({ events: [ev], channels, settings: {} });
  clearInterval(iv);
  const names = r.get(ev.id).map((x) => x.channel.name);
  assert.ok(names.includes('NFL 07 - Detroit Lions vs Chicago Bears'));
  assert.ok(names.includes('US: Channel 777 SD News'), 'found through the guide');
  assert.ok(ticks > 3, 'the event loop kept running during matching (ticks=' + ticks + ')');
  assert.ok(maxGap < 400, 'no long blocking gap: ' + maxGap + ' ms (total ' + (Date.now() - t0) + ' ms)');
});

// ------------------------------------------------------------------------------------------------ service
function makeService({ channels = [], guide = null, hide = false, region = 'US' } = {}) {
  const store = { features: { sports: { region, hideScores: hide } } };
  const sent = [];
  const clock = () => NOW;
  const fetchJson = async (url) => {
    if (/football\/nfl\/scoreboard/.test(url)) return { status: 200, json: fx('nfl-scoreboard.json') };
    if (/soccer\/eng\.1\/scoreboard/.test(url)) return { status: 200, json: fx('soccer-scoreboard.json') };
    if (/racing\/f1\/scoreboard/.test(url)) return { status: 200, json: fx('f1-scoreboard.json') };
    if (/standings/.test(url)) return { status: 200, json: fx('nba-standings.json') };
    return { status: 200, json: { events: [] } };
  };
  const svc = createService({
    getSettings: () => store,
    saveSettings: (p) => { Object.assign(store, p); return store; },
    getChannels: () => channels, getGuide: () => guide, send: (c, p) => sent.push([c, p]), now: clock, fetchJson, pollMs: 1e9,
  });
  return { svc, store, sent };
}

test('service: events by day, league filter, hide scores, follow/unfollow persisted', async () => {
  const { svc, store, sent } = makeService();
  const all = [];
  for (const off of [-1, 0, 1]) all.push(...(await svc.eventsForDay(off)).events);
  const ids = all.map((e) => e.id);
  assert.ok(ids.includes('espn:football/nfl:401') && ids.includes('espn:football/nfl:402'));
  assert.ok(!ids.includes('espn:football/nfl:403'), 'postponed games are not listed');
  const lions = all.find((e) => e.id === 'espn:football/nfl:401');
  assert.equal(lions.away.score, '24');
  assert.equal(lions.leagueName, 'NFL');
  assert.equal(lions.leagueLogo, 'https://a.espncdn.com/nfl-dark.png');
  assert.equal(lions.followed, false);
  const only = await svc.eventsForDay(0, { leagueId: 'soccer/eng.1' });
  assert.ok(only.events.every((e) => e.leagueId === 'soccer/eng.1'));

  svc.follow({ kind: 'team', leagueId: 'football/nfl', teamId: '8', name: 'Lions', abbr: 'DET', color: '#0076b6', logo: 'x.png' });
  svc.follow({ kind: 'team', leagueId: 'football/nfl', teamId: '8', name: 'Lions' }); // duplicate ignored
  svc.follow({ kind: 'league', id: 'racing/f1' });
  svc.follow({ kind: 'name', word: 'Chiefs' });
  const f = svc.getFollows();
  assert.equal(f.teams.length, 1);
  assert.deepEqual(f.leagues, ['racing/f1']);
  assert.deepEqual(f.words, ['Chiefs']);
  assert.deepEqual(store.features.sports.follows.leagues, ['racing/f1'], 'persisted in settings.features.sports');
  await new Promise((r) => setTimeout(r, 300));
  assert.ok(sent.some(([c]) => c === 'sports:updated'), 'pushes sports:updated');

  const mine = await svc.myEvents();
  assert.ok(mine.live.some((e) => e.id === 'espn:football/nfl:401' && e.followed));
  assert.ok(mine.upcoming.some((e) => e.id === 'espn:football/nfl:402'), 'name follow "Chiefs" picks the Chiefs game');
  const teamRow = mine.rows.find((r) => r.kind === 'team');
  assert.equal(teamRow.name, 'Lions');
  assert.ok(teamRow.events.some((e) => e.id === 'espn:football/nfl:401'));
  assert.ok(!teamRow.events.some((e) => e.id === 'espn:football/nfl:402'));
  assert.ok(mine.rows.some((r) => r.kind === 'league' && r.key === 'racing/f1' && r.events.length === 1));
  assert.ok(mine.rows.some((r) => r.kind === 'name' && r.key === 'Chiefs' && r.events.length === 1));

  svc.unfollow({ kind: 'team', leagueId: 'football/nfl', teamId: '8' });
  svc.unfollow({ kind: 'league', id: 'racing/f1' });
  svc.unfollow({ kind: 'name', word: 'chiefs' });
  assert.deepEqual(svc.getFollows(), { teams: [], leagues: [], words: [] });
  svc.stop();
});

test('service: Hide Scores removes scores, clocks and results from what the UI receives', async () => {
  const { svc } = makeService({ hide: true });
  const evs = [];
  for (const off of [-1, 0, 1]) evs.push(...(await svc.eventsForDay(off)).events);
  const lions = evs.find((e) => e.id === 'espn:football/nfl:401');
  assert.equal(lions.away.score, null);
  assert.equal(lions.home.periods.length, 0);
  assert.equal(lions.status, 'Live');
  assert.equal(lions.situation, null);
  assert.equal(lions.progress, null);
  assert.equal(lions.hidden, true);
  svc.saveSettings({ hideScores: false });
  const again = (await svc.eventsForDay(0)).events.concat((await svc.eventsForDay(-1)).events, (await svc.eventsForDay(1)).events).find((e) => e.id === 'espn:football/nfl:401');
  assert.equal(again.away.score, '24');
  svc.stop();
});

test('service: channels for an event, grouped, via guide + names', async () => {
  const start = Date.parse('2026-11-15T18:00:00Z');
  const guide = fakeGuide([
    { tvgId: 'fox5', start, stop: start + 3 * 3600000, title: 'Detroit Lions at Chicago Bears' },
    { tvgId: 'fox5sd', start, stop: start + 3 * 3600000, title: 'Detroit Lions at Chicago Bears' },
  ]);
  const channels = [ch('US: FOX 5 HD', 'fox5'), ch('US: FOX 5 SD', 'fox5sd'), ch('Lions v Bears event', ''), ch('Unrelated', 'u')];
  const { svc } = makeService({ channels, guide });
  await svc.eventsForDay(0);
  await svc.eventsForDay(-1);
  await svc.eventsForDay(1);
  const r = await svc.channelsFor('espn:football/nfl:401');
  assert.equal(r.found, true);
  assert.equal(r.groups[0].lead.channel.name, 'US: FOX 5 HD');
  assert.equal(r.groups[0].copies.length, 1);
  assert.equal(r.groups[0].copies[0].channel.name, 'US: FOX 5 SD');
  assert.ok(r.groups.some((g) => g.lead.channel.name === 'Lions v Bears event'));
  assert.ok(!r.groups.some((g) => g.lead.channel.name === 'Unrelated'));
  const g0 = r.groups[0].lead;
  assert.deepEqual(Object.keys(g0.channel).sort(), ['group', 'id', 'logo', 'name', 'tvgId', 'url']);
  assert.ok(g0.sure && g0.program.title.includes('Lions'));
  assert.deepEqual(await svc.channelsFor('nope'), { eventId: 'nope', found: false, groups: [], total: 0 });
  svc.stop();
});

test('service: leagues list, toggling, standings, league networks', async () => {
  const { svc } = makeService({ channels: [ch('NFL Network HD', ''), ch('NFL RedZone', ''), ch('NBA TV Canada', ''), ch('NFL Network Replay', '')] });
  const ls = svc.leagues();
  assert.ok(ls.length > 200);
  const nfl = ls.find((l) => l.id === 'football/nfl');
  assert.equal(nfl.shown, true);
  assert.equal(nfl.icon, '\u{1F3C8}');
  const after = svc.toggleLeague('football/nfl').find((l) => l.id === 'football/nfl');
  assert.equal(after.shown, false);
  assert.ok(!svc.activeLeagueIds().includes('football/nfl'));
  const st = await svc.standings('basketball/nba');
  assert.equal(st.groups.length, 2);
  assert.equal(await svc.standings('nope'), null);
  const nets = svc.leagueNetworks();
  const byId = Object.fromEntries(nets.map((n) => [n.id, n.channels.map((c) => c.name)]));
  assert.deepEqual(byId.nfl, ['NFL Network HD']);
  assert.deepEqual(byId.redzone, ['NFL RedZone']);
  assert.deepEqual(byId.nbatvca, ['NBA TV Canada']);
  assert.deepEqual(byId.nbatv, []);
  svc.stop();
});
