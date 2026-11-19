'use strict';
// ESPN public feed: URLs and parsing into the normalised SportsEvent model (port of SportsFeeds.parseEvent & friends).
//
// SportsEvent: { id, leagueId, startMs, hasTime, state: 'pre'|'live'|'post'|'off', title, subtitle, status, away, home,
//   broadcasts[], situation, progress (0..1|null), espnId, endMs, note, venue, standings: [{pos,name,detail}],
//   sessions: [{label,startMs,minutes,race}], source: 'espn' }
// Side: { team: { leagueId, id, name, fullName, abbr, location, color, logo, record }, score, record, winner, home, periods[] }
const cat = require('./catalog');

const ESPN = 'https://site.api.espn.com/apis/site/v2/sports/';

/** Leagues whose data does not come from ESPN's usual scoreboard (cricket seasons, HockeyTech, MiLB): not ported yet. */
function feedable(l) {
  return !!(l && l.espnPath && !/^(hockeytech|milb)\//.test(l.espnPath) && l.espnPath !== 'cricket/panel' && !l.guideOnly);
}

const EASTERN = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit' });
/** "yyyyMMdd" in US Eastern time (ESPN's day boundary). */
function dayKey(ms) { return EASTERN.format(new Date(ms)).replace(/-/g, ''); }

function scoreboardUrl(l, fromDay, toDay) {
  return `${ESPN}${l.espnPath}/scoreboard?dates=${fromDay}${toDay && toDay !== fromDay ? '-' + toDay : ''}&limit=1000${l.espnQuery || ''}`;
}
function scoreboardDefaultUrl(l) { return `${ESPN}${l.espnPath}/scoreboard?limit=1000${l.espnQuery || ''}`; }
function standingsUrl(l) {
  if (!l || !l.espnPath) return null;
  const divisions = ['football/nfl', 'basketball/nba', 'hockey/nhl', 'baseball/mlb'].includes(l.espnPath);
  return `https://site.api.espn.com/apis/v2/sports/${l.espnPath}/standings` + (divisions ? '?level=3' : '');
}

const str = (v) => (typeof v === 'string' && v.trim() && v !== 'null' ? v : (typeof v === 'number' ? String(v) : null));
const arr = (v) => (Array.isArray(v) ? v : []);

function parseIso(s) {
  if (!s || typeof s !== 'string') return null;
  let t = s.trim();
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z$/.test(t)) t = t.replace('Z', ':00Z'); // ESPN: "2026-11-15T18:00Z"
  const ms = Date.parse(t);
  return Number.isNaN(ms) ? null : ms;
}
function hexColor(s) {
  const h = String(s || '').replace('#', '');
  return /^[0-9a-fA-F]{6}$/.test(h) ? '#' + h.toLowerCase() : null;
}
const CLUB_COLORS = ['#b71c1c', '#0d47a1', '#1b5e20', '#4a148c', '#e65100', '#006064', '#880e4f', '#263238', '#33691e', '#1a237e', '#bf360c', '#3e2723'];
function clubColor(name) {
  let h = 0;
  for (const ch of String(name).toLowerCase()) h = (Math.imul(h, 31) + ch.charCodeAt(0)) | 0;
  return CLUB_COLORS[((h % CLUB_COLORS.length) + CLUB_COLORS.length) % CLUB_COLORS.length];
}

function pickLogo(logos) {
  let first = null;
  for (const l of arr(logos)) {
    const href = str(l && l.href);
    if (!href) continue;
    if (!first) first = href;
    const rel = arr(l.rel);
    if (rel.includes('default') && !rel.includes('dark')) return href;
  }
  return first;
}

/** A team (or an athlete in individual sports) as the feed describes it. */
function team(l, t, athlete) {
  if (t) {
    const id = str(t.id);
    if (!id) return null;
    const name = str(t.shortDisplayName) || str(t.name) || str(t.displayName);
    if (!name) return null;
    const color = hexColor(t.color) || (hexColor(t.alternateColor) ? null : clubColor(str(t.displayName) || str(t.name) || id));
    const rec = t.record && arr(t.record.items)[0];
    return {
      leagueId: l.id, id, name, fullName: str(t.displayName) || str(t.name) || '', abbr: str(t.abbreviation) || '',
      location: str(t.location), color, altColor: hexColor(t.alternateColor),
      logo: str(t.logo) || pickLogo(t.logos), record: (rec && str(rec.summary)) || null,
    };
  }
  if (athlete) {
    const name = str(athlete.displayName) || str(athlete.fullName);
    if (!name) return null;
    return {
      leagueId: l.id, id: str(athlete.id) || name, name: str(athlete.shortName) || name, fullName: name,
      abbr: name.split(' ').pop().slice(0, 3).toUpperCase(), location: null, color: null, altColor: null,
      logo: str(athlete.headshot && athlete.headshot.href) || str(athlete.flag && athlete.flag.href), record: null,
    };
  }
  return null;
}

const CLOCK_FIRST = /^(\d{1,2}:\d{2}(?:\.\d)?)\s*-\s*(.+)$/;
const OFF_STATUS = /POSTPONED|CANCEL|SUSPENDED|FORFEIT|ABANDON/;
const marketRank = (m) => ({ national: 0, home: 1, away: 2 }[String(m || '').toLowerCase()] ?? 3);
const isApp = (n) => { const s = n.toLowerCase(); return s.endsWith(' app') || s.includes('.com') || s.includes('league pass') || s.endsWith('+'); };

function progressOf(l, period, clockSec, displayClock) {
  if (!(period > 0)) return null;
  const clamp = (v) => Math.min(1, Math.max(0, v));
  const countdown = (periods, len) => {
    if (clockSec == null || Number.isNaN(clockSec)) return null;
    const done = Math.min(period - 1, periods) * len + (len - Math.min(len, Math.max(0, clockSec)));
    return clamp(done / (periods * len));
  };
  switch (l.sport) {
    case 'AMERICAN_FOOTBALL': return countdown(4, 900);
    case 'BASKETBALL': return l.id === 'basketball/nba' ? countdown(4, 720) : l.id === 'basketball/mens-college-basketball' ? countdown(2, 1200) : countdown(4, 600);
    case 'HOCKEY': return countdown(3, 1200);
    case 'BASEBALL': return clamp((period - 0.5) / 9);
    case 'FOOTBALL': { const m = /^(\d+)/.exec(displayClock || ''); return m ? clamp(Number(m[1]) / 90) : null; }
    default: return null;
  }
}

function scoreOf(sc) {
  if (sc && typeof sc === 'object') return str(sc.displayValue);
  if (typeof sc === 'string') return sc.trim() ? sc : null;
  if (typeof sc === 'number') return String(Math.trunc(sc));
  return null;
}

// ---- race weekends
function sessionOf(c, raceMinutes) {
  const at = parseIso(str(c.date));
  if (at == null) return null;
  const t = c.type || {};
  const abbr = str(t.abbreviation) || '';
  const words = [abbr, str(t.text), str(t.name)].filter(Boolean).join(' ').toLowerCase();
  let label; let minutes;
  const practice = /\bfp\s?(\d)|practice\s?(\d)?/.exec(words);
  if (/\b(ss|sq)\b|sprint (shootout|qual)/.test(words)) { label = 'Sprint Qualifying'; minutes = 45; }
  else if (/\bsprint\b/.test(words)) { label = 'Sprint'; minutes = 45; }
  else if (/\bqual/.test(words) || words.trim() === 'q') { label = 'Qualifying'; minutes = 60; }
  else if (practice) { const n = practice[1] || practice[2]; label = n ? `Practice ${n}` : 'Practice'; minutes = 60; }
  else if (/\brace\b/.test(words) || words.trim() === 'r') { label = 'Race'; minutes = raceMinutes; }
  else { label = str(t.text) || abbr || 'Session'; minutes = 60; }
  const state = (c.status && c.status.type && str(c.status.type.state)) || 'pre';
  return { label, start: at, state, minutes, race: label === 'Race' };
}

function raceWeekend(l, ev, espnId, weekendState, broadcasts, subtitle, now) {
  if (weekendState === 'off') return null;
  const sessions = arr(ev.competitions).map((c) => sessionOf(c, l.minutes)).filter(Boolean).sort((a, b) => a.start - b.start);
  if (!sessions.length) return null;
  const race = [...sessions].reverse().find((s) => s.race) || sessions[sessions.length - 1];
  const on = (s) => s.state === 'in' && now < s.start + s.minutes * 2 * 60000;
  const over = (s) => s.state === 'post' || (s.state === 'in' && !on(s)) || (s.state !== 'in' && now > s.start + s.minutes * 3 * 60000);
  const live = [...sessions].reverse().find(on);
  const raceDone = over(race);
  const next = sessions.find((s) => !over(s) && !on(s) && s.start + 30 * 60000 > now);
  let state; let at; let session;
  if (live) { state = 'live'; at = live.start; session = live; }
  else if (raceDone) { state = 'post'; at = race.start; session = race; }
  else if (next) { state = 'pre'; at = next.start; session = next; }
  else { state = 'pre'; at = race.start; session = race; }
  const day = new Intl.DateTimeFormat('en-US', { weekday: 'long' }).format(new Date(race.start));
  let note = null;
  if (state === 'live') note = session.race ? 'Race · Live' : `${session.label} · Live`;
  else if (state === 'pre') note = session.race ? 'Race' : `Next: ${session.label} · Race ${day}`;
  return {
    id: `espn:${l.id}:${espnId}`, leagueId: l.id, startMs: at, hasTime: true, state,
    title: str(ev.name) || str(ev.shortName) || l.name, subtitle, status: state === 'post' ? 'Final' : null,
    away: null, home: null, broadcasts, espnId, endMs: session.start + session.minutes * 60000, note,
    sessions: sessions.map((s) => ({ label: s.label, startMs: s.start, minutes: s.minutes, race: s.race })),
    standings: state === 'pre' && sessions.every((s) => s.state === 'pre') ? [] : safe(() => standingsOf(ev), []),
    source: 'espn',
  };
}

function safe(fn, dflt) { try { return fn(); } catch { return dflt; } }

function standingsOf(ev) {
  const all = arr(ev.competitions).filter((c) => arr(c.competitors).length >= 3);
  if (!all.length) return [];
  const isRace = (c) => { const t = c.type || {}; const w = [str(t.abbreviation), str(t.text), str(t.name)].filter(Boolean).join(' ').toLowerCase(); return /\brace\b/.test(w) || w.trim() === 'r'; };
  const stateOf = (c) => c.status && c.status.type && c.status.type.state;
  const started = all.filter((c) => ['in', 'post'].includes(stateOf(c)));
  const pick = [...started].reverse().find(isRace) || started.reduce((best, c) => (!best || arr(c.competitors).length > arr(best.competitors).length ? c : best), null);
  if (!pick) return [];
  const rows = [];
  arr(pick.competitors).forEach((co, i) => {
    const name = (co.athlete && (str(co.athlete.displayName) || str(co.athlete.shortName) || str(co.athlete.fullName))) || (co.team && (str(co.team.displayName) || str(co.team.name)));
    if (!name) return;
    const order = Number(co.order) > 0 ? Number(co.order) : i + 1;
    let score = scoreOf(co.score);
    if (score === '0' || score === '-') score = null;
    rows.push({ order, pos: String(order), name, detail: score });
  });
  return rows.sort((a, b) => a.order - b.order).slice(0, 20).map(({ pos, name, detail }) => ({ pos, name, detail }));
}

/** Tennis matches / fight bouts: live first, then recent results, then next ones. */
function matchesOf(ev, l, now, mainFirst) {
  const comps = [];
  for (const g of arr(ev.groupings)) for (const c of arr(g.competitions)) comps.push(c);
  for (const c of arr(ev.competitions)) comps.push(c);
  const nameOf = (co) => (co && co.athlete && (str(co.athlete.shortName) || str(co.athlete.displayName))) || (co && co.team && str(co.team.displayName));
  const all = [];
  for (const c of comps) {
    const cos = arr(c.competitors);
    if (cos.length !== 2) continue;
    const [a, b] = cos;
    const an = nameOf(a); const bn = nameOf(b);
    if (!an || !bn) continue;
    const st = c.status && c.status.type;
    const state = (st && str(st.state)) || 'pre';
    const at = parseIso(str(c.date) || str(c.startDate)) || 0;
    const sets = (x) => arr(x && x.linescores).map((s) => (typeof s.value === 'number' ? Math.trunc(s.value) : null)).filter((v) => v != null);
    const aw = !!(a && a.winner); const bw = !!(b && b.winner);
    const first = bw ? bn : an; const second = bw ? an : bn;
    const fs = bw ? sets(b) : sets(a); const ss = bw ? sets(a) : sets(b);
    const score = fs.map((x, i) => (i < ss.length ? `${x}-${ss[i]}` : null)).filter(Boolean).join(' ');
    const r = str(c.round && c.round.displayName) || str(c.type && c.type.abbreviation);
    let round = '';
    if (r) {
      if (/quarter/i.test(r)) round = 'QF'; else if (/semi/i.test(r)) round = 'SF'; else if (/^finals?$/i.test(r)) round = 'F';
      else if (/\d+/.test(r) && r.length > 4) round = 'R' + /\d+/.exec(r)[0]; else round = r.slice(0, 4);
    }
    let line;
    const detail = str(st && st.shortDetail);
    if (state === 'post') line = { pos: round, name: aw || bw ? `${first} def. ${second}` : `${first} v ${second}`, detail: score || detail };
    else if (state === 'in') line = { pos: 'LIVE', name: `${an} v ${bn}`, detail: score || detail };
    else line = { pos: round, name: `${an} v ${bn}`, detail: at > 0 ? new Date(at).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' }) : null };
    all.push({ state, at, line });
  }
  const live = all.filter((m) => m.state === 'in');
  if (mainFirst) return [...live, ...all.filter((m) => m.state !== 'in').reverse()].map((m) => m.line).slice(0, 14);
  const done = all.filter((m) => m.state === 'post' && now - m.at < 30 * 3600000).sort((x, y) => y.at - x.at);
  const next = all.filter((m) => m.state === 'pre' && m.at >= now - 3600000).sort((x, y) => x.at - y.at);
  return [...live, ...done, ...next].map((m) => m.line).slice(0, 12);
}

/** One ESPN event -> SportsEvent (null when it cannot be understood). `now` is injectable for tests. */
function parseEvent(l, ev, now = Date.now()) {
  const espnId = str(ev.id);
  const start = parseIso(str(ev.date));
  if (!espnId || start == null) return null;
  const end = parseIso(str(ev.endDate));
  const comp = arr(ev.competitions)[0];
  const st = l.kind === 'EVENT' ? (ev.status || (comp && comp.status)) : ((comp && comp.status) || ev.status);
  const type = st && st.type;
  const stateRaw = type && str(type.state);
  const name = (type && str(type.name)) || '';
  let state = OFF_STATUS.test(name) ? 'off' : stateRaw === 'in' ? 'live' : stateRaw === 'post' ? 'post' : 'pre';
  const short = (l.sport === 'CRICKET' ? (st && str(st.summary)) || str(ev.status && ev.status.summary) : null) || (type && (str(type.shortDetail) || str(type.detail)));
  let status = null;
  if (state === 'live') { const m = short && CLOCK_FIRST.exec(short); status = short ? (m ? `${m[2]} · ${m[1]}` : short) : null; }
  else if (state === 'post') status = short || 'Final';
  else if (state === 'off') status = (type && str(type.description)) || 'Postponed';
  const period = (st && Number(st.period)) || 0;
  const clock = st && typeof st.clock === 'number' ? st.clock : NaN;
  const progress = state === 'live' ? progressOf(l, period, clock, st && str(st.displayClock)) : null;

  const ranked = [];
  // Multi-session events (race weekends): the first session often has no broadcasts; use the last one that does.
  const hasB = (c) => c && (arr(c.broadcasts).length || arr(c.geoBroadcasts).length);
  const bcomp = hasB(comp) || l.kind !== 'EVENT' ? comp : ([...arr(ev.competitions)].reverse().find(hasB) || comp);
  if (bcomp) {
    for (const b of arr(bcomp.broadcasts)) for (const n of arr(b.names)) if (str(n)) ranked.push([n, marketRank(b.market)]);
    for (const b of arr(bcomp.geoBroadcasts)) {
      const n = str(b.media && b.media.shortName);
      if (n) ranked.push([n, marketRank(b.market && b.market.type)]);
    }
  }
  const broadcasts = [...new Set(ranked.map((r, i) => ({ n: r[0], r: r[1], i })).sort((x, y) => x.r - y.r || (isApp(x.n) ? 1 : 0) - (isApp(y.n) ? 1 : 0) || x.i - y.i).map((x) => x.n))];
  const venue = (comp && comp.venue && str(comp.venue.fullName)) || (ev.circuit && str(ev.circuit.fullName)) || null;
  const wk = ev.week && Number(ev.week.number) > 0 ? `Week ${ev.week.number}` : null;
  const note0 = comp && arr(comp.notes)[0];
  const headline = (note0 && str(note0.headline)) || null;
  const join = (...p) => p.filter(Boolean).join(' · ') || null;

  if (l.kind === 'EVENT' && l.sport === 'RACING') {
    const r = raceWeekend(l, ev, espnId, state, broadcasts, join(headline, venue), now);
    if (r) return { ...r, venue };
  }
  if (l.kind === 'EVENT') {
    const multiDay = end != null && end - start > 20 * 3600000;
    const underWay = multiDay && end + 24 * 3600000 > now && start <= now;
    const evState = underWay && (state === 'post' || state === 'pre') ? 'live' : state;
    return {
      id: `espn:${l.id}:${espnId}`, leagueId: l.id, startMs: start, hasTime: !multiDay, state: evState,
      title: str(ev.name) || str(ev.shortName) || l.name, subtitle: join(headline, venue), status, away: null, home: null,
      broadcasts, espnId, endMs: end, venue,
      note: multiDay ? 'Until ' + new Date(end).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }) : null,
      standings: safe(() => (l.sport === 'TENNIS' || l.sport === 'FIGHTING' ? matchesOf(ev, l, now, l.sport === 'FIGHTING') : state === 'pre' ? [] : standingsOf(ev)), []),
      sessions: [], source: 'espn',
    };
  }

  if (!comp) return null;
  let home = null; let away = null;
  for (const co of arr(comp.competitors)) {
    const t = team(l, co.team, co.athlete);
    if (!t) continue;
    const sc = scoreOf(co.score);
    const side = {
      team: t, score: state === 'pre' ? null : sc,
      record: (arr(co.records)[0] && str(arr(co.records)[0].summary)) || null,
      winner: co.winner === true, home: str(co.homeAway) === 'home',
      periods: arr(co.linescores).map((x) => str(x.displayValue) || (typeof x.value === 'number' ? String(Math.trunc(x.value)) : null)).filter((x) => x != null),
    };
    if (side.home && !home) home = side; else if (!away) away = side; else if (!home) home = side;
  }
  if (!home || !away) return null;
  let situation = null;
  if (comp.situation && state === 'live') {
    const s = comp.situation;
    const dd = str(s.downDistanceText) || str(s.shortDownDistanceText);
    const pos = str(s.possession);
    const who = [home, away].find((x) => x.team.id === pos);
    situation = dd && who ? `${who.team.abbr} ball · ${dd}` : dd || null;
  }
  const homeFirst = cat.SPORTS[l.sport].homeFirst;
  const title = homeFirst ? `${home.team.name} v ${away.team.name}` : `${away.team.name} at ${home.team.name}`;
  return {
    id: `espn:${l.id}:${espnId}`, leagueId: l.id, startMs: start, hasTime: true, state, title,
    subtitle: join(headline, wk, venue), status, away, home, broadcasts, situation, progress, espnId, endMs: null, venue,
    note: null, standings: [], sessions: [], source: 'espn',
  };
}

/** A scoreboard JSON document -> { events, logo }. Bad events are skipped, never fatal. */
function parseScoreboard(l, root, now = Date.now()) {
  const events = [];
  for (const ev of arr(root && root.events)) {
    try { const e = parseEvent(l, ev, now); if (e) events.push(e); } catch { /* skip */ }
  }
  let logo = null;
  const logos = arr(root && root.leagues && root.leagues[0] && root.leagues[0].logos);
  for (const x of logos) {
    const href = typeof x.href === 'string' && x.href.startsWith('http') ? x.href : null;
    if (!href) continue;
    if (!logo) logo = href;
    if (arr(x.rel).includes('dark')) { logo = href; break; }
  }
  return { events, logo };
}

/** Teams list JSON -> TeamInfo[] */
function parseTeams(l, root) {
  const teams = arr(root && root.sports && root.sports[0] && root.sports[0].leagues && root.sports[0].leagues[0] && root.sports[0].leagues[0].teams);
  return teams.map((t) => team(l, t && t.team)).filter((t) => t && t.fullName);
}

// ---- standings (ESPN: /apis/v2/sports/<path>/standings)
function parseStandings(l, root, now = Date.now()) {
  const leaves = [];
  let season = null;
  const walk = (node, parent, isRoot) => {
    const sd = node.standings;
    if (sd && arr(sd.entries).length) {
      if (!season) season = str(sd.seasonDisplayName) || (sd.season != null ? String(sd.season) : null);
      leaves.push({ name: str(node.name) || str(node.abbreviation) || l.name, parent, node });
    }
    const name = str(node.name);
    for (const k of arr(node.children)) walk(k, isRoot ? null : name, false);
  };
  walk(root || {}, null, true);
  if (!leaves.length) return null;
  const firstEntry = leaves[0].node.standings.entries[0];
  const athletes = !!(firstEntry && firstEntry.athlete);
  const used = athletes ? leaves.slice(0, 1) : leaves;
  const names = new Set();
  for (const { node } of used) for (const s of arr(node.standings.entries[0] && node.standings.entries[0].stats)) if (str(s.name)) names.add(s.name);
  const kind = athletes ? 'racing' : names.has('otLosses') ? 'hockey'
    : (l.sport === 'FOOTBALL' || (names.has('points') && names.has('ties') && !names.has('winPercent'))) ? 'table' : 'record';
  const fmt = (n) => (Number.isInteger(n) ? String(n) : n.toFixed(1));
  const groups = used.map(({ name, parent, node }) => {
    const rows = [];
    for (const e of node.standings.entries) {
      const who = e.team || e.athlete;
      if (!who || !str(who.id)) continue;
      const stats = {};
      const values = {};
      let record = null;
      for (const s of arr(e.stats)) {
        const t = String(s.type || '').toLowerCase();
        if (t === 'total') record = str(s.summary) || str(s.displayValue);
        if (str(s.name)) { stats[s.name] = s; const d = str(s.displayValue); if (d && d.trim()) values[s.name] = d.trim(); }
      }
      if (kind === 'racing') { const p = values.championshipPts || values.points; if (p) values.points = p; }
      if (values.gamesBehind === '-') values.gamesBehind = '–';
      const num = (k) => { const s = stats[k]; if (!s) return null; const v = Number(s.value); return Number.isNaN(v) ? null : v; };
      const logo = (e.team && arr(e.team.logos)[0] && str(e.team.logos[0].href)) || (e.athlete && e.athlete.flag && str(e.athlete.flag.href)) || null;
      const dn = str(who.displayName) || str(who.name);
      if (!dn) continue;
      rows.push({
        id: String(who.id), name: dn, shortName: str(who.shortDisplayName) || str(who.shortName) || dn,
        abbr: str(who.abbreviation) || dn.slice(0, 3).toUpperCase(), logo, pos: Math.trunc(num('rank') || 0), values, record,
        zone: (e.note && str(e.note.description)) || null, clincher: (values.clincher && values.clincher !== '*' && values.clincher.length <= 2) ? values.clincher : null,
      });
    }
    const ranked = rows.every((r) => r.pos > 0) ? [...rows].sort((a, b) => a.pos - b.pos) : rows;
    const withPos = ranked.map((r, i) => (r.pos > 0 ? r : { ...r, pos: i + 1 }));
    if (kind === 'racing' && withPos.length) {
      const lead = Number(withPos[0].values.points);
      if (!Number.isNaN(lead)) for (const r of withPos) { const p = Number(r.values.points); r.values.behind = Number.isNaN(p) || r.pos === 1 ? '–' : '-' + fmt(lead - p); }
    }
    return { name, parent, rows: withPos };
  });
  const anyTies = groups.some((g) => g.rows.some((r) => r.values.ties && r.values.ties !== '0'));
  const C = (key, label) => ({ key, label });
  const columns = {
    table: [C('gamesPlayed', 'P'), C('wins', 'W'), C('ties', 'D'), C('losses', 'L'), C('pointDifferential', 'GD'), C('points', 'PTS')],
    hockey: [C('gamesPlayed', 'GP'), C('wins', 'W'), C('losses', 'L'), C('otLosses', 'OTL'), C('points', 'PTS'), C('streak', 'STRK')],
    racing: [C('points', 'PTS'), C('behind', 'BEHIND')],
    record: [C('wins', 'W'), C('losses', 'L'), anyTies ? C('ties', 'T') : null, C('winPercent', 'PCT'), C('gamesBehind', 'GB'), C('streak', 'STRK')].filter(Boolean),
  }[kind];
  let filled = columns.filter((c) => c.key === 'behind' || groups.some((g) => g.rows.some((r) => r.values[c.key])));
  if (filled.length < 2 && !athletes) {
    const first = used[0].node.standings.entries[0];
    const own = [];
    for (const s of arr(first && first.stats)) {
      const n = str(s.name);
      if (!n || ['rank', 'rankChange', 'clincher', 'playoffSeed'].includes(n) || !str(s.displayValue)) continue;
      if (own.some((c) => c.key === n)) continue;
      own.push(C(n, (str(s.abbreviation) || str(s.shortDisplayName) || str(s.displayName) || n).toUpperCase().slice(0, 5)));
    }
    if (own.slice(0, 7).length >= 2) filled = own.slice(0, 7);
  }
  return { leagueId: l.id, season, kind, columns: filled, groups, fetchedAt: now };
}

module.exports = { feedable, dayKey, scoreboardUrl, scoreboardDefaultUrl, standingsUrl, parseEvent, parseScoreboard, parseTeams, parseStandings, parseIso, ESPN };
