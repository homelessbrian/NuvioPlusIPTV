'use strict';
// Network name tables from SportsMatcher.kt (how ESPN's broadcast names appear in channel names) and the
// league networks of SportsLeagueNetworks.kt (NFL Network, NBA TV, Golf Channel ...).
const { norm, hasWord } = require('./catalog');

/** Networks that show the same thing everywhere (no local stations). */
const NATIONAL = new Set([
  'espn', 'espn2', 'espnu', 'espnews', 'espn plus', 'espn deportes', 'fs1', 'fs2', 'nfl net', 'nfl network', 'nfln',
  'nba tv', 'nhl net', 'nhl network', 'mlb net', 'mlb network', 'tnt', 'tbs', 'trutv', 'usa net', 'usa network',
  'cbssn', 'cbs sports network', 'btn', 'big ten network', 'secn', 'sec network', 'accn', 'acc network',
  'golf', 'golf channel', 'tennis channel', 'fox deportes', 'tudn', 'univision', 'telemundo', 'universo', 'peacock',
  'prime video', 'paramount plus', 'apple tv', 'netflix', 'bein sports', 'nbcsn', 'mlb tv', 'max', 'truetv',
  'sky sports', 'sky sports main event', 'sky sports football', 'sky sports premier league', 'sky sports arena',
  'tnt sports', 'dazn', 'itv', 'itv1', 'itv4', 'bbc', 'bbc one', 'bbc two', 'bbc iplayer',
  'tsn', 'sportsnet', 'sportsnet one', 'ctv', 'cbc',
  'fox sports australia', 'kayo', 'stan sport', 'seven', 'nine', 'optus sport',
]);

const GENERIC_NETWORK_WORDS = new Set(['sports', 'network', 'tv', 'channel', 'the', 'regional', 'home', 'plus']);

/** How each network appears in channel names. */
const NETWORKS = {
  fox: ['fox'], cbs: ['cbs'], nbc: ['nbc'], abc: ['abc'], espn: ['espn'], espn2: ['espn2', 'espn 2'], espnu: ['espnu', 'espn u'],
  espnews: ['espnews', 'espn news'], 'espn plus': ['espn plus'], 'espn deportes': ['espn deportes'],
  fs1: ['fs1', 'fox sports 1'], fs2: ['fs2', 'fox sports 2'],
  'nfl net': ['nfl network', 'nfl net', 'nfln'], 'nfl network': ['nfl network', 'nfl net', 'nfln'], nfln: ['nfl network', 'nfl net', 'nfln'],
  'nba tv': ['nba tv', 'nbatv'], 'nhl net': ['nhl network', 'nhl net'], 'nhl network': ['nhl network', 'nhl net'],
  'mlb net': ['mlb network', 'mlb net'], 'mlb network': ['mlb network', 'mlb net'],
  tnt: ['tnt'], tbs: ['tbs'], trutv: ['trutv', 'tru tv'], 'usa net': ['usa network', 'usa net'], 'usa network': ['usa network', 'usa net'],
  cbssn: ['cbs sports network', 'cbssn', 'cbs sports net'], 'cbs sports network': ['cbs sports network', 'cbssn', 'cbs sports net'],
  btn: ['big ten network', 'btn', 'big ten'], 'big ten network': ['big ten network', 'btn', 'big ten'],
  secn: ['sec network', 'secn'], 'sec network': ['sec network', 'secn'], accn: ['acc network', 'accn'], 'acc network': ['acc network', 'accn'],
  golf: ['golf channel', 'golf'], 'golf channel': ['golf channel', 'golf'], 'tennis channel': ['tennis channel'],
  'fox deportes': ['fox deportes'], tudn: ['tudn'], univision: ['univision'], telemundo: ['telemundo'], universo: ['universo'],
  peacock: ['peacock'], 'prime video': ['prime video', 'amazon prime'], 'paramount plus': ['paramount plus', 'paramount'],
  'apple tv': ['apple tv', 'mls season pass'], netflix: ['netflix'], 'bein sports': ['bein sports', 'bein'], max: ['max', 'hbo max'],
  'sky sports': ['sky sports'], 'sky sports main event': ['sky sports main event', 'sky sports 1'], 'sky sports football': ['sky sports football'],
  'sky sports premier league': ['sky sports premier league', 'sky sports prem'], 'sky sports arena': ['sky sports arena'],
  'sky sports racing': ['sky sports racing'], 'sky sports f1': ['sky sports f1', 'sky sports formula 1'], 'sky sports cricket': ['sky sports cricket'],
  'sky sports golf': ['sky sports golf'], 'sky sports tennis': ['sky sports tennis'],
  'tnt sports': ['tnt sports'], 'tnt sports 1': ['tnt sports 1'], 'tnt sports 2': ['tnt sports 2'], dazn: ['dazn'],
  itv: ['itv1', 'itv'], itv4: ['itv4'], bbc: ['bbc one', 'bbc two', 'bbc'], 'bbc iplayer': ['bbc iplayer', 'bbc one', 'bbc two'],
  tsn: ['tsn'], sportsnet: ['sportsnet'], 'sportsnet one': ['sportsnet one'], ctv: ['ctv'], cbc: ['cbc'],
  'fox sports australia': ['fox sports', 'foxsports'], kayo: ['kayo'], 'stan sport': ['stan sport'],
  seven: ['channel 7', 'seven network', '7mate'], nine: ['channel 9', 'nine network', '9gem'], 'optus sport': ['optus sport'],
  'bein sports 1': ['bein sports 1', 'bein 1'], 'bein sports 2': ['bein sports 2', 'bein 2'], 'bein sports 3': ['bein sports 3', 'bein 3'],
};

/** Channel-name words that rule a channel out for that network (FOX News isn't FOX). */
const NETWORK_NOT = {
  fox: ['news', 'business', 'deportes', 'sports 1', 'sports 2', 'fs1', 'fs2', 'soccer', 'weather', 'life', 'kids', 'movies', 'classics', 'comedy', 'nation', 'family', 'crime', 'reality'],
  cbs: ['news', 'sports', 'cbssn', 'golazo', 'reality', 'drama', 'action'],
  nbc: ['news', 'nbcsn', 'sports', 'universo', 'golf'],
  abc: ['news', 'family', 'kids', 'me', 'australia'],
  espn: ['2', 'u', 'news', 'deportes', 'classic', 'plus', 'espn2', 'espnu', 'espnews'],
  tnt: ['sports 1', 'sports 2', 'sports 3', 'sports 4', 'sports 5', 'drama', 'series', 'film', 'comedy'],
  tbs: ['news'], max: ['cinemax', 'maximum', 'maxx', 'movies'], golf: ['tv'], 'paramount plus': ['network', 'comedy'],
  itv: ['itv2', 'itv3', 'itv4', 'itvbe'], bbc: ['news', 'parliament', 'alba', 'four'], seven: ['news', 'mate extra'], nine: ['news', 'gem extra'],
};

/** A network as we key it ("ESPN+" -> "espn plus"). */
const networkKey = (name) => norm(String(name).replace(/\+/g, ' plus ')).trim();

// ------------------------------------------------------------------------------------------------ league networks
const LEAGUE_NETWORKS = [
  { id: 'nfl', sport: 'FOOTBALL', name: 'NFL Network', mark: 'NFL', color: 0xFF1C4F9C, words: ['nfl network'], not: ['redzone', 'red zone'] },
  { id: 'redzone', sport: 'FOOTBALL', name: 'NFL RedZone', mark: 'RZ', color: 0xFFA8322D, words: ['redzone', 'red zone'], not: [] },
  { id: 'nbatv', sport: 'BASKETBALL', name: 'NBA TV', mark: 'NBA', color: 0xFF8A3B1F, words: ['nba tv'], not: ['canada'] },
  { id: 'nbatvca', sport: 'BASKETBALL', name: 'NBA TV Canada', mark: 'NBA', color: 0xFF5A3A8F, words: ['nba tv canada'], not: [] },
  { id: 'mlb', sport: 'BASEBALL', name: 'MLB Network', mark: 'MLB', color: 0xFF1D6B5A, words: ['mlb network'], not: ['strike zone'] },
  { id: 'strikezone', sport: 'BASEBALL', name: 'MLB Strike Zone', mark: 'SZ', color: 0xFF4B5870, words: ['strike zone'], not: [] },
  { id: 'nhl', sport: 'HOCKEY', name: 'NHL Network', mark: 'NHL', color: 0xFF2F5D8A, words: ['nhl network'], not: [] },
  { id: 'golf', sport: 'GOLF', name: 'Golf Channel', mark: 'GC', color: 0xFF2E7D4F, words: ['golf channel'], not: [] },
  { id: 'tennis', sport: 'TENNIS', name: 'Tennis Channel', mark: 'TC', color: 0xFF7A8A22, words: ['tennis channel'], not: [] },
  { id: 'laliga', sport: 'SOCCER', name: 'LaLiga TV', mark: 'LL', color: 0xFFB5372E, words: ['laliga tv', 'la liga tv'], not: [] },
  { id: 'ligue1', sport: 'SOCCER', name: 'Ligue 1+', mark: 'L1', color: 0xFF2A3F8F, words: ['ligue 1 plus'], not: [] },
];
const NOT_THE_CHANNEL = ['replay', 'replays', '24 7', 'classic', 'vault', 'encore', 'highlights'];

/** Which of `channels` are each league network, by channel name only. `isBanner` skips separator rows. */
function findLeagueNetworks(channels, isBanner) {
  const out = {};
  for (const ch of channels) {
    if (isBanner(ch.name)) continue;
    const n = norm(String(ch.name).replace(/\+/g, ' plus '));
    for (const net of LEAGUE_NETWORKS) {
      if (!net.words.some((w) => hasWord(n, w))) continue;
      if (net.not.some((w) => hasWord(n, w)) || NOT_THE_CHANNEL.some((w) => hasWord(n, w))) continue;
      (out[net.id] || (out[net.id] = [])).push(ch);
    }
  }
  return out;
}

module.exports = { NATIONAL, GENERIC_NETWORK_WORDS, NETWORKS, NETWORK_NOT, networkKey, LEAGUE_NETWORKS, findLeagueNetworks };
