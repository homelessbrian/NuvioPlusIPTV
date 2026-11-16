'use strict';
// Port of SportsCatalog.kt: the leagues the Sports tab knows, text normalisation (norm / hasWord), team names.
// League fields: id, sport (key of SPORTS), name, short, espnPath (null = guide only), kind ('TEAMS'|'EVENT'),
// guideWords (how guides name it), regions (where it is on by default), minutes (usual length), color (0xAARRGGBB number),
// plus optional schoolNames, espnQuery, guideOnly, fixtureSlug, liveByDefault, national, women.
const LEAGUE_DATA = require('./catalog-data');

const SPORTS = {
  AMERICAN_FOOTBALL: { label: 'American Football', icon: '\u{1F3C8}', homeFirst: false },
  BASKETBALL: { label: 'Basketball', icon: '\u{1F3C0}', homeFirst: false },
  HOCKEY: { label: 'Hockey', icon: '\u{1F3D2}', homeFirst: false },
  BASEBALL: { label: 'Baseball', icon: '\u26BE', homeFirst: false },
  FOOTBALL: { label: 'Football', icon: '\u26BD', homeFirst: true },
  RUGBY: { label: 'Rugby', icon: '\u{1F3C9}', homeFirst: true },
  FIGHTING: { label: 'Fighting', icon: '\u{1F94A}', homeFirst: false },
  RACING: { label: 'Racing', icon: '\u{1F3C1}', homeFirst: false },
  GOLF: { label: 'Golf', icon: '\u26F3', homeFirst: false },
  TENNIS: { label: 'Tennis', icon: '\u{1F3BE}', homeFirst: false },
  CRICKET: { label: 'Cricket', icon: '\u{1F3CF}', homeFirst: true },
  DARTS: { label: 'Darts', icon: '\u{1F3AF}', homeFirst: false },
  SNOOKER: { label: 'Snooker', icon: '\u{1F3B1}', homeFirst: false },
  WRESTLING: { label: 'Wrestling', icon: '\u{1F4AA}', homeFirst: false },
  AUSSIE_RULES: { label: 'Aussie Rules', icon: '\u{1F3C9}', homeFirst: true },
  VOLLEYBALL: { label: 'Volleyball', icon: '\u{1F3D0}', homeFirst: false },
  LACROSSE: { label: 'Lacrosse', icon: '\u{1F94D}', homeFirst: false },
  GAELIC: { label: 'Gaelic Games', icon: '\u2618', homeFirst: false },
  NETBALL: { label: 'Netball', icon: '\u{1F93E}', homeFirst: false },
  OTHER: { label: 'More Sports', icon: '\u{1F3C5}', homeFirst: false },
};
const SPORT_KEYS = Object.keys(SPORTS);

const leagues = LEAGUE_DATA.map((l) => Object.freeze({ kind: 'TEAMS', espnPath: null, ...l, nationalGroup: l.sport + (l.women ? '|w' : '') }));
const byId = new Map(leagues.map((l) => [l.id, l]));
const league = (id) => byId.get(id) || null;

const REGIONS = [
  ['US', 'United States'], ['UK', 'United Kingdom'], ['IE', 'Ireland'], ['CA', 'Canada'],
  ['AU', 'Australia & New Zealand'], ['OTHER', 'Somewhere else'],
];

/** The machine's country as a Sports region (Kotlin deviceRegion). */
function deviceRegion(locale) {
  let c = '';
  try { c = (new Intl.Locale(locale || Intl.DateTimeFormat().resolvedOptions().locale).region || '').toUpperCase(); } catch { /* ignore */ }
  if (c === 'US' || c === 'PR') return 'US';
  if (c === 'GB' || c === 'UK') return 'UK';
  if (c === 'IE') return 'IE';
  if (c === 'CA') return 'CA';
  if (c === 'AU' || c === 'NZ') return 'AU';
  return 'OTHER';
}
const regionOf = (s) => (s && s.region) || deviceRegion();

/** In All Events: the region's leagues, with the user's own additions/removals (hiddenLeagues holds "id" or "+id"). */
function shown(l, s) {
  const hidden = new Set((s && s.hiddenLeagues) || []);
  const inRegion = l.regions.includes(regionOf(s));
  if (inRegion && hidden.has(l.id)) return false;
  if (!inRegion && hidden.has('+' + l.id)) return true;
  return inRegion;
}

function toggleShown(l, s) {
  const set = new Set((s && s.hiddenLeagues) || []);
  const inRegion = l.regions.includes(regionOf(s));
  if (shown(l, s)) { set.delete('+' + l.id); if (inRegion) set.add(l.id); } else { set.delete(l.id); if (!inRegion) set.add('+' + l.id); }
  return { ...s, hiddenLeagues: [...set] };
}

/** Leagues in the order the region cares about them. */
function ordered(s) {
  const region = regionOf(s);
  const first = {
    UK: ['FOOTBALL', 'RUGBY', 'CRICKET', 'DARTS', 'SNOOKER', 'RACING', 'FIGHTING', 'WRESTLING', 'GOLF', 'TENNIS'],
    AU: ['AUSSIE_RULES', 'RUGBY', 'CRICKET', 'FOOTBALL', 'BASKETBALL'],
    CA: ['HOCKEY', 'AMERICAN_FOOTBALL', 'BASKETBALL', 'BASEBALL'],
  }[region === 'IE' ? 'UK' : region] || ['AMERICAN_FOOTBALL', 'BASKETBALL', 'BASEBALL', 'HOCKEY', 'FOOTBALL'];
  const rank = (l) => { const i = first.indexOf(l.sport); return i < 0 ? 100 + SPORT_KEYS.indexOf(l.sport) : i; };
  return leagues.map((l, i) => [l, i]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).map((x) => x[0]);
}

// ---------------------------------------------------------------------------------------------- related leagues
const UEFA_CLUB = ['soccer/uefa.champions', 'soccer/uefa.europa', 'soccer/uefa.europa.conf'];
const EURO_MORE = new Set(['soccer/ned.1', 'soccer/por.1', 'soccer/bel.1', 'soccer/tur.1', 'soccer/gre.1', 'soccer/aut.1', 'soccer/den.1', 'soccer/swe.1', 'soccer/nor.1']);
const CONMEBOL = ['soccer/conmebol.libertadores', 'soccer/conmebol.sudamericana'];
const EPCR = ['rugby/271937', 'rugby/272073'];
const DOMESTIC_CUPS = {
  'soccer/esp.1': ['soccer/esp.copa_del_rey'], 'soccer/esp.2': ['soccer/esp.copa_del_rey'],
  'soccer/ger.1': ['soccer/ger.dfb_pokal'], 'soccer/ger.2': ['soccer/ger.dfb_pokal'],
  'soccer/ita.1': ['soccer/ita.coppa_italia'], 'soccer/ita.2': ['soccer/ita.coppa_italia'],
  'soccer/fra.1': ['soccer/fra.coupe_de_france'], 'soccer/fra.2': ['soccer/fra.coupe_de_france'],
  'soccer/ned.1': ['soccer/ned.cup'], 'soccer/por.1': ['soccer/por.taca.portugal'],
  'soccer/usa.1': ['soccer/usa.open', 'soccer/concacaf.champions', 'soccer/concacaf.leagues.cup'],
  'soccer/mex.1': ['soccer/concacaf.champions', 'soccer/concacaf.leagues.cup'],
  'soccer/usa.usl.1': ['soccer/usa.open'], 'soccer/usa.usl.l1': ['soccer/usa.open'],
  'soccer/bra.1': [...CONMEBOL, 'soccer/bra.copa_do_brazil'], 'soccer/bra.2': ['soccer/bra.copa_do_brazil'],
  'soccer/arg.1': [...CONMEBOL, 'soccer/arg.copa'], 'soccer/arg.2': ['soccer/arg.copa'],
  'soccer/col.1': [...CONMEBOL, 'soccer/col.copa'], 'soccer/chi.1': [...CONMEBOL, 'soccer/chi.copa_chi'],
  'soccer/per.1': CONMEBOL, 'soccer/par.1': [...CONMEBOL, 'soccer/par.1.supercopa'], 'soccer/uru.1': CONMEBOL,
  'soccer/ecu.1': CONMEBOL, 'soccer/bol.1': CONMEBOL, 'soccer/ven.1': CONMEBOL,
  'soccer/esp.w.1': ['soccer/esp.copa_de_la_reina', 'soccer/uefa.wchampions'],
  'soccer/jpn.1': ['soccer/afc.champions', 'soccer/afc.cup'], 'soccer/ksa.1': ['soccer/afc.champions', 'soccer/afc.cup', 'soccer/ksa.kings.cup'],
  'soccer/chn.1': ['soccer/afc.champions'], 'soccer/aus.1': ['soccer/afc.champions'], 'soccer/rsa.1': ['soccer/caf.champions'],
  'rugby/267979': EPCR, 'rugby/270557': EPCR, 'rugby/270559': EPCR,
};
const EURO_TOP = new Set(['soccer/esp.1', 'soccer/ita.1', 'soccer/ger.1', 'soccer/fra.1', 'soccer/sco.1', ...UEFA_CLUB]);

/** The other competitions a team followed in `leagueId` plays in. */
function relatedLeagues(leagueId) {
  const l = league(leagueId);
  if (!l) return [];
  let out;
  if (l.national) out = leagues.filter((x) => x.national && x.nationalGroup === l.nationalGroup).map((x) => x.id);
  else if (leagueId.startsWith('soccer/eng.w')) out = ['soccer/eng.w.fa', 'soccer/eng.w.league_cup', 'soccer/uefa.wchampions'];
  else if (['soccer/eng.2', 'soccer/eng.3', 'soccer/eng.4', 'soccer/eng.5'].includes(leagueId)) out = ['soccer/eng.fa', 'soccer/eng.league_cup', 'soccer/eng.trophy'];
  else if (leagueId.startsWith('soccer/eng.')) out = [...UEFA_CLUB, 'soccer/eng.fa', 'soccer/eng.league_cup', 'soccer/uefa.super_cup', 'soccer/eng.charity', 'soccer/fifa.cwc'];
  else if (leagueId.startsWith('soccer/sco.')) out = [...UEFA_CLUB, 'soccer/sco.tennents', 'soccer/sco.cis'];
  else out = [...(EURO_TOP.has(leagueId) || EURO_MORE.has(leagueId) ? [...UEFA_CLUB, 'soccer/uefa.super_cup', 'soccer/fifa.cwc'] : []), ...(DOMESTIC_CUPS[leagueId] || [])];
  return out.filter((x) => x !== leagueId);
}

/** Leagues whose team ids are shared (ESPN's football/rugby/lacrosse competitions); others: the league itself. */
function teamFamily(leagueId) {
  if (leagueId.startsWith('tsdb/')) return 'tsdb';
  if (leagueId.startsWith('soccer/')) return 'soccer';
  if (leagueId.startsWith('rugby/')) return 'rugby';
  if (leagueId.startsWith('lacrosse/')) return 'lacrosse';
  return leagueId;
}

// ---------------------------------------------------------------------------------------------- text
// Small raised letters some guides put in titles ("ᴸᴵⱽᴱ", "ᴺᴱᵂ", "ʟɪᴠᴇ").
const SMALL = Array.from('ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻᶦᶫᶰᶸᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ⁰¹²³⁴⁵⁶⁷⁸⁹⁺');
const PLAIN = Array.from('abdeghijklmnoprtuvwabcdefghijklmnoprstuvwxyzilnuabcdefghijklmnoprstuvwyz0123456789+');
const SMALL_MAP = new Map();
SMALL.forEach((ch, i) => { if (PLAIN[i]) SMALL_MAP.set(ch, PLAIN[i]); });
const SMALL_CLASS = [...SMALL_MAP.keys()].join('');
const SMALL_RUN = SMALL_MAP.size ? new RegExp('[' + SMALL_CLASS + ']+(?:\\s*[' + SMALL_CLASS + ']+)*', 'gu') : null;
const SMALL_MARKS = new Set(['live', 'new', 'hd', 'fhd', 'uhd', '4k']);
const plainOf = (run) => Array.from(run).map((ch) => (SMALL_MAP.has(ch) ? SMALL_MAP.get(ch) : /\s/.test(ch) ? ' ' : '')).join('');
const hasSmall = (s) => SMALL_RUN && SMALL_RUN.test(s) && ((SMALL_RUN.lastIndex = 0), true);
function plainSmall(s) {
  if (!SMALL_RUN || !hasSmall(s)) return s;
  return s.replace(SMALL_RUN, (m) => ' ' + plainOf(m) + ' ');
}
function withoutSmallMarks(s) {
  if (!SMALL_RUN || !hasSmall(s)) return s;
  return s.replace(SMALL_RUN, (m) => (plainOf(m).split(' ').filter((w) => w.trim()).every((w) => SMALL_MARKS.has(w)) ? ' ' : m)).replace(/\s+/g, ' ').trim();
}

/** Lowercase, accents/punctuation/"&" sorted out, padded with spaces so whole words can be found with hasWord. */
function norm(s) {
  s = String(s == null ? '' : s);
  // eslint-disable-next-line no-control-regex
  if (!/[^\x00-\x7f]/.test(s)) return asciiNorm(s);
  const plain = plainSmall(s).normalize('NFD').replace(/\p{M}+/gu, '');
  return ' ' + plain.toLowerCase().replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').trim().replace(/ +/g, ' ') + ' ';
}
function asciiNorm(s) {
  let out = ' ';
  let space = true;
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c >= 65 && c <= 90) c += 32;
    if ((c >= 97 && c <= 122) || (c >= 48 && c <= 57)) { out += String.fromCharCode(c); space = false; }
    else if (c === 38) { if (!space) out += ' '; out += 'and '; space = true; }
    else if (!space) { out += ' '; space = true; }
  }
  if (!space || out.length === 1) out += ' ';
  return out;
}

/** `term` (normalised, no padding) as whole words in `normText` (from norm). Same boundary rules as the Kotlin. */
function hasWord(normText, term) {
  if (!term || !term.trim()) return false;
  let at = normText.indexOf(term);
  while (at >= 0) {
    const end = at + term.length;
    if (at > 0 && end < normText.length && normText[at - 1] === ' ' && normText[end] === ' ') return true;
    const boundary = normText.indexOf(' ', at);
    if (boundary < 0) return false;
    at = normText.indexOf(term, boundary + 1);
  }
  return false;
}

/** Words that make a show a replay or highlights show rather than the game itself. */
const NOT_LIVE_WORDS = [
  'highlights', 'replay', 'classic', 'classics', 'rewind', 'recap', 'review', 'reaction',
  'condensed', 'mini match', 'extended', 'best of', 'in 60', 'in 90', 'encore', 're air', 'reair',
  'magazine', 'preview show', 'goals', 'match of the day', 'motd', 'uncut', 'repeat',
  'this week in', 'bottom line', 'untold', 'vault', 'biography',
  'hlts', 'highlight', 'rerun', 're run', 'as live',
];

const FIGHT_NOISE = new Set([
  'boxing', 'fight', 'fights', 'night', 'card', 'main', 'event', 'live', 'title', 'world', 'championship',
  'undercard', 'prelims', 'preliminary', 'presents', 'from', 'replay', 'full', 'saturday', 'friday', 'sunday',
  'season', 'riyadh', 'zuffa', 'showdown', 'heavyweight', 'middleweight', 'welterweight', 'lightweight',
  'featherweight', 'bantamweight', 'flyweight', 'cruiserweight', 'rematch', 'undisputed', 'unified', 'bout',
]);

/** A fight's two sides from "A vs B" titles: each side's name words (4+ letters, without card words) or null. */
function fightSides(title) {
  const parts = norm(title).trim().split(/ vs | v | versus /);
  if (parts.length !== 2) return null;
  const words = (p) => [...new Set(p.trim().split(' ').filter((w) => w.length >= 4 && /[a-z]/.test(w) && !FIGHT_NOISE.has(w)))];
  const a = words(parts[0]);
  const b = words(parts[1]);
  return a.length && b.length ? [a, b] : null;
}

const ALIASES = {
  'manchester united': ['man utd', 'man united', 'man u'], 'manchester city': ['man city'],
  'tottenham hotspur': ['tottenham', 'spurs'], 'wolverhampton wanderers': ['wolves', 'wolverhampton'],
  'brighton and hove albion': ['brighton'], 'west ham united': ['west ham'], 'newcastle united': ['newcastle'],
  'nottingham forest': ['nottm forest', 'nottingham f'], 'leicester city': ['leicester'], 'leeds united': ['leeds'],
  'sheffield united': ['sheff utd', 'sheffield utd'], 'sheffield wednesday': ['sheff wed', 'sheffield wed'],
  'queens park rangers': ['qpr'], 'west bromwich albion': ['west brom', 'wba'], 'afc bournemouth': ['bournemouth'],
  'ipswich town': ['ipswich'], 'luton town': ['luton'], 'aston villa': ['villa'], 'crystal palace': ['palace'],
  'paris saint germain': ['psg', 'paris sg', 'paris st germain'], internazionale: ['inter milan', 'internazionale milano'],
  'bayern munich': ['bayern', 'fc bayern', 'bayern munchen'], 'borussia dortmund': ['dortmund', 'bvb'],
  'bayer leverkusen': ['leverkusen'], 'atletico madrid': ['atletico', 'atleti'], barcelona: ['barca', 'fc barcelona'],
  celtic: ['celtic fc'], rangers: ['rangers fc'], 'inter miami cf': ['inter miami'], 'la galaxy': ['galaxy'],
  'los angeles fc': ['lafc'], 'new york red bulls': ['red bulls', 'ny red bulls'], 'new york city fc': ['nycfc', 'nyc fc'],
};
const CLUB_BITS = /^(fc|afc|cf|sc|ac|ss|ssc|as|rc|cd|ca|sv|vfb|vfl|tsg|1 fc) | (fc|afc|cf|sc)$/;

/** How a guide might name `team`: strong names (enough with the other team) and weak ones (a city). */
function teamNames(team, lg) {
  const strong = new Set();
  const weak = new Set();
  const n = (s) => { if (s == null) return null; const t = norm(s).trim(); return t.length >= 3 ? t : null; };
  const full = n(team.fullName);
  if (full) { strong.add(full); strong.add(full.replace(CLUB_BITS, '').trim()); }
  const nick = n(team.name);
  const loc = n(team.location);
  if (lg && lg.schoolNames) {
    if (loc) strong.add(loc);
    if (nick && (loc == null || nick !== loc)) weak.add(nick);
  } else if (lg && lg.sport === 'FOOTBALL') {
    if (nick) { strong.add(nick); strong.add(nick.replace(CLUB_BITS, '').trim()); }
    if (loc && loc !== nick) weak.add(loc);
  } else {
    if (nick && nick !== loc) strong.add(nick);
    if (loc) weak.add(loc);
  }
  for (const k of [full, nick]) if (k && ALIASES[k]) for (const a of ALIASES[k]) strong.add(a);
  for (const s of [...strong]) if (s.length < 3) strong.delete(s);
  for (const s of strong) weak.delete(s);
  return [[...strong], [...weak]];
}

/** A show's name without a trailing air date ("NXT - October 20th 2026" -> "NXT"). */
function withoutAirDate(title) {
  const month = '(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?';
  const day = '\\d{1,2}(?:st|nd|rd|th)?';
  const date = [`${month}\\s+${day},?\\s+\\d{4}`, `${day}\\s+${month},?\\s+\\d{4}`, '\\d{4}-\\d{2}-\\d{2}', '\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}'].join('|');
  const trailing = new RegExp(`\\s*(?:[\\-\u2013\u2014:|,]\\s*)?\\(?(?:${date})\\)?\\s*$`, 'i');
  const out = title.replace(trailing, '').trim().replace(/[-\u2013\u2014:|,\s]+$/, '').trim();
  return out || title;
}

/** A wrestling show's headline without promotion prefix and episode number. */
function wrestlingHeadline(title) {
  const t = withoutAirDate(title).replace(/^\s*(wwe|aew|tna|njpw)\s*[:\-\u2013]?\s*/i, '').trim();
  const m = /^(.*?)\s*#\s*\d+\s*(.*)$/.exec(t);
  const out = m ? (m[2].trim() || m[1].trim()) : t;
  return out || title;
}

module.exports = {
  SPORTS, SPORT_KEYS, leagues, league, REGIONS, deviceRegion, regionOf, shown, toggleShown, ordered,
  relatedLeagues, teamFamily, norm, hasWord, plainSmall, withoutSmallMarks, NOT_LIVE_WORDS, fightSides,
  teamNames, withoutAirDate, wrestlingHeadline,
};
