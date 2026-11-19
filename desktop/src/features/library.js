'use strict';
// Channel loading for the Live TV tab: M3U playlist and/or Xtream Codes account.
// Shared state: ctx.state.channels.
const { loadPlaylist } = require('../sources');
const { channelsFromSettings, xmltvUrl } = require('../xtream');

function groupsOf(channels) {
  const counts = new Map();
  for (const c of channels) counts.set(c.group, (counts.get(c.group) || 0) + 1);
  return [...counts.entries()].map(([name, count]) => ({ name, count }));
}

function register(ctx) {
  ctx.handle('library:load', async () => {
    const s = ctx.settings.get();
    const result = { ok: true, errors: [], groups: [], channels: [], guideChannels: 0 };
    let channels = [];
    if (s.playlist) {
      try { channels = channels.concat((await loadPlaylist(s.playlist)).channels); }
      catch (e) { result.errors.push(`Playlist: ${e.message}`); }
    }
    if (s.xtream) {
      try { channels = channels.concat(await channelsFromSettings(s.xtream)); }
      catch (e) { result.errors.push(`Xtream: ${e.message}`); }
      // No guide of its own: use the account's XMLTV.
      if (!s.guide) {
        const url = xmltvUrl(s.xtream);
        if (url) ctx.settings.save({ guide: { kind: 'url', value: url } });
      }
    }
    // Ids are positional, so make them unique across sources.
    channels = channels.map((c, i) => ({ ...c, id: String(i) }));
    result.ok = channels.length > 0;
    if (!channels.length && !result.errors.length) result.errors.push('No playlist or Xtream login configured.');
    ctx.state.channels = channels;
    result.channels = channels;
    result.groups = groupsOf(channels);
    return result;
  });
}

module.exports = { register };
