'use strict';
// Feature module "ondemand": Xtream movies and series (IPC "ondemand:*"). Owned by the ondemand work stream.
const { createClient, normalizeConfig } = require('../xtream');

const LIST_TTL_MS = 15 * 60e3;
const INFO_TTL_MS = 30 * 60e3;
const SEARCH_LIMIT = 200;

const kindOf = (k) => (/^series$/i.test(k) ? 'series' : 'movies');

/** Builds the service the IPC handlers use. `getXtream()` returns the settings object; `fetch` is injectable for tests. */
function createService(getXtream, opts = {}) {
  const now = opts.now || (() => Date.now());
  let sig = null;
  let client = null;
  let cache = new Map(); // key -> { at, value } or { at, promise }

  function current() {
    const cfg = normalizeConfig(getXtream());
    if (!cfg) { sig = null; client = null; cache = new Map(); return null; }
    const s = JSON.stringify([cfg.server, cfg.username, cfg.password]);
    if (s !== sig) { sig = s; cache = new Map(); client = createClient(cfg, { fetch: opts.fetch, now: opts.now, timeoutMs: opts.timeoutMs }); }
    return client;
  }
  const need = () => {
    const c = current();
    if (!c) { const e = new Error('Xtream is not set up. Add your server, username and password in Settings.'); e.code = 'config'; throw e; }
    return c;
  };

  function cached(key, ttl, load) {
    const hit = cache.get(key);
    if (hit && now() - hit.at < ttl) return hit.promise;
    const entry = { at: now(), promise: null };
    entry.promise = load().catch((e) => { if (cache.get(key) === entry) cache.delete(key); throw e; });
    cache.set(key, entry);
    return entry.promise;
  }

  const listKey = (kind) => `list:${kind}`;
  const lists = (kind) => {
    const c = need();
    return cached(listKey(kind), LIST_TTL_MS, async () => {
      await c.login(); // bad/expired accounts get a clear error instead of an empty list
      const [cats, items] = await Promise.all([
        kind === 'series' ? c.seriesCategories() : c.vodCategories(),
        kind === 'series' ? c.seriesList() : c.vodStreams(),
      ]);
      const counts = new Map();
      for (const it of items) counts.set(it.categoryId, (counts.get(it.categoryId) || 0) + 1);
      const known = new Set(cats.map((x) => x.id));
      const categories = cats.map((x) => ({ id: x.id, name: x.name, count: counts.get(x.id) || 0 }));
      if (items.some((it) => !known.has(it.categoryId))) categories.push({ id: '', name: 'Uncategorised', count: items.filter((it) => !known.has(it.categoryId)).length });
      for (const it of items) if (!known.has(it.categoryId)) it.categoryId = '';
      // Newest additions first by default.
      items.sort((a, b) => (b.added - a.added) || a.name.localeCompare(b.name));
      return { categories: categories.filter((x) => x.count > 0 || cats.length === 0), items };
    });
  }

  async function categories(kind) {
    const l = await lists(kindOf(kind));
    return l.categories;
  }

  async function page(kind, categoryId, pageNo, pageSize = 100) {
    const l = await lists(kind);
    const all = categoryId == null || categoryId === '' || categoryId === 'all' ? l.items : l.items.filter((it) => it.categoryId === String(categoryId));
    if (pageNo == null) return { items: all, total: all.length, page: 0, pageSize: all.length };
    const p = Math.max(0, Number(pageNo) | 0);
    return { items: all.slice(p * pageSize, (p + 1) * pageSize), total: all.length, page: p, pageSize };
  }

  const movies = (categoryId, pageNo) => page('movies', categoryId, pageNo);
  const series = (categoryId, pageNo) => page('series', categoryId, pageNo);

  function info(kind, id) {
    const c = need();
    const k = kindOf(kind);
    return cached(`info:${k}:${id}`, INFO_TTL_MS, () => (k === 'series' ? c.seriesInfo(id) : c.vodInfo(id)));
  }

  async function playUrl(kind, id, episodeId, episodeExt) {
    const c = need();
    await c.login(); // surfaces expired/disabled accounts with a clear message before the player fails
    if (kindOf(kind) === 'series') {
      if (episodeId == null || episodeId === '') throw new Error('Pick an episode to play.');
      let ext = episodeExt;
      let title = '';
      if (!ext) {
        const si = await info('series', id);
        for (const s of si.seasons) for (const e of s.episodes) if (e.id === String(episodeId)) { ext = e.ext; title = `${si.name} S${s.number}E${e.episode}`; }
      }
      return { url: c.episodeUrl(episodeId, ext), name: title || 'Episode', ext: ext || 'mp4' };
    }
    const l = await lists('movies').catch(() => null);
    const hit = l && l.items.find((m) => m.id === String(id));
    let ext = hit && hit.ext; let name = hit && hit.name;
    if (!ext || !name) { const vi = await info('movies', id); ext = ext || vi.ext; name = name || vi.name; }
    return { url: c.movieUrl(id, ext), name: name || 'Movie', ext: ext || 'mp4' };
  }

  async function search(query) {
    const q = String(query || '').trim().toLowerCase();
    if (q.length < 2) return { movies: [], series: [], truncated: false };
    const words = q.split(/\s+/);
    const [m, s] = await Promise.all([lists('movies'), lists('series').catch(() => ({ items: [] }))]);
    const pick = (items) => {
      const out = [];
      for (const it of items) {
        const n = it.name.toLowerCase();
        if (words.every((w) => n.includes(w))) { out.push(it); if (out.length > SEARCH_LIMIT) break; }
      }
      return out;
    };
    const mr = pick(m.items); const sr = pick(s.items);
    return { movies: mr.slice(0, SEARCH_LIMIT), series: sr.slice(0, SEARCH_LIMIT), truncated: mr.length > SEARCH_LIMIT || sr.length > SEARCH_LIMIT };
  }

  async function status() {
    const c = current();
    if (!c) return { configured: false, ok: false, message: 'Xtream is not set up.', expiry: null, activeConnections: 0, maxConnections: 0 };
    try {
      const i = await c.login(true);
      return { configured: true, ok: true, message: i.status, expiry: i.expiry, activeConnections: i.activeConnections, maxConnections: i.maxConnections };
    } catch (e) {
      return { configured: true, ok: false, code: e.code || 'error', message: e.message, expiry: null, activeConnections: 0, maxConnections: 0 };
    }
  }

  return { categories, movies, series, info, playUrl, search, status, refresh: () => { cache = new Map(); } };
}

function register(ctx) {
  const svc = createService(() => ctx.settings.get().xtream);
  ctx.handle('ondemand:categories', (kind) => svc.categories(kind));
  ctx.handle('ondemand:movies', (categoryId, page) => svc.movies(categoryId, page));
  ctx.handle('ondemand:series', (categoryId, page) => svc.series(categoryId, page));
  ctx.handle('ondemand:info', (kind, id) => svc.info(kind, id));
  ctx.handle('ondemand:playUrl', (kind, id, episodeId, ext) => svc.playUrl(kind, id, episodeId, ext));
  ctx.handle('ondemand:search', (query) => svc.search(query));
  ctx.handle('ondemand:status', () => svc.status());
  ctx.handle('ondemand:refresh', () => { svc.refresh(); return true; });
}

module.exports = { register, createService };
