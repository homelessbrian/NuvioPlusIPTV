'use strict';
// ESPN feed fetching with timeouts, a disk cache, per-entry refresh policy and backoff.
//
// Per league two cache entries are kept:
//   window : a wide range (8 days back .. 8 days ahead) in ONE request, refreshed slowly
//   today  : yesterday..tomorrow, refreshed every ~30 s while something in it is live, otherwise every few minutes
// Events from `today` override same-id events from `window`. A failed fetch backs off exponentially but never drops the
// cached data (it is served stale). `fetchJson` is injectable so everything here is testable without a network.
const fs = require('node:fs');
const path = require('node:path');
const espn = require('./espn');
const cat = require('./catalog');

const DAY = 86400000;
const WINDOW_BACK = 8;
const WINDOW_FWD = 8;

const POLICY = {
  liveMs: 30 * 1000,          // something is live in the entry
  soonMs: 90 * 1000,          // a game starts (or ended) within 3 hours
  todayMs: 4 * 60 * 1000,     // the today entry otherwise
  windowMs: 20 * 60 * 1000,   // the wide window
  standingsMs: 30 * 60 * 1000,
  backoffBase: 15 * 1000,
  backoffMax: 15 * 60 * 1000,
  keepMs: 3 * DAY,            // how long a disk entry is worth keeping
};

/** How long an entry stays fresh. kind: 'window' | 'today' | 'standings'. Pure; exported for tests. */
function ttlFor(kind, entry, now) {
  if (kind === 'standings') return POLICY.standingsMs;
  if (kind === 'window') return POLICY.windowMs;
  const evs = (entry && entry.events) || [];
  if (evs.some((e) => e.state === 'live')) return POLICY.liveMs;
  if (evs.some((e) => e.state !== 'off' && Math.abs(e.startMs - now) < 3 * 3600000)) return POLICY.soonMs;
  return POLICY.todayMs;
}

/** Delay before retrying after `failures` consecutive failures. */
function backoffMs(failures) {
  return Math.min(POLICY.backoffMax, POLICY.backoffBase * 2 ** Math.max(0, failures - 1));
}

async function defaultFetchJson(url, { timeoutMs = 10000, etag } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const headers = { Accept: 'application/json' };
    if (etag) headers['If-None-Match'] = etag;
    const res = await fetch(url, { signal: ctl.signal, headers });
    if (res.status === 304) return { status: 304 };
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return { status: 200, json: await res.json(), etag: res.headers.get('etag') || undefined };
  } finally { clearTimeout(timer); }
}

function createFeeds({ fetchJson = defaultFetchJson, dir = null, now = Date.now, onUpdate = () => {}, concurrency = 4, timeoutMs = 10000, log = () => {} } = {}) {
  /** key -> { fetchedAt, events, logo, etag, failures, nextTry, error } ; standings: { fetchedAt, data, failures, nextTry } */
  const entries = new Map();
  const inflight = new Map();
  const queue = [];
  let running = 0;
  const cacheFile = dir ? path.join(dir, 'sports-cache.json') : null;
  let saveTimer = null;

  function load() {
    if (!cacheFile) return;
    try {
      const j = JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
      if (j && j.v === 1 && j.entries && typeof j.entries === 'object') {
        const t = now();
        for (const [k, v] of Object.entries(j.entries)) {
          if (v && typeof v.fetchedAt === 'number' && t - v.fetchedAt < POLICY.keepMs) entries.set(k, { ...v, failures: 0, nextTry: 0 });
        }
      }
    } catch { /* no cache yet, or unreadable: start empty */ }
  }
  function scheduleSave() {
    if (!cacheFile || saveTimer) return;
    saveTimer = setTimeout(() => { saveTimer = null; flush(); }, 2000);
    if (saveTimer.unref) saveTimer.unref();
  }
  function flush() {
    if (!cacheFile) return;
    try {
      const out = {};
      const t = now();
      for (const [k, v] of entries) if (t - v.fetchedAt < POLICY.keepMs) out[k] = { fetchedAt: v.fetchedAt, events: v.events, logo: v.logo, etag: v.etag, data: v.data };
      fs.mkdirSync(path.dirname(cacheFile), { recursive: true });
      const tmp = cacheFile + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify({ v: 1, entries: out }));
      fs.renameSync(tmp, cacheFile);
    } catch (e) { log('sports cache write failed: ' + e.message); }
  }

  function run(task) {
    return new Promise((resolve, reject) => { queue.push({ task, resolve, reject }); pump(); });
  }
  function pump() {
    while (running < concurrency && queue.length) {
      const { task, resolve, reject } = queue.shift();
      running++;
      task().then(resolve, reject).finally(() => { running--; pump(); });
    }
  }

  const keyOf = (kind, id) => `${kind}|${id}`;
  const rangeFor = (kind, t) => (kind === 'window'
    ? [espn.dayKey(t - WINDOW_BACK * DAY), espn.dayKey(t + WINDOW_FWD * DAY)]
    : [espn.dayKey(t - DAY), espn.dayKey(t + DAY)]);

  function isDue(kind, id, t = now()) {
    const e = entries.get(keyOf(kind, id));
    if (!e) return true;
    if (e.nextTry && e.nextTry > t) return false; // backing off
    return t - e.fetchedAt >= ttlFor(kind, e, t);
  }

  /** Fetch one entry (deduplicated). Resolves true when new data arrived. Never rejects. */
  function refresh(kind, l, { force = false } = {}) {
    const key = keyOf(kind, l.id);
    if (inflight.has(key)) return inflight.get(key);
    const t = now();
    const cur = entries.get(key);
    if (!force && cur && !isDue(kind, l.id, t)) return Promise.resolve(false);
    if (!force && cur && cur.nextTry > t) return Promise.resolve(false);
    const p = run(async () => {
      const [from, to] = rangeFor(kind, t);
      const url = espn.scoreboardUrl(l, from, to);
      try {
        const r = await fetchJson(url, { timeoutMs, etag: cur && cur.etag });
        const at = now();
        if (r.status === 304 && cur) { cur.fetchedAt = at; cur.failures = 0; cur.nextTry = 0; return false; }
        const parsed = espn.parseScoreboard(l, r.json, at);
        const next = { fetchedAt: at, events: parsed.events, logo: parsed.logo || (cur && cur.logo) || null, etag: r.etag, failures: 0, nextTry: 0 };
        const changed = !cur || JSON.stringify(cur.events) !== JSON.stringify(next.events);
        entries.set(key, next);
        scheduleSave();
        return changed;
      } catch (e) {
        const failures = ((cur && cur.failures) || 0) + 1;
        const bad = { ...(cur || { fetchedAt: 0, events: [] }), failures, nextTry: now() + backoffMs(failures), error: e.message };
        entries.set(key, bad);
        log(`sports feed ${l.id} failed (${e.message}); retry in ${Math.round(backoffMs(failures) / 1000)}s`);
        return false;
      }
    }).then((changed) => { inflight.delete(key); if (changed) onUpdate([l.id]); return changed; });
    inflight.set(key, p);
    return p;
  }

  const leagueOf = (id) => cat.league(id);
  const feedableLeagues = (ids) => ids.map(leagueOf).filter((l) => espn.feedable(l));

  /** Cached events for these leagues (window overridden by today), as a flat array. No network. */
  function eventsFor(ids) {
    const out = [];
    for (const l of feedableLeagues(ids)) {
      const byId = new Map();
      const w = entries.get(keyOf('window', l.id));
      const td = entries.get(keyOf('today', l.id));
      for (const e of (w && w.events) || []) byId.set(e.id, e);
      for (const e of (td && td.events) || []) byId.set(e.id, e);
      out.push(...byId.values());
    }
    return out;
  }

  /** Refresh whatever is due for these leagues; wait at most deadlineMs for the answers. Returns true if anything changed. */
  async function ensure(ids, { deadlineMs = 6000, force = false } = {}) {
    const ls = feedableLeagues(ids);
    const jobs = [];
    for (const l of ls) for (const kind of ['window', 'today']) jobs.push(refresh(kind, l, { force }));
    if (!jobs.length) return false;
    const all = Promise.all(jobs).then((r) => r.some(Boolean));
    if (!(deadlineMs > 0)) return all;
    let timer;
    const timeout = new Promise((res) => { timer = setTimeout(() => res(false), deadlineMs); });
    try { return await Promise.race([all, timeout]); } finally { clearTimeout(timer); }
  }

  /** Background poll: only what is due. `visible`: the Sports tab is showing (all given leagues); otherwise only `liveIds`. */
  function tick(ids, { visible = false, liveIds = [] } = {}) {
    const target = visible ? ids : liveIds;
    return ensure(target, { deadlineMs: 0 });
  }

  function hasData(id) { return entries.has(keyOf('window', id)) && !!entries.get(keyOf('window', id)).fetchedAt || (entries.has(keyOf('today', id)) && !!entries.get(keyOf('today', id)).fetchedAt); }
  function hasLive(ids) { return eventsFor(ids).some((e) => e.state === 'live'); }
  function leagueLogo(id) { const w = entries.get(keyOf('window', id)) || entries.get(keyOf('today', id)); return (w && w.logo) || null; }

  /** League table, cached for 30 minutes; stale data is served when the network fails. */
  async function standings(l) {
    const url = espn.standingsUrl(l);
    if (!url) return null;
    const key = keyOf('standings', l.id);
    const t = now();
    const cur = entries.get(key);
    if (cur && cur.data && (t - cur.fetchedAt < POLICY.standingsMs || (cur.nextTry && cur.nextTry > t))) return cur.data;
    if (inflight.has(key)) return inflight.get(key);
    const p = run(async () => {
      try {
        const r = await fetchJson(url, { timeoutMs });
        const data = espn.parseStandings(l, r.json, now());
        entries.set(key, { fetchedAt: now(), events: [], data, failures: 0, nextTry: 0 });
        scheduleSave();
        return data;
      } catch (e) {
        const failures = ((cur && cur.failures) || 0) + 1;
        entries.set(key, { ...(cur || { fetchedAt: 0, events: [] }), failures, nextTry: now() + backoffMs(failures), error: e.message });
        if (cur && cur.data) return cur.data;
        throw e;
      }
    }).finally(() => inflight.delete(key));
    inflight.set(key, p);
    return p;
  }

  function status() {
    const out = {};
    for (const [k, v] of entries) out[k] = { fetchedAt: v.fetchedAt, events: (v.events || []).length, failures: v.failures || 0, nextTry: v.nextTry || 0, error: v.error };
    return out;
  }

  load();
  return { eventsFor, ensure, tick, refresh, hasLive, hasData, leagueLogo, standings, isDue, flush, status, _entries: entries };
}

module.exports = { createFeeds, ttlFor, backoffMs, POLICY, defaultFetchJson };
