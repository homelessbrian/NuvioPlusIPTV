'use strict';
// The Sports service: follows, league selection, day/My Sports views, channel matching, polling.
// Pure of Electron: everything it needs comes in through `deps`, so it is testable.
const cat = require('./catalog');
const espn = require('./espn');
const { createFeeds } = require('./feeds');
const { createMatcher, groupCopies, chKey } = require('./matcher');
const N = require('./networks');

const DAY = 86400000;
const HOUR = 3600000;

const DEFAULTS = {
  follows: { teams: [], leagues: [], words: [] },
  hideScores: false, region: '', hiddenLeagues: [], preferredGroups: [], bestChannelFirst: true,
};

function createService(deps) {
  const {
    getSettings, saveSettings, getChannels, getGuide = () => null, send = () => {}, userData = null,
    fetchJson, now = Date.now, log = () => {}, feeds: injectedFeeds, matcher: injectedMatcher, pollMs = 15000,
  } = deps;

  const feeds = injectedFeeds || createFeeds({ fetchJson, dir: userData, now, onUpdate: (ids) => notify(ids), log });
  const matcher = injectedMatcher || createMatcher({ getGuide, now });
  let visible = false;
  let timer = null;
  let notifyTimer = null;
  let pendingIds = new Set();

  // ------------------------------------------------------------------ settings & follows
  const cfg = () => {
    const s = (getSettings() || {}).features && getSettings().features.sports;
    const f = (s && s.follows) || {};
    return {
      ...DEFAULTS, ...(s || {}),
      follows: { teams: f.teams || [], leagues: f.leagues || [], words: f.words || [] },
    };
  };
  function saveCfg(patch) {
    const cur = cfg();
    saveSettings({ features: { sports: { ...cur, ...patch } } });
    return cfg();
  }

  const teamKey = (t) => `${t.leagueId}:${t.teamId}`;
  function followsTeam(follows, leagueId, teamId) {
    if (teamId == null) return false;
    const fam = cat.teamFamily(leagueId);
    return follows.teams.some((t) => t.teamId === teamId && (t.leagueId === leagueId || cat.teamFamily(t.leagueId) === fam));
  }
  const followsLeague = (follows, id) => follows.leagues.includes(id);

  function getFollows() { const f = cfg().follows; return { teams: f.teams, leagues: f.leagues, words: f.words }; }

  function follow(item) {
    const c = cfg();
    const f = { teams: [...c.follows.teams], leagues: [...c.follows.leagues], words: [...c.follows.words] };
    if (!item || typeof item !== 'object') return f;
    if (item.kind === 'team' && item.leagueId && item.teamId != null) {
      const t = { leagueId: String(item.leagueId), teamId: String(item.teamId), name: String(item.name || ''), abbr: String(item.abbr || ''), color: item.color || null, logo: item.logo || null };
      if (!f.teams.some((x) => teamKey(x) === teamKey(t))) f.teams.push(t);
    } else if (item.kind === 'league' && cat.league(item.id)) {
      if (!f.leagues.includes(item.id)) f.leagues.push(item.id);
    } else if (item.kind === 'name' && String(item.word || '').trim()) {
      const w = String(item.word).trim().slice(0, 60);
      if (!f.words.some((x) => x.toLowerCase() === w.toLowerCase())) f.words.push(w);
    }
    saveCfg({ follows: f });
    notify([]);
    return f;
  }
  function unfollow(item) {
    const c = cfg();
    const f = { teams: [...c.follows.teams], leagues: [...c.follows.leagues], words: [...c.follows.words] };
    if (item && item.kind === 'team') {
      // Unfollowing a team in one competition removes the club everywhere (same id family).
      const fam = cat.teamFamily(String(item.leagueId));
      f.teams = f.teams.filter((t) => !(String(t.teamId) === String(item.teamId) && (t.leagueId === item.leagueId || cat.teamFamily(t.leagueId) === fam)));
    } else if (item && item.kind === 'league') f.leagues = f.leagues.filter((x) => x !== item.id);
    else if (item && item.kind === 'name') f.words = f.words.filter((w) => w.toLowerCase() !== String(item.word || '').toLowerCase());
    saveCfg({ follows: f });
    notify([]);
    return f;
  }

  function publicSettings() {
    const c = cfg();
    return { hideScores: !!c.hideScores, region: c.region || '', effectiveRegion: cat.regionOf(c), hiddenLeagues: c.hiddenLeagues, preferredGroups: c.preferredGroups, bestChannelFirst: c.bestChannelFirst !== false, regions: cat.REGIONS };
  }
  function saveSettingsPatch(p) {
    const patch = {};
    if (p && typeof p.hideScores === 'boolean') patch.hideScores = p.hideScores;
    if (p && typeof p.region === 'string' && (p.region === '' || cat.REGIONS.some((r) => r[0] === p.region))) patch.region = p.region;
    if (p && Array.isArray(p.preferredGroups)) patch.preferredGroups = p.preferredGroups.filter((x) => typeof x === 'string').slice(0, 50);
    if (p && typeof p.bestChannelFirst === 'boolean') patch.bestChannelFirst = p.bestChannelFirst;
    saveCfg(patch);
    notify([]);
    return publicSettings();
  }

  // ------------------------------------------------------------------ leagues
  function activeLeagueIds() {
    const c = cfg();
    const ids = new Set();
    for (const l of cat.leagues) if (espn.feedable(l) && cat.shown(l, c)) ids.add(l.id);
    for (const id of c.follows.leagues) ids.add(id);
    for (const t of c.follows.teams) { ids.add(t.leagueId); for (const r of cat.relatedLeagues(t.leagueId)) ids.add(r); }
    return [...ids].filter((id) => espn.feedable(cat.league(id)));
  }
  function followedLeagueIds() {
    const c = cfg();
    const ids = new Set(c.follows.leagues);
    for (const t of c.follows.teams) { ids.add(t.leagueId); for (const r of cat.relatedLeagues(t.leagueId)) ids.add(r); }
    return [...ids].filter((id) => espn.feedable(cat.league(id)));
  }

  function leagues() {
    const c = cfg();
    return cat.ordered(c).map((l) => ({
      id: l.id, sport: l.sport, sportLabel: cat.SPORTS[l.sport].label, icon: cat.SPORTS[l.sport].icon, name: l.name, short: l.short,
      color: l.color, kind: l.kind, national: !!l.national, women: !!l.women, feedable: espn.feedable(l),
      shown: cat.shown(l, c), followed: followsLeague(c.follows, l.id), logo: feeds.leagueLogo(l.id),
    }));
  }
  function toggleLeague(id) {
    const l = cat.league(id);
    if (!l) return leagues();
    saveCfg({ hiddenLeagues: cat.toggleShown(l, cfg()).hiddenLeagues });
    notify([id]);
    return leagues();
  }

  // ------------------------------------------------------------------ events
  function wire(ev, c = cfg()) {
    const l = cat.league(ev.leagueId);
    const hide = !!c.hideScores;
    const sideOut = (s) => s && { team: s.team, score: hide ? null : s.score, record: s.record, winner: hide ? false : s.winner, home: s.home, periods: hide ? [] : s.periods };
    const followed = (ev.home && followsTeam(c.follows, ev.leagueId, ev.home.team.id)) || (ev.away && followsTeam(c.follows, ev.leagueId, ev.away.team.id)) || followsLeague(c.follows, ev.leagueId);
    return {
      id: ev.id, leagueId: ev.leagueId, leagueName: l ? l.name : ev.leagueId, leagueShort: l ? l.short : '', leagueColor: l ? l.color : null,
      leagueLogo: feeds.leagueLogo(ev.leagueId), sport: l ? l.sport : 'OTHER', icon: l ? cat.SPORTS[l.sport].icon : '', kind: l ? l.kind : 'TEAMS',
      startMs: ev.startMs, hasTime: ev.hasTime, endMs: ev.endMs, state: ev.state, title: ev.title, subtitle: ev.subtitle, venue: ev.venue || null,
      status: hide ? (ev.state === 'live' ? 'Live' : ev.state === 'post' ? 'Final' : ev.status) : ev.status,
      away: sideOut(ev.away), home: sideOut(ev.home), broadcasts: ev.broadcasts, situation: hide ? null : ev.situation, progress: hide ? null : ev.progress,
      note: ev.note, standings: hide ? [] : ev.standings, sessions: ev.sessions || [], followed: !!followed, hidden: hide,
    };
  }

  const dayStart = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const stateRank = { live: 0, pre: 1, post: 2, off: 3 };
  const byTime = (a, b) => (stateRank[a.state] - stateRank[b.state]) || a.startMs - b.startMs || a.id.localeCompare(b.id);
  const leagueOrder = () => { const m = new Map(); cat.ordered(cfg()).forEach((l, i) => m.set(l.id, i)); return m; };

  function inDay(ev, d0, d1) {
    if (ev.hasTime) return ev.startMs >= d0 && ev.startMs < d1;
    return ev.startMs < d1 && (ev.endMs != null ? ev.endMs + DAY : ev.startMs + DAY) > d0;
  }

  async function loadFor(ids) {
    const missing = ids.filter((id) => !feeds.hasData(id));
    feeds.ensure(ids, { deadlineMs: 0 }).catch(() => {});
    if (missing.length) await feeds.ensure(missing, { deadlineMs: 5000 });
    return { loading: ids.some((id) => !feeds.hasData(id)) };
  }

  async function eventsForDay(dayOffset = 0, opts = {}) {
    const c = cfg();
    const off = Math.max(-7, Math.min(7, Math.trunc(Number(dayOffset) || 0)));
    const d0 = dayStart(now()) + off * DAY;
    const d1 = dayStart(d0 + DAY + 3 * HOUR); // DST safe
    let ids = activeLeagueIds();
    if (opts.leagueId) ids = ids.filter((x) => x === opts.leagueId);
    const { loading } = await loadFor(ids);
    const order = leagueOrder();
    const evs = feeds.eventsFor(ids).filter((e) => inDay(e, d0, d1) && e.state !== 'off');
    evs.sort((a, b) => byTime(a, b) || (order.get(a.leagueId) || 0) - (order.get(b.leagueId) || 0));
    return { dayOffset: off, dayStart: d0, loading, events: evs.map((e) => wire(e, c)), leagues: ids.length };
  }

  function teamMatches(c, ev) {
    const out = [];
    for (const s of [ev.home, ev.away]) {
      if (!s) continue;
      for (const t of c.follows.teams) {
        if (t.teamId === s.team.id && (t.leagueId === ev.leagueId || cat.teamFamily(t.leagueId) === cat.teamFamily(ev.leagueId))) out.push(t);
      }
    }
    return out;
  }

  /** My Sports: live and upcoming games of everything followed, and a row per followed team / league / name. */
  async function myEvents() {
    const c = cfg();
    const ids = followedLeagueIds();
    const { loading } = await loadFor(ids);
    const t = now();
    const all = feeds.eventsFor(ids).filter((e) => e.state !== 'off');
    const isMine = (e) => followsLeague(c.follows, e.leagueId) || teamMatches(c, e).length > 0 || nameMatch(c, e);
    const mine = all.filter(isMine);
    const live = mine.filter((e) => e.state === 'live').sort(byTime);
    const upcoming = mine.filter((e) => e.state === 'pre' && e.startMs >= t - HOUR && e.startMs < t + 7 * DAY).sort(byTime).slice(0, 60);
    const rows = [];
    const window = (list) => {
      const lastDay = list.filter((e) => e.state === 'post' && e.startMs > t - 3 * DAY).sort((a, b) => b.startMs - a.startMs).slice(0, 2);
      const soon = list.filter((e) => e.state !== 'post' && e.startMs > t - 6 * HOUR).sort(byTime).slice(0, 8);
      return [...soon, ...lastDay];
    };
    for (const ft of c.follows.teams) {
      const fam = cat.teamFamily(ft.leagueId);
      const list = all.filter((e) => [e.home, e.away].some((s) => s && s.team.id === ft.teamId) && (e.leagueId === ft.leagueId || cat.teamFamily(e.leagueId) === fam));
      rows.push({ kind: 'team', key: teamKey(ft), name: ft.name, logo: ft.logo, color: ft.color, leagueId: ft.leagueId, teamId: ft.teamId, events: window(list).map((e) => wire(e, c)) });
    }
    for (const id of c.follows.leagues) {
      const l = cat.league(id);
      rows.push({ kind: 'league', key: id, name: l ? l.name : id, logo: feeds.leagueLogo(id), leagueId: id, events: window(all.filter((e) => e.leagueId === id)).map((e) => wire(e, c)) });
    }
    for (const w of c.follows.words) rows.push({ kind: 'name', key: w, name: w, word: w, events: window(all.filter((e) => nameMatch(c, e, w))).map((e) => wire(e, c)) });
    return { loading, live: live.map((e) => wire(e, c)), upcoming: upcoming.map((e) => wire(e, c)), rows, follows: getFollows() };
  }

  function nameMatch(c, ev, only) {
    const words = only ? [only] : c.follows.words;
    if (!words.length) return false;
    const text = cat.norm([ev.title, ev.home && ev.home.team.fullName, ev.away && ev.away.team.fullName, ev.subtitle].filter(Boolean).join(' '));
    return words.some((w) => { const n = cat.norm(w).trim(); return n && cat.hasWord(text, n); });
  }

  function findEvent(id) {
    for (const e of feeds.eventsFor(cat.leagues.map((l) => l.id))) if (e.id === id) return e;
    return null;
  }

  // ------------------------------------------------------------------ channels
  const channelCache = new Map();
  const inflightMatch = new Map();
  function guideRev() { try { const db = getGuide(); const r = db && (typeof db.revision === 'function' ? db.revision() : db.revision); return r == null ? 't' + Math.floor(now() / 120000) : String(r); } catch { return 'none'; } }

  const wireChannel = (ch) => ({ id: ch.id, name: ch.name, tvgId: ch.tvgId || '', logo: ch.logo || '', group: ch.group || '', url: ch.url });
  function wireMatch(m) {
    return {
      channel: wireChannel(m.channel), score: m.score, sure: m.sure, how: m.how, quality: m.quality,
      program: m.program ? { title: m.program.title, start: m.program.start, stop: m.program.stop } : null,
    };
  }

  /** Channels showing an event, best first, FHD/HD/SD duplicates grouped: [{ lead, copies:[...] }]. */
  async function channelsFor(eventId) {
    const ev = findEvent(eventId);
    if (!ev) return { eventId, found: false, groups: [], total: 0 };
    const channels = getChannels() || [];
    const sig = `${channels.length}|${guideRev()}|${ev.state}|${ev.startMs}`;
    const hit = channelCache.get(eventId);
    const ttl = ev.state === 'live' ? 60000 : 5 * 60000;
    if (hit && hit.sig === sig && now() - hit.at < ttl) return hit.value;
    if (inflightMatch.has(eventId)) return inflightMatch.get(eventId);
    const c = cfg();
    const p = matcher.match({
      events: [ev], channels, settings: { preferredGroups: c.preferredGroups, region: c.region, bestChannelFirst: c.bestChannelFirst !== false },
      ahead: Infinity, back: 3 * DAY,
    }).then((res) => {
      const list = res.get(ev.id) || [];
      const groups = groupCopies(list).map((g) => ({ lead: wireMatch(g[0]), copies: g.slice(1).map(wireMatch) }));
      const value = { eventId, found: true, groups, total: list.length, channelCount: channels.length, guide: !!getGuide() };
      channelCache.set(eventId, { sig, at: now(), value });
      if (channelCache.size > 200) channelCache.delete(channelCache.keys().next().value);
      return value;
    }).finally(() => inflightMatch.delete(eventId));
    inflightMatch.set(eventId, p);
    return p;
  }

  /** The league networks (NFL Network, NBA TV ...) present in the playlist. */
  function leagueNetworks() {
    const found = N.findLeagueNetworks(getChannels() || [], require('./matcher').isBanner);
    return N.LEAGUE_NETWORKS.map((n) => ({ id: n.id, name: n.name, mark: n.mark, color: n.color, channels: (found[n.id] || []).slice(0, 12).map(wireChannel) }));
  }

  async function standings(leagueId) {
    const l = cat.league(leagueId);
    if (!l) return null;
    return feeds.standings(l);
  }

  // ------------------------------------------------------------------ push & polling
  function notify(ids) {
    for (const id of ids) pendingIds.add(id);
    if (notifyTimer) return;
    notifyTimer = setTimeout(() => { notifyTimer = null; const out = [...pendingIds]; pendingIds = new Set(); send('sports:updated', { leagues: out, at: now() }); }, 250);
    if (notifyTimer.unref) notifyTimer.unref();
  }

  function followedLiveLeagues() {
    const c = cfg();
    const ids = followedLeagueIds();
    const out = new Set();
    for (const e of feeds.eventsFor(ids)) if (e.state === 'live' && (followsLeague(c.follows, e.leagueId) || teamMatches(c, e).length || nameMatch(c, e))) out.add(e.leagueId);
    return [...out];
  }
  function poll() {
    try {
      const ids = visible ? activeLeagueIds() : followedLiveLeagues();
      if (ids.length) feeds.tick(ids, { visible: true }).catch(() => {});
    } catch (e) { log('sports poll failed: ' + e.message); }
  }
  function start() {
    if (timer) return;
    timer = setInterval(poll, pollMs);
    if (timer.unref) timer.unref();
    // Warm followed leagues shortly after start so My Sports is ready when the tab opens.
    const w = setTimeout(() => { const ids = followedLeagueIds(); if (ids.length) feeds.ensure(ids, { deadlineMs: 0 }).catch(() => {}); }, 3000);
    if (w.unref) w.unref();
  }
  function stop() { if (timer) clearInterval(timer); timer = null; feeds.flush(); }
  function setVisible(v) { visible = !!v; if (visible) poll(); return visible; }

  return {
    leagues, toggleLeague, eventsForDay, myEvents, getFollows, follow, unfollow, channelsFor, leagueNetworks, standings,
    settings: publicSettings, saveSettings: saveSettingsPatch, setVisible, start, stop, poll, findEvent, feeds, wire, activeLeagueIds,
  };
}

module.exports = { createService };
