'use strict';
// Finds the playlist channels that show a given game. Port of SportsMatcher.kt (match(), Terms, sorting, copy grouping).
//
// Sources, strongest first:
//   1. the guide (programme titles/descriptions that name both teams / the event), a half-hour slot of games at a time
//   2. the channel's own name ("Lakers v Celtics", "Sky Sports F1")
//   3. the network ESPN lists, with a sports show on at the time
// The guide comes in through `getGuide()` -> an object with `findByWords(words[], fromMs, toMs)` ->
// [{ tvgId, start, stop, title, desc }] (see docs/CONTRACTS.md); `range(tvgIds, fromMs, toMs)` is used when present.
// Nothing is skipped for speed: the work is chunked and yields to the event loop so the UI stays responsive.
const C = require('./catalog');
const N = require('./networks');

const { norm, hasWord } = C;
const MINUTE = 60000;
const HOUR = 60 * MINUTE;
const AHEAD_MS = 56 * HOUR;

// ------------------------------------------------------------------------------------------------ constants
const NAME_PREFIX = /^\s*[A-Za-z]{2,3}\s*[:|-]\s*/;
const NAME_NOISE = / (fhd|uhd|hd|sd|4k|hevc|raw|backup|vip) /g;
const TWENTY_FOUR_SEVEN = /\b24\s*[/\\|\-x]?\s*7\b/i;
const MATCHUP = /\s(?:at|vs|v|versus)\s/;
const BANNER_RUN = /([#=*~_▬━─•■◆★|-])\1{2,}/;
const LIVE_PREFIX = /^\s*(?:live\s*[:\-–|]?\s*)/i;
const NEW_PREFIX = /^\s*\(?new\)?\s*[:\-–|]\s*/i;
const NEW_SUFFIX = /\s*(?:\(new\)|\[new]|[-–|]\s*new)\s*$/i;
const LANGUAGE_TAG = /[[(]([A-Za-z]{2,3})[\])]/g;

const SPORT_WORDS = ['football', 'basketball', 'hockey', 'baseball', 'soccer', 'game', 'live', 'ncaa', 'college', 'sports',
  'nfl', 'nba', 'nhl', 'mlb', 'mls', 'playoff', 'playoffs', 'bowl', 'series'];

const GENERIC_TOURNAMENT_WORDS = new Set(['atp', 'wta', 'tour', 'tennis', 'golf', 'open', 'cup', 'championship', 'championships', 'masters',
  'classic', 'international', 'invitational', 'presented', 'the', 'for', 'and', 'ladies', 'mens', 'womens',
  'grand', 'prix', 'race', 'series', 'world', 'trophy', 'tournament', 'challenger', 'final', 'finals',
  'round', 'qualifying', 'practice', 'sprint', 'week', 'weekend', 'live', 'season', 'formula', 'edition']);
const RARE_TOURNAMENT_WORDS = new Set(['final', 'finals', 'masters', 'championship', 'championships', 'classic', 'invitational',
  'cup', 'trophy', 'challenger', 'international', 'open']);
const GENERIC_EVENT_WORDS = new Set(['grand prix', 'grand', 'prix', 'championship', 'championships', 'open', 'classic', 'tour', 'the', 'final',
  'finals', 'race', 'series', 'invitational', 'masters', 'trophy', 'fight night', 'night', 'main card', 'prelims']);

const REGION_WORDS = {
  UK: ['uk', 'gb', 'united kingdom', 'britain', 'england', 'scotland'],
  IE: ['ie', 'ireland', 'irish', 'irl', 'eir'],
  US: ['us', 'usa', 'united states'],
  CA: ['ca', 'canada', 'can'],
  AU: ['au', 'aus', 'australia', 'nz', 'new zealand'],
};
const LANGUAGE_WORDS = {
  es: ['es', 'esp', 'spain', 'spanish', 'espanol', 'latino', 'latin', 'latam', 'mx', 'mexico', 'argentina', 'deportes'],
  fr: ['fr', 'fra', 'france', 'french', 'francais'], de: ['de', 'ger', 'germany', 'german', 'deutsch', 'deutschland'],
  it: ['it', 'ita', 'italy', 'italia', 'italian', 'italiano'], pt: ['pt', 'por', 'portugal', 'portuguese', 'br', 'brasil', 'brazil'],
  nl: ['nl', 'netherlands', 'dutch', 'nederland'], pl: ['pl', 'poland', 'polish', 'polska'], tr: ['tr', 'turkey', 'turkish', 'turkiye'],
  ar: ['arabic', 'arab', 'mena'], ru: ['ru', 'russia', 'russian'],
};
const COPY_WORDS = new Set(['4k', 'uhd', '2160', '2160p', 'fhd', '1080', '1080p', '1080i', 'hd', '720', '720p', 'sd', '480', '576',
  'hevc', 'h265', 'h264', '50fps', '60fps', 'fps', 'backup', 'alt']);

const SPORT_NAMES = {
  RUGBY: ['rugby', 'nrl'],
  FOOTBALL: ['soccer', 'football', 'women s super league', 'wsl', 'indian super league', 'chinese super league', 'super league greece', 'swiss super league', 'super league kerala'],
  AMERICAN_FOOTBALL: ['nfl', 'american football', 'ncaaf'], AUSSIE_RULES: ['afl', 'aflw', 'aussie rules'],
  GAELIC: ['gaelic', 'hurling', 'camogie', 'gaa'], VOLLEYBALL: ['volleyball', 'beach volley'],
  BASKETBALL: ['basketball', 'nba', 'wnba', 'nbl', 'euroleague'], NETBALL: ['netball'], HOCKEY: ['hockey', 'nhl'],
  BASEBALL: ['baseball', 'mlb'], CRICKET: ['cricket'], DARTS: ['darts'], SNOOKER: ['snooker'], TENNIS: ['tennis'], GOLF: ['golf'],
  RACING: ['motogp', 'formula 1', 'formula one', 'nascar', 'indycar', 'superbikes', 'wsbk'], LACROSSE: ['lacrosse'],
  OTHER: ['handball', 'futsal', 'badminton', 'table tennis', 'esports', 'cycling', 'horse racing'],
};

// ------------------------------------------------------------------------------------------------ channel helpers
function isBanner(name) {
  name = String(name || '');
  if (BANNER_RUN.test(name)) return true;
  let letters = 0;
  for (const ch of name) if (/[\p{L}\p{N}]/u.test(ch)) letters++;
  return name.length >= 6 && letters * 10 < name.length * 4;
}

function cleanTitle(t) {
  let s = C.withoutSmallMarks(t);
  for (let i = 0; i < 2; i++) s = s.replace(LIVE_PREFIX, '').replace(NEW_PREFIX, '').replace(NEW_SUFFIX, '').trim();
  return s || t;
}

function nameQuality(name) {
  const n = norm(name);
  if (['4k', 'uhd', '2160', '2160p'].some((w) => hasWord(n, w))) return '4K';
  if (['fhd', '1080', '1080p', '1080i'].some((w) => hasWord(n, w))) return 'FHD';
  if (['hd', '720', '720p'].some((w) => hasWord(n, w))) return 'HD';
  if (['sd', '480', '576'].some((w) => hasWord(n, w))) return 'SD';
  return null;
}
const qualityRank = (q) => ({ '4K': 4, FHD: 3, HD: 2, SD: 1 }[q] || 2);

function prefixOf(ch) {
  return /[:|]/.test(ch.name) ? norm(ch.name.split(':')[0].split('|')[0].slice(0, 16)) : ' ';
}
function tagsOf(ch) {
  const m = [...String(ch.name).matchAll(LANGUAGE_TAG)].map((x) => x[1]).join(' ');
  return norm(m);
}
function channelLanguage(ch) {
  const prefix = prefixOf(ch);
  const tags = tagsOf(ch);
  const group = norm(String(ch.group || '').split('|')[0].split(':')[0]);
  for (const [lang, ws] of Object.entries(LANGUAGE_WORDS)) {
    if (ws.some((w) => hasWord(prefix, w) || hasWord(tags, w) || (w.length > 3 && hasWord(group, w)))) return lang;
  }
  return null;
}
/** Where a channel says it is from: "lang:es", or "UK"/"US"/"IE"/"CA"/"AU", or null. */
function channelRegionCode(ch) {
  const lang = channelLanguage(ch);
  if (lang) return 'lang:' + lang;
  const prefix = prefixOf(ch);
  const tags = tagsOf(ch);
  const group = norm(ch.group || '');
  for (const [r, ws] of Object.entries(REGION_WORDS)) if (ws.some((w) => hasWord(prefix, w) || hasWord(tags, w) || hasWord(group, w))) return r;
  return null;
}

/** The same channel's copies (FHD/HD/SD) share this key; other regions' don't. */
function copyKey(ch) {
  return norm(String(ch.name).replace(/\+/g, ' plus ')).split(' ').filter((w) => w && !COPY_WORDS.has(w)).join(' ');
}
/** Matches in order, with each channel's copies together (the first copy leads, or `playingKey`'s while it plays). */
function groupCopies(list, playingKey = null) {
  const groups = new Map();
  for (const m of list) {
    const k = copyKey(m.channel) || chKey(m.channel);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(m);
  }
  return [...groups.values()].map((g) => {
    const i = g.findIndex((m) => chKey(m.channel) === playingKey);
    return i > 0 ? [g[i], ...g.filter((_, j) => j !== i)] : g;
  });
}

function preferenceRank(settings, locale) {
  const groups = new Set(((settings && settings.preferredGroups) || []).map((g) => String(g).toLowerCase().trim()));
  const words = REGION_WORDS[C.regionOf(settings)] || [];
  let myLanguage = 'en';
  try { myLanguage = new Intl.Locale(locale || Intl.DateTimeFormat().resolvedOptions().locale).language.toLowerCase(); } catch { /* en */ }
  return (ch) => {
    if (groups.size && groups.has(String(ch.group || '').toLowerCase().trim())) return 2;
    const lang = channelLanguage(ch);
    if (lang && lang !== myLanguage) return -1;
    if (words.length) {
      const g = norm(ch.group || '');
      if (words.some((w) => hasWord(g, w))) return 1;
      if (/[:|]/.test(ch.name)) { const n = prefixOf(ch); if (words.some((w) => hasWord(n, w))) return 1; }
    }
    return 0;
  };
}

function sortMatches(list, bestFirst, rank = () => 0) {
  const band = (s) => (s >= 150 ? 3 : s >= 80 ? 2 : s >= 55 ? 1 : 0);
  const own = (m) => (band(m.score) === 3 ? 1 : 0);
  const out = list.map((m, i) => [m, i]);
  out.sort(bestFirst
    ? (a, b) => own(b[0]) - own(a[0]) || rank(b[0].channel) - rank(a[0].channel) || band(b[0].score) - band(a[0].score)
      || qualityRank(b[0].quality) - qualityRank(a[0].quality) || b[0].score - a[0].score || a[0].channel.name.length - b[0].channel.name.length || a[1] - b[1]
    : (a, b) => own(b[0]) - own(a[0]) || rank(b[0].channel) - rank(a[0].channel) || b[0].score - a[0].score || a[1] - b[1]);
  return out.map((x) => x[0]);
}

const chKey = (ch) => ch.url || ch.id;

// ------------------------------------------------------------------------------------------------ how a game is named
/** What to look for: team names (strong/weak), abbreviations, league words, event words. */
function termsFor(ev) {
  const lg = C.league(ev.leagueId);
  const leagueWords = ((lg && lg.guideWords) || []).map((w) => norm(w).trim()).filter((w) => w.length >= 2);
  if (lg && ev.away && ev.home) {
    const [a, aWeak] = C.teamNames(ev.away.team, lg);
    const [b, bWeak] = C.teamNames(ev.home.team, lg);
    const ab = (t) => { const x = norm(t.abbr).trim(); return x.length >= 2 && x.length <= 4 ? x : null; };
    return makeTerms({ a, aWeak, b, bWeak, abbrA: ab(ev.away.team), abbrB: ab(ev.home.team), leagueWords, eventWords: [], sqlWords: [...new Set([...a, ...b])] });
  }
  if (lg && lg.sport === 'WRESTLING') {
    const show = norm(C.wrestlingHeadline(ev.title)).trim();
    const words = show.length >= 3 ? [show] : [];
    if (words.length) return makeTerms({ leagueWords, eventWords: words, sqlWords: [...new Set([...words, ...leagueWords])] });
  }
  const core = [...new Set(norm(ev.title).trim().split(/ vs | v | at /)
    .flatMap((part) => [part.trim(), ...part.trim().split(' ').filter((w) => w.length >= 5)])
    .map((s) => s.trim())
    .filter((s) => s.length >= 4 && !GENERIC_EVENT_WORDS.has(s) && !leagueWords.some((lw) => lw === s)))].slice(0, 8);
  const fight = lg && lg.sport === 'FIGHTING' ? C.fightSides(ev.title) : null;
  return makeTerms({
    leagueWords, eventWords: core,
    sqlWords: [...new Set([...core, ...leagueWords, ...(fight ? fight[0] : []), ...(fight ? fight[1] : [])])].filter((w) => w.length >= 3),
    fightA: fight ? fight[0] : [], fightB: fight ? fight[1] : [],
  });
}

function makeTerms(o) {
  const t = { a: [], aWeak: [], b: [], bWeak: [], abbrA: null, abbrB: null, leagueWords: [], eventWords: [], sqlWords: [], fightA: [], fightB: [], ...o };
  const any = (text, list) => list.some((w) => hasWord(text, w));

  t.baseScore = (nt, nd) => {
    if (t.a.length && t.b.length) {
      const aT = any(nt, t.a); const bT = any(nt, t.b);
      const aW = any(nt, t.aWeak); const bW = any(nt, t.bWeak);
      const aD = any(nd, t.a); const bD = any(nd, t.b);
      if (aT && bT) return 100;
      if ((aT && bW) || (bT && aW)) return 90;
      if ((aT || aD) && (bT || bD)) return 82;
      if ((aT || bT) && any(nt, t.leagueWords)) return 58;
      return 0;
    }
    const ew = t.eventWords.filter((w) => hasWord(nt, w) || hasWord(nd, w)).length;
    const lw = any(nt, t.leagueWords);
    if (lw && ew > 0) return 92;
    if (ew >= 2) return 75;
    if (ew > 0 && any(nd, t.leagueWords)) return 70;
    return 0;
  };
  t.scoreText = (nt, nd) => {
    const base = t.baseScore(nt, nd);
    if (!t.fightA.length || !t.fightB.length) return base;
    const aT = any(nt, t.fightA); const bT = any(nt, t.fightB);
    const fight = aT && bT ? 100 : (aT || any(nd, t.fightA)) && (bT || any(nd, t.fightB)) ? 85 : 0;
    return Math.max(base, fight);
  };
  /** The show names a game ("Bills at Raiders") that isn't this one. */
  t.otherGame = (text) => {
    if (!t.a.length || !t.b.length) return false;
    const us = any(text, t.a) || any(text, t.aWeak) || any(text, t.b) || any(text, t.bWeak)
      || [t.abbrA, t.abbrB].filter(Boolean).some((x) => x.length >= 2 && hasWord(text, x));
    return us ? false : MATCHUP.test(text);
  };
  t.namesLeague = (nt, nd) => t.leagueWords.some((w) => hasWord(nt, w) || hasWord(nd, w));
  t.anchorTokens = () => {
    const out = new Set();
    for (const l of [t.a, t.b, t.leagueWords, t.eventWords]) for (const term of l) { const f = term.trim().split(' ')[0]; if (f) out.add(f); }
    if (t.abbrA) out.add(t.abbrA);
    if (t.abbrB) out.add(t.abbrB);
    for (const w of t.fightA) out.add(w);
    for (const w of t.fightB) out.add(w);
    return out;
  };
  t.leagueInName = (nn) => t.leagueWords.some((w) => w.length >= 2 && w !== 'grand prix' && hasWord(nn, w));
  t.inName = (nn) => {
    if (t.fightA.length && t.fightB.length && any(nn, t.fightA) && any(nn, t.fightB)) return true;
    if (t.a.length && t.b.length) {
      if (any(nn, t.a) && any(nn, t.b)) return true;
      const x = t.abbrA; const y = t.abbrB;
      if (!x || !y) return false;
      return [`${x} ${y}`, `${x} at ${y}`, `${x} vs ${y}`, `${x} v ${y}`, `${y} ${x}`, `${y} vs ${x}`, `${y} v ${x}`, `${y} at ${x}`].some((w) => hasWord(nn, w));
    }
    return t.eventWords.length > 0 && any(nn, t.leagueWords) && t.eventWords.filter((w) => hasWord(nn, w)).length >= 1;
  };
  return t;
}

// ------------------------------------------------------------------------------------------------ filler
const nameNorm = (name) => norm(String(name).replace(NAME_PREFIX, '')).replace(NAME_NOISE, ' ').replace(/ +/g, ' ');
/** Not a real broadcast: a 24/7 channel, a 6 hour+ block, or a listing that is just the channel's name. */
function fillerOrLoop(ch, p) {
  if (TWENTY_FOUR_SEVEN.test(ch.name) || TWENTY_FOUR_SEVEN.test(ch.group || '')) return true;
  if (p.stop - p.start >= 6 * HOUR) return true;
  const t = nameNorm(cleanTitle(p.title)).trim();
  const c = nameNorm(ch.name).trim();
  if (!t || !c) return !t;
  if (t === c) return true;
  if (MATCHUP.test(' ' + norm(p.title.replace(/@/g, ' at ')).trim() + ' ')) return false;
  return c.includes(' ') && c.length >= 8 && (' ' + t + ' ').includes(' ' + c + ' ');
}

// ------------------------------------------------------------------------------------------------ the matcher
const yieldNow = () => new Promise((r) => setImmediate(r));

function createMatcher({ getGuide = () => null, now = Date.now, budgetMs = 8 } = {}) {
  let nameDataCache = null;
  let slotCache = new Map();
  let slotCacheKey = null;

  // yield to the event loop whenever `budgetMs` of work has gone by
  function makeYielder() {
    let last = Date.now();
    return async () => { if (Date.now() - last >= budgetMs) { await yieldNow(); last = Date.now(); } };
  }

  async function nameData(channels, tick) {
    if (nameDataCache && nameDataCache.channels === channels && nameDataCache.length === channels.length) return nameDataCache;
    let fp = channels.length;
    for (let i = 0; i < channels.length; i++) {
      const ch = channels[i];
      fp = (Math.imul(fp, 31) + hashStr(ch.name) * 17 + hashStr(chKey(ch))) | 0;
      if (i % 2048 === 0) await tick();
    }
    if (nameDataCache && nameDataCache.fingerprint === fp) { nameDataCache.channels = channels; nameDataCache.length = channels.length; return nameDataCache; }
    const names = [];
    const tokenIndex = new Map();
    const byTvg = new Map();
    const byKey = new Map();
    for (let i = 0; i < channels.length; i++) {
      const ch = channels[i];
      if (i % 512 === 0) await tick();
      byKey.set(chKey(ch), ch);
      if (ch.tvgId) { const k = String(ch.tvgId); (byTvg.get(k) || byTvg.set(k, []).get(k)).push(ch); }
      if (isBanner(ch.name)) continue;
      const nn = norm(String(ch.name).replace(/\+/g, ' plus ').replace(/@/g, ' at '));
      const idx = names.length;
      names.push([ch, nn]);
      for (const tok of new Set(nn.split(' '))) if (tok) (tokenIndex.get(tok) || tokenIndex.set(tok, []).get(tok)).push(idx);
    }
    const discNames = new Set(names.filter(([, nn]) => hasWord(nn, 'disc golf')).map(([ch]) => chKey(ch)));
    nameDataCache = { channels, length: channels.length, fingerprint: fp, names, tokenIndex, byTvg, byKey, discNames };
    return nameDataCache;
  }

  function guideRevision(db) {
    try {
      if (!db) return null;
      const r = typeof db.revision === 'function' ? db.revision() : db.revision;
      return r == null ? null : String(r);
    } catch { return null; }
  }

  /**
   * events: SportsEvent[]; channels: [{id,name,tvgId,logo,group,url}]; settings: feature settings.
   * opts: { shiftMs, readGuide, ahead (ms), back (ms) }  -> Map<eventId, ChannelMatch[]>
   * ChannelMatch: { channel, score, how, program: {start,stop,title}|null, quality, sure }
   */
  async function match({ events, channels, settings = {}, shiftMs = 0, readGuide = true, ahead = AHEAD_MS, back = 8 * HOUR }) {
    const tick = makeYielder();
    const t0 = now();
    const nd = await nameData(channels, tick);
    const { names, tokenIndex, byTvg, byKey, discNames } = nd;
    const rank = preferenceRank(settings);
    const bestFirst = settings.bestChannelFirst !== false;
    const blocked = new Set(settings.blocked || []);
    const discIds = new Set(events.filter(isDiscGolf).map((e) => e.id));
    const found = new Map(); // eventId -> Map(key -> {key, score, how, program})
    const add = (eventId, key, score, how, program) => {
      if (!byKey.has(key) || score <= 0) return;
      let m = found.get(eventId);
      if (!m) found.set(eventId, (m = new Map()));
      const cur = m.get(key);
      if (!cur || score > cur.score) m.set(key, { key, score, how, program: program || (cur && cur.program) || null });
      else if (!cur.program && program) cur.program = program;
    };

    // Events that run for days have no single start time: while they are on, treat them as starting now.
    const multiDay = (ev) => !ev.hasTime && ev.endMs != null;
    const raceWeekend = (ev) => { const l = C.league(ev.leagueId); return ev.source === 'espn' && !!l && l.kind === 'EVENT' && l.sport === 'RACING'; };
    const noStart = new Set();
    const live = [];
    for (const ev of events) {
      let e = null;
      if (ev.hasTime) e = ev;
      else if (!multiDay(ev)) e = null;
      else if (ev.endMs + 24 * HOUR < t0) e = null;
      else e = ev.startMs >= t0 ? ev : { ...ev, startMs: t0 };
      if (!e) continue;
      if (!ev.hasTime || raceWeekend(ev)) noStart.add(ev.id);
      live.push(e);
    }
    const wanted = live.filter((ev) => ev.state !== 'off' && ev.startMs >= t0 - back && ev.startMs <= t0 + ahead);
    const picked = live.filter((ev) => ev.state !== 'off' && ev.startMs >= t0 - back && (
      (settings.eventChannels && settings.eventChannels[ev.id]) || (settings.leagueChannels && settings.leagueChannels[ev.leagueId])
      || ((ev.home && ev.away) && [ev.home, ev.away].some((s) => settings.teamChannels && settings.teamChannels[C.teamFamily(ev.leagueId) + '|' + s.team.id]))));
    const byId = new Map([...wanted, ...picked].map((e) => [e.id, e]));

    const teamEvents = wanted;
    const terms = new Map(teamEvents.map((ev) => [ev.id, termsFor(ev)]));

    // Tournaments running at the same time: a show that just says "Tennis" can't tell them apart (see Kotlin comments).
    const multiEvents = teamEvents.filter((e) => noStart.has(e.id));
    const specific = new Map();
    for (const ev of multiEvents) {
      const league = new Set(terms.get(ev.id).leagueWords.flatMap((w) => w.split(' ')));
      specific.set(ev.id, new Set(norm(ev.title).trim().split(' ').filter((w) => w.length >= 4 && /[a-z]/.test(w) && !GENERIC_TOURNAMENT_WORDS.has(w) && !league.has(w))));
    }
    const leagueWordUse = new Map();
    for (const ev of multiEvents) for (const w of new Set(terms.get(ev.id).leagueWords)) leagueWordUse.set(w, (leagueWordUse.get(w) || 0) + 1);
    const specificHits = (id, text) => [...(specific.get(id) || [])].filter((w) => hasWord(text, w)).length;
    const ownLeague = new Map();
    for (const ev of multiEvents) {
      ownLeague.set(ev.id, new Set(terms.get(ev.id).leagueWords.filter((w) => !multiEvents.some((o) => o.leagueId !== ev.leagueId && terms.get(o.id).leagueWords.includes(w)))));
    }
    const titleWordUse = new Map();
    for (const ev of multiEvents) for (const w of new Set(norm(ev.title).trim().split(' '))) titleWordUse.set(w, (titleWordUse.get(w) || 0) + 1);
    const rare = new Map();
    for (const ev of multiEvents) rare.set(ev.id, new Set(norm(ev.title).trim().split(' ').filter((w) => RARE_TOURNAMENT_WORDS.has(w) && titleWordUse.get(w) === 1)));
    const rareHit = (id, text) => [...(rare.get(id) || [])].some((w) => hasWord(text, w)) && [...(ownLeague.get(id) || [])].some((w) => hasWord(text, w));
    const distinctLeague = (id, text) => terms.get(id).leagueWords.some((w) => w !== 'grand prix' && (leagueWordUse.get(w) || 0) <= 1 && hasWord(text, w));
    const namesOther = (id, text) => {
      const mine = new Set(terms.get(id).leagueWords);
      return multiEvents.some((o) => o.id !== id && terms.get(o.id).leagueWords.some((w) => mine.has(w)) && specificHits(o.id, text) > 0);
    };

    // Games whose channel the user already linked skip the (slow) guide search; names and networks are still checked.
    const linked = new Set(teamEvents.filter((ev) => {
      const inList = (k) => k != null && byKey.has(k);
      const ec = settings.eventChannels && settings.eventChannels[ev.id];
      return inList(ec && ec.slice(0, ec.lastIndexOf('|') < 0 ? undefined : ec.lastIndexOf('|')))
        || inList(settings.leagueChannels && settings.leagueChannels[ev.leagueId])
        || (ev.home && ev.away && [ev.home, ev.away].some((s) => inList(settings.teamChannels && settings.teamChannels[C.teamFamily(ev.leagueId) + '|' + s.team.id])))
        || (settings.networkChannels && ev.broadcasts.some((b) => inList(settings.networkChannels[N.networkKey(b)])));
    }).map((e) => e.id));

    // ---- 1. The guide, a half-hour slot at a time.
    const db = readGuide ? safeGuide(getGuide) : null;
    const rev = guideRevision(db);
    const cacheKey = db ? `${rev == null ? 't' + Math.floor(now() / (2 * MINUTE)) : rev}|${nd.fingerprint}|${shiftMs}` : null;
    if (cacheKey !== slotCacheKey) { slotCache = new Map(); slotCacheKey = cacheKey; }
    if (db && typeof db.findByWords === 'function') {
      const slotsByKey = new Map();
      for (const ev of teamEvents) {
        if (linked.has(ev.id)) continue;
        const k = Math.floor(ev.startMs / (30 * MINUTE));
        (slotsByKey.get(k) || slotsByKey.set(k, []).get(k)).push(ev);
      }
      for (const slot of slotsByKey.values()) {
        await tick();
        const slotStart = Math.min(...slot.map((e) => e.startMs));
        const slotEnd = Math.max(...slot.map((e) => e.startMs));
        const words = [...new Set(slot.flatMap((ev) => terms.get(ev.id).sqlWords).map((w) => w.trim()).filter((w) => w.length >= 3))];
        if (!words.length) continue;
        const fromMs = slotStart - 100 * MINUTE - shiftMs;
        const toMs = slotEnd + 70 * MINUTE - shiftMs;
        const minStop = slotStart + 10 * MINUTE - shiftMs;
        const cacheable = rev != null && slot.every((e) => !noStart.has(e.id));
        const sig = cacheable ? slot.map((e) => `${e.id}|${e.startMs}|${terms.get(e.id).sqlWords.join(',')}`).sort().join(';') + `|${discIds.size}` : null;
        const kept = sig && slotCache.get(sig);
        if (kept) { for (const f of kept) add(f.eventId, f.key, f.score, f.how, f.program); continue; }
        let hits = null;
        try { hits = await db.findByWords(words, fromMs, toMs); } catch { hits = null; }
        if (!Array.isArray(hits)) continue; // guide not readable: nothing found now does not mean nothing is there (not cached)
        const finds = [];
        const prepared = [];
        for (const h of hits) {
          if (h.start < fromMs || h.start >= toMs || h.stop <= minStop) continue;
          const chs = byTvg.get(String(h.tvgId));
          if (!chs) continue;
          const p = shiftMs ? { ...h, start: h.start + shiftMs, stop: h.stop + shiftMs } : h;
          const title = String(h.title || '');
          const desc = String(h.desc || '');
          const sub = desc.includes('\n') ? (() => { const f = desc.split('\n')[0]; return f.length <= 90 ? f : null; })() : null;
          const ntx = norm(title + (sub ? ' ' + sub : ''));
          if (C.NOT_LIVE_WORDS.some((w) => hasWord(ntx, w))) continue;
          prepared.push({ chs, p, nt: ntx, nd: desc ? norm(desc) : '' });
        }
        let cmp = 0;
        for (const ev of slot) {
          const t = terms.get(ev.id);
          for (const { chs, p, nt, nd: ndesc } of prepared) {
            if (++cmp % 256 === 0) await tick();
            if (!discIds.has(ev.id) && (hasWord(nt, 'disc golf') || hasWord(ndesc, 'disc golf'))) continue;
            if (p.start > ev.startMs + 60 * MINUTE || p.stop < ev.startMs + 15 * MINUTE) continue;
            let score = t.scoreText(nt, ndesc);
            if (noStart.has(ev.id)) {
              const hitsN = specificHits(ev.id, nt) + specificHits(ev.id, ndesc);
              if (!t.namesLeague(nt, ndesc) && hitsN < 2) continue;
              if (score > 0) { /* keep */ }
              else if (hitsN >= 1) score = 88;
              else if (namesOther(ev.id, nt) || namesOther(ev.id, ndesc)) continue;
              else if (rareHit(ev.id, nt)) score = 75;
              else if (distinctLeague(ev.id, nt) || distinctLeague(ev.id, ndesc)) score = 70;
              else continue;
            }
            if (score <= 0) continue;
            if (p.start < ev.startMs - 80 * MINUTE || p.start > ev.startMs + 25 * MINUTE) score -= 10;
            const prog = { start: p.start, stop: p.stop, title: p.title, desc: p.desc || '' };
            for (const ch of chs) {
              if (fillerOrLoop(ch, p)) continue;
              add(ev.id, chKey(ch), score, 'Guide: ' + p.title, prog);
              if (score > 0) finds.push({ eventId: ev.id, key: chKey(ch), score, how: 'Guide: ' + p.title, program: prog });
            }
          }
        }
        if (sig) slotCache.set(sig, finds);
      }
    }

    // ---- 2. The channel's own name. Only channels whose name has one of the game's words are looked at.
    for (const ev of teamEvents) {
      await tick();
      if (ev.state === 'post' && ev.startMs < t0 - 5 * HOUR) continue;
      const t = terms.get(ev.id);
      const discEvent = discIds.has(ev.id);
      forNamed(tokenIndex, t.anchorTokens(), names, (ch, nn) => {
        if (!discEvent && discNames.has(chKey(ch))) return;
        if (t.inName(nn)) add(ev.id, chKey(ch), 95, 'Channel name', null);
        else if (noStart.has(ev.id)) {
          const hits = specificHits(ev.id, nn);
          if ((hits >= 1 && t.leagueInName(nn)) || hits >= 2) add(ev.id, chKey(ch), 90, 'Channel name', null);
          else if (!namesOther(ev.id, nn) && rareHit(ev.id, nn)) add(ev.id, chKey(ch), 85, 'Channel name', null);
          else if (!namesOther(ev.id, nn) && distinctLeague(ev.id, nn)) add(ev.id, chKey(ch), 78, 'Channel name', null);
        }
      });
    }

    // ---- 3. The network ESPN lists, with a sports show on at the time.
    const networkEvents = teamEvents.filter((ev) => ev.broadcasts.length && ev.startMs > t0 - 5 * HOUR);
    const candidates = new Map();
    for (const n of new Set(networkEvents.flatMap((e) => e.broadcasts).map(N.networkKey))) {
      await tick();
      const mapped = N.NETWORKS[n];
      const words = !mapped ? n.split(' ').filter((w) => w.length >= 3 && !N.GENERIC_NETWORK_WORDS.has(w)) : [];
      const pats = mapped || (words.length ? words : [n].filter((w) => w.length >= 3));
      if (!pats.length) continue;
      const not = N.NETWORK_NOT[n] || [];
      const anchors = new Set(pats.map((p) => p.trim().split(' ')[0]).filter(Boolean));
      const list = [];
      forNamed(tokenIndex, anchors, names, (ch, nn) => {
        const ok = mapped ? pats.some((p) => hasWord(nn, p)) : pats.every((p) => hasWord(nn, p));
        if (ok && !not.some((w) => hasWord(nn, w))) list.push(ch);
      });
      candidates.set(n, list);
    }
    const slots3 = new Map();
    for (const ev of networkEvents) { const k = Math.floor(ev.startMs / (30 * MINUTE)); (slots3.get(k) || slots3.set(k, []).get(k)).push(ev); }
    for (const slot of slots3.values()) {
      await tick();
      const at = Math.min(...slot.map((e) => e.startMs)) + 20 * MINUTE;
      for (const ev of slot) for (const b of ev.broadcasts) {
        const key = settings.networkChannels && settings.networkChannels[N.networkKey(b)];
        if (key) add(ev.id, key, 75, `Your channel for ${b}`, null);
      }
      const keys = [...new Set(slot.flatMap((ev) => ev.broadcasts.flatMap((b) => candidates.get(N.networkKey(b)) || [])).map(chKey))];
      if (!keys.length) continue;
      const sportyAll = new Set();
      for (const ev of slot) { const lg = C.league(ev.leagueId); if (lg) for (const w of [...lg.guideWords, ...SPORT_WORDS]) sportyAll.add(norm(w).trim()); }
      const onNow = await programsOn(db, byKey, keys, at - shiftMs, [...sportyAll], shiftMs);
      for (const ev of slot) {
        const lg = C.league(ev.leagueId);
        if (!lg) continue;
        const sporty = [...lg.guideWords, ...SPORT_WORDS].map((w) => norm(w).trim());
        for (const b of ev.broadcasts) {
          const n = N.networkKey(b);
          const key = settings.networkChannels && settings.networkChannels[n];
          if (key) add(ev.id, key, 75, `Your channel for ${b}`, onNow.get(key) || null);
          if (noStart.has(ev.id)) continue;
          const national = N.NATIONAL.has(n);
          const scored = [];
          for (const ch of candidates.get(n) || []) {
            const p = onNow.get(chKey(ch)) || null;
            const showText = p ? norm(p.title + ' ' + (p.episode || '')) : null;
            let score = 0;
            if (!discIds.has(ev.id) && showText && hasWord(showText, 'disc golf')) score = 0;
            else if (!p) score = onNow.unknownIsAbsent !== true && keys.length < 400 && national ? 45 : 0;
            else if (terms.get(ev.id).otherGame(showText)) score = 0;
            else if (sporty.some((w) => hasWord(norm(p.title), w)) && !C.NOT_LIVE_WORDS.some((w) => hasWord(norm(p.title), w))) score = national ? 62 : 52;
            if (score > 0) scored.push({ ch, score, p });
          }
          scored.sort((x, y) => rank(y.ch) - rank(x.ch) || qualityRank(nameQuality(y.ch.name)) - qualityRank(nameQuality(x.ch.name)));
          for (const { ch, score, p } of scored.slice(0, national ? 8 : 12)) add(ev.id, chKey(ch), score, `${b} listed by ESPN`, p);
        }
      }
    }

    // ---- 4. The user's own fixes: this game, then every game of a team / the league.
    for (const ev of byId.values()) {
      await tick();
      const ec = settings.eventChannels && settings.eventChannels[ev.id];
      if (ec) add(ev.id, ec.includes('|') ? ec.slice(0, ec.lastIndexOf('|')) : ec, 200, 'Your pick', null);
      const lc = settings.leagueChannels && settings.leagueChannels[ev.leagueId];
      if (lc) add(ev.id, lc, 140, `Your channel for ${(C.league(ev.leagueId) || {}).name || 'this competition'}`, null);
      if (settings.teamChannels) for (const side of [ev.away, ev.home]) {
        if (!side) continue;
        const k = settings.teamChannels[C.teamFamily(ev.leagueId) + '|' + side.team.id];
        if (k) add(ev.id, k, 150, `Your channel for ${side.team.name}`, null);
      }
    }

    const out = new Map();
    for (const [eventId, m] of found) {
      const list = [...m.values()].filter((f) => !blocked.has(`${eventId}|${f.key}`)).map((f) => {
        const ch = byKey.get(f.key);
        return { channel: ch, score: f.score, how: f.how, program: f.program, quality: nameQuality(ch.name), sure: f.score >= 80 };
      });
      if (list.length) out.set(eventId, sortMatches(list, bestFirst, rank).slice(0, 40));
    }
    return out;
  }

  return { match, _nameData: nameData };
}

// ------------------------------------------------------------------------------------------------ small helpers
function hashStr(s) { let h = 0; s = String(s || ''); for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0; return h; }
function safeGuide(get) { try { return get(); } catch { return null; } }
function isDiscGolf(ev) { return /\bdisc\s*golf\b/i.test(ev.title || '') || /\bdisc\s*golf\b/i.test(ev.subtitle || ''); }

/** Call `fn(channel, normalisedName)` for every channel whose name has one of `anchors` (via the token index). */
function forNamed(tokenIndex, anchors, names, fn) {
  const seen = new Set();
  for (const a of anchors) {
    const idxs = tokenIndex.get(a);
    if (!idxs) continue;
    for (const i of idxs) { if (seen.has(i)) continue; seen.add(i); fn(names[i][0], names[i][1]); }
  }
}

/**
 * What is on each channel at `atMs` (Map key -> {start,stop,title}). Uses the guide's range() when it has one (every show);
 * otherwise findByWords with sports words only, in which case a channel with a guide id but no sports show found is unknown
 * and map.unknownIsAbsent is set (the "listed by ESPN, nothing known" score of 45 is then not given to channels that do have a guide).
 */
async function programsOn(db, byKey, keys, atMs, sportyWords, shiftMs) {
  const out = new Map();
  if (!db) { out.unknownIsAbsent = false; return out; }
  try {
    if (typeof db.range === 'function') {
      const tvgOf = new Map();
      for (const k of keys) { const ch = byKey.get(k); if (ch && ch.tvgId) (tvgOf.get(String(ch.tvgId)) || tvgOf.set(String(ch.tvgId), []).get(String(ch.tvgId))).push(k); }
      const r = await db.range([...tvgOf.keys()], atMs, atMs + 1);
      const rows = r instanceof Map ? Object.fromEntries(r) : r || {};
      for (const [tvg, list] of Object.entries(rows)) {
        const p = Array.isArray(list) ? list[0] : null;
        if (!p) continue;
        for (const k of tvgOf.get(tvg) || []) out.set(k, { start: p.start + shiftMs, stop: p.stop + shiftMs, title: p.title || '', episode: p.episode || '' });
      }
      out.unknownIsAbsent = false;
      return out;
    }
    if (typeof db.findByWords === 'function') {
      const hits = await db.findByWords(sportyWords.filter((w) => w.length >= 2), atMs, atMs + 1);
      const byTvg = new Map();
      for (const h of hits || []) if (h.start <= atMs && h.stop > atMs && !byTvg.has(String(h.tvgId))) byTvg.set(String(h.tvgId), h);
      for (const k of keys) {
        const ch = byKey.get(k);
        const h = ch && ch.tvgId ? byTvg.get(String(ch.tvgId)) : null;
        if (h) out.set(k, { start: h.start + shiftMs, stop: h.stop + shiftMs, title: h.title || '', episode: '' });
        else if (ch && ch.tvgId) out.set(k, { start: atMs, stop: atMs, title: '', episode: '', none: true });
      }
      out.unknownIsAbsent = false;
    }
  } catch { /* guide unreadable: names alone */ }
  return out;
}

module.exports = {
  createMatcher, termsFor, makeTerms, isBanner, cleanTitle, nameQuality, qualityRank, copyKey, groupCopies, preferenceRank, sortMatches,
  channelRegionCode, fillerOrLoop, chKey, AHEAD_MS, SPORT_NAMES,
};
