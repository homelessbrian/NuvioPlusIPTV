'use strict';
// Xtream Codes API client (pure Node, global fetch, injectable for tests).
// Credentials are never logged and never appear in thrown error messages.

const DEFAULT_TIMEOUT_MS = 30000;
const LIST_TIMEOUT_MS = 90000;
const LOGIN_TTL_MS = 5 * 60e3;

class XtreamError extends Error {
  // code: config | network | http | parse | auth | expired | disabled
  constructor(code, message) { super(message); this.name = 'XtreamError'; this.code = code; }
}

/** A typed/pasted server made safe: invisible chars removed, scheme added, reduced to scheme://host[:port]. */
function cleanServer(raw) {
  let s = String(raw || '').replace(/[​-‍⁠﻿ \s]/g, '').replace(/[‐-―−﹘﹣－]/g, '-');
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  try { const u = new URL(s); return `${u.protocol}//${u.host}`; } catch { return s.replace(/\/+$/, ''); }
}

/** username/password written in a pasted link such as ".../get.php?username=a&password=b". */
function credentialsFromLink(raw) {
  try {
    const u = new URL(/^https?:\/\//i.test(raw) ? raw : 'http://' + raw);
    const username = u.searchParams.get('username') || '';
    const password = u.searchParams.get('password') || '';
    return username && password ? { username, password } : null;
  } catch { return null; }
}

/** Normalise the settings object. Returns null when it is not usable. */
function normalizeConfig(xtream) {
  if (!xtream || typeof xtream.server !== 'string') return null;
  const server = cleanServer(xtream.server);
  let username = String(xtream.username || '').trim();
  let password = String(xtream.password || '');
  if (!username || !password) {
    const c = credentialsFromLink(xtream.server);
    if (c) { username = username || c.username; password = password || c.password; }
  }
  if (!server || !username || !password) return null;
  return { server, username, password };
}

const num = (v, d = 0) => { const n = Number(v); return Number.isFinite(n) ? n : d; };
const str = (v) => (v == null ? '' : String(v));
const arr = (v) => (Array.isArray(v) ? v : []);
const obj = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? v : {});
const firstOf = (v) => (Array.isArray(v) ? str(v[0]) : str(v));
const yearOf = (v) => { const m = /(19|20)\d{2}/.exec(str(v)); return m ? Number(m[0]) : 0; };
const b64 = (s) => { try { return Buffer.from(str(s), 'base64').toString('utf8'); } catch { return str(s); } };

/** "yyyy-MM-dd:HH-mm" for an instant, written in the given IANA time zone (default: this machine's). */
function formatCatchupStamp(ms, timezone) {
  const opts = { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };
  let fmt;
  try { fmt = new Intl.DateTimeFormat('en-GB', timezone ? { ...opts, timeZone: timezone } : opts); }
  catch { fmt = new Intl.DateTimeFormat('en-GB', opts); }
  const p = {};
  for (const part of fmt.formatToParts(new Date(ms))) p[part.type] = part.value;
  const hh = p.hour === '24' ? '00' : p.hour;
  return `${p.year}-${p.month}-${p.day}:${hh}-${p.minute}`;
}

const seg = (s) => encodeURIComponent(s);

/** Pure catch-up URL builder: base/timeshift/user/pass/minutes/stamp/id.ext (start rounded down to the minute). */
function buildCatchupUrl({ server, username, password, streamId, startMs, durationMin, timezone, ext = 'ts' }) {
  const startMin = Math.floor(startMs / 60000) * 60000;
  const minutes = Math.max(1, Math.round(durationMin));
  return `${cleanServer(server)}/timeshift/${seg(username)}/${seg(password)}/${minutes}/${formatCatchupStamp(startMin, timezone)}/${seg(str(streamId))}.${ext.replace(/^\./, '')}`;
}

function createClient(config, opts = {}) {
  const cfg = normalizeConfig(config);
  if (!cfg) throw new XtreamError('config', 'Xtream login is not set up (server, username and password are needed).');
  const doFetch = opts.fetch || ((...a) => globalThis.fetch(...a));
  const timeoutMs = opts.timeoutMs || DEFAULT_TIMEOUT_MS;
  const now = opts.now || (() => Date.now());
  const { server, username, password } = cfg;

  const scrub = (text) => {
    let t = String(text);
    for (const secret of new Set([password, encodeURIComponent(password)])) {
      if (secret) t = t.split(secret).join('***');
    }
    return t;
  };
  const fail = (code, message) => new XtreamError(code, scrub(message));

  const apiUrl = (action, params) => {
    const q = new URLSearchParams({ username, password });
    if (action) q.set('action', action);
    for (const [k, v] of Object.entries(params || {})) if (v != null && v !== '') q.set(k, String(v));
    return `${server}/player_api.php?${q}`;
  };

  async function call(action, params, t = timeoutMs) {
    let res;
    try {
      res = await doFetch(apiUrl(action, params), { signal: AbortSignal.timeout(t), redirect: 'follow', headers: { Accept: 'application/json' } });
    } catch (e) {
      const timedOut = e && (e.name === 'TimeoutError' || e.name === 'AbortError');
      throw fail('network', timedOut ? 'The Xtream server did not answer in time.' : 'Could not reach the Xtream server.');
    }
    if (res.status === 401 || res.status === 403) throw fail('auth', `The server refused the login (HTTP ${res.status}).`);
    if (!res.ok) throw fail('http', `The Xtream server answered HTTP ${res.status}.`);
    const text = await res.text();
    try { return JSON.parse(text); } catch { throw fail('parse', 'The server did not answer with Xtream data (check the server address).'); }
  }

  let loginCache = null;

  /** Authenticate. Resolves to { ok, status, expiry (ms|null), activeConnections, maxConnections, formats, timezone, serverInfo }; throws on bad/expired/disabled. */
  async function login(force = false) {
    if (!force && loginCache && now() - loginCache.at < LOGIN_TTL_MS) return loginCache.value;
    const j = obj(await call('', null));
    const ui = obj(j.user_info);
    if (!Object.keys(ui).length || num(ui.auth, 0) === 0) {
      const msg = str(ui.message);
      throw fail('auth', msg ? `Login refused: ${msg}` : 'Login refused: wrong username or password.');
    }
    const status = str(ui.status || 'Active');
    const expSec = ui.exp_date == null || ui.exp_date === '' ? null : num(ui.exp_date, null);
    const expiry = expSec && expSec > 0 ? expSec * 1000 : null;
    if (/^(disabled|banned)$/i.test(status)) throw fail('disabled', `This account is ${status.toLowerCase()}.`);
    if (/^expired$/i.test(status) || (expiry && expiry < now())) throw fail('expired', expiry ? `This account expired on ${new Date(expiry).toISOString().slice(0, 10)}.` : 'This account has expired.');
    const si = obj(j.server_info);
    const formats = arr(ui.allowed_output_formats).map((f) => str(f).toLowerCase()).filter(Boolean);
    const value = {
      ok: true, status, expiry,
      activeConnections: num(ui.active_cons, 0), maxConnections: num(ui.max_connections, 0),
      formats, timezone: str(si.timezone) || null, serverInfo: si, isTrial: str(ui.is_trial) === '1',
    };
    loginCache = { at: now(), value };
    return value;
  }

  const categories = async (action) => arr(await call(action, null, LIST_TIMEOUT_MS)).map((c) => ({ id: str(c.category_id), name: str(c.category_name) || 'Uncategorised', parentId: str(c.parent_id) }));
  const liveCategories = () => categories('get_live_categories');
  const vodCategories = () => categories('get_vod_categories');
  const seriesCategories = () => categories('get_series_categories');

  /** Preferred live container: m3u8 when the account offers it (or offers nothing explicit), else ts. */
  const pickFormat = (formats, prefer) => {
    const f = formats && formats.length ? formats : (loginCache ? loginCache.value.formats : []);
    if (prefer && f.includes(prefer)) return prefer;
    if (f.includes('m3u8')) return 'm3u8';
    if (f.includes('ts')) return 'ts';
    return f[0] || 'ts';
  };

  const liveUrl = (streamId, format) => `${server}/live/${seg(username)}/${seg(password)}/${seg(str(streamId))}.${format || pickFormat()}`;
  const movieUrl = (streamId, ext) => `${server}/movie/${seg(username)}/${seg(password)}/${seg(str(streamId))}.${str(ext).replace(/^\./, '') || 'mp4'}`;
  const episodeUrl = (episodeId, ext) => `${server}/series/${seg(username)}/${seg(password)}/${seg(str(episodeId))}.${str(ext).replace(/^\./, '') || 'mp4'}`;
  const xmltv = () => `${server}/xmltv.php?${new URLSearchParams({ username, password })}`;

  /** Live streams as shared Channel objects, in the provider's order. */
  async function liveStreams({ format } = {}) {
    const [info, cats, list] = await Promise.all([login(), liveCategories().catch(() => []), call('get_live_streams', null, LIST_TIMEOUT_MS)]);
    const names = new Map(cats.map((c) => [c.id, c.name]));
    const fmt = pickFormat(info.formats, format);
    const items = arr(list).map((s, i) => ({ s: obj(s), i })).filter(({ s }) => s.stream_id != null && s.stream_id !== '');
    items.sort((a, b) => (num(a.s.num, a.i + 1) - num(b.s.num, b.i + 1)) || a.i - b.i);
    return items.map(({ s }) => {
      const id = str(s.stream_id);
      const ch = {
        id, name: str(s.name).trim() || `Channel ${id}`, tvgId: str(s.epg_channel_id).trim(), logo: str(s.stream_icon),
        group: names.get(str(s.category_id)) || 'Uncategorised', url: liveUrl(id, fmt),
      };
      const archive = num(s.tv_archive, 0) === 1;
      if (archive) ch.catchup = { type: 'xtream', days: Math.max(1, num(s.tv_archive_duration, 1)), streamId: id };
      return ch;
    });
  }

  /** Catch-up (timeshift) URL for a channel (or stream id) and a show window. Uses the server's time zone from login. */
  async function catchupUrl(streamOrChannel, startMs, durationMin, { format } = {}) {
    const streamId = streamOrChannel && typeof streamOrChannel === 'object'
      ? (streamOrChannel.catchup ? streamOrChannel.catchup.streamId : streamOrChannel.streamId || streamOrChannel.id)
      : streamOrChannel;
    const info = await login();
    return buildCatchupUrl({ server, username, password, streamId, startMs, durationMin, timezone: info.timezone, ext: format || (info.formats.includes('ts') ? 'ts' : 'm3u8') });
  }

  const normMovie = (m) => ({
    id: str(m.stream_id), name: str(m.name).trim(), poster: str(m.stream_icon || m.cover), rating: num(m.rating_5based ?? m.rating, 0),
    year: yearOf(m.year || m.releaseDate || m.releasedate || m.name), added: num(m.added, 0), categoryId: str(Array.isArray(m.category_ids) && m.category_ids.length ? m.category_ids[0] : m.category_id), ext: str(m.container_extension) || 'mp4',
  });
  const normSeries = (s) => ({
    id: str(s.series_id), name: str(s.name).trim(), poster: str(s.cover), rating: num(s.rating_5based ?? s.rating, 0),
    year: yearOf(s.year || s.releaseDate || s.release_date || s.name), plot: str(s.plot), categoryId: str(Array.isArray(s.category_ids) && s.category_ids.length ? s.category_ids[0] : s.category_id), added: num(s.last_modified, 0),
  });

  async function vodStreams(categoryId) {
    const list = await call('get_vod_streams', categoryId ? { category_id: categoryId } : null, LIST_TIMEOUT_MS);
    return arr(list).map(obj).filter((m) => m.stream_id != null && m.stream_id !== '').map(normMovie);
  }
  async function seriesList(categoryId) {
    const list = await call('get_series', categoryId ? { category_id: categoryId } : null, LIST_TIMEOUT_MS);
    return arr(list).map(obj).filter((s) => s.series_id != null && s.series_id !== '').map(normSeries);
  }

  async function vodInfo(id) {
    const j = obj(await call('get_vod_info', { vod_id: id }));
    const i = obj(j.info); const d = obj(j.movie_data);
    const backdrop = firstOf(i.backdrop_path);
    return {
      id: str(d.stream_id || id), name: str(d.name || i.name), ext: str(d.container_extension) || 'mp4',
      poster: str(i.movie_image || i.cover_big), backdrop, plot: str(i.plot || i.description), cast: str(i.cast || i.actors), director: str(i.director),
      genre: str(i.genre), year: yearOf(i.releasedate || i.release_date || i.year), rating: num(i.rating_5based ?? 0) || (num(i.rating, 0) / 2),
      durationSecs: num(i.duration_secs, 0), duration: str(i.duration), trailer: str(i.youtube_trailer),
    };
  }

  async function seriesInfo(id) {
    const j = obj(await call('get_series_info', { series_id: id }));
    const i = obj(j.info);
    const bySeason = new Map();
    const eps = j.episodes;
    const add = (season, e) => {
      e = obj(e); if (e.id == null || e.id === '') return;
      const ei = obj(e.info);
      const sn = num(e.season ?? season, 0);
      if (!bySeason.has(sn)) bySeason.set(sn, []);
      bySeason.get(sn).push({ id: str(e.id), season: sn, episode: num(e.episode_num, 0), title: str(e.title) || `Episode ${num(e.episode_num, 0) || '?'}`, ext: str(e.container_extension) || 'mp4', plot: str(ei.plot), duration: str(ei.duration), durationSecs: num(ei.duration_secs, 0), image: str(ei.movie_image), added: num(e.added, 0) });
    };
    if (Array.isArray(eps)) eps.forEach((group, idx) => { if (Array.isArray(group)) group.forEach((e) => add(idx + 1, e)); else add(idx + 1, group); });
    else for (const [k, list] of Object.entries(obj(eps))) arr(list).forEach((e) => add(k, e));
    const seasonMeta = new Map(arr(j.seasons).map(obj).map((s) => [num(s.season_number, -1), s]));
    const seasons = [...bySeason.keys()].sort((a, b) => a - b).map((n) => {
      const list = bySeason.get(n).sort((a, b) => a.episode - b.episode);
      const m = seasonMeta.get(n) || {};
      return { number: n, name: str(m.name) || (n === 0 ? 'Specials' : `Season ${n}`), cover: str(m.cover || m.cover_big), episodes: list };
    });
    return {
      id: str(id), name: str(i.name), poster: str(i.cover), backdrop: firstOf(i.backdrop_path), plot: str(i.plot), cast: str(i.cast), director: str(i.director), genre: str(i.genre),
      year: yearOf(i.releaseDate || i.release_date || i.year), rating: num(i.rating_5based, 0) || (num(i.rating, 0) / 2), seasons,
    };
  }

  /** Short EPG for a live stream: Programme[] (epoch ms). */
  async function shortEpg(streamId, limit = 4) {
    const j = obj(await call('get_short_epg', { stream_id: streamId, limit }));
    return arr(j.epg_listings).map(obj).map((e) => ({
      start: num(e.start_timestamp, 0) * 1000, stop: num(e.stop_timestamp ?? e.end_timestamp, 0) * 1000, title: b64(e.title), desc: b64(e.description),
    })).filter((p) => p.start && p.stop).sort((a, b) => a.start - b.start);
  }

  return {
    login, liveCategories, liveStreams, vodCategories, vodStreams, vodInfo, seriesCategories, seriesList, seriesInfo, shortEpg,
    xmltvUrl: xmltv, liveUrl, movieUrl, episodeUrl, catchupUrl, config: () => ({ server, username }),
  };
}

/** For library.js: the Xtream live channels for the settings object (null when not configured). */
async function channelsFromSettings(xtream, opts) {
  if (!normalizeConfig(xtream)) return [];
  return createClient(xtream, opts).liveStreams();
}

/** XMLTV URL for the settings object, or null. */
function xmltvUrl(xtream) {
  const cfg = normalizeConfig(xtream);
  return cfg ? createClient(cfg).xmltvUrl() : null;
}

module.exports = { createClient, channelsFromSettings, xmltvUrl, buildCatchupUrl, formatCatchupStamp, cleanServer, normalizeConfig, credentialsFromLink, XtreamError };
