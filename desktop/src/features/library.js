'use strict';
// Playlist + XMLTV loading for the Live TV tab. Shared state: ctx.state.channels.
const { loadPlaylist } = require('../sources');

function register(ctx) {
  ctx.handle('library:load', async () => {
    const s = ctx.settings.get();
    const result = { ok: true, errors: [], groups: [], channels: [], guideChannels: 0 };
    try {
      const pl = await loadPlaylist(s.playlist);
      ctx.state.channels = pl.channels;
      result.channels = pl.channels;
      result.groups = pl.groups;
    } catch (e) {
      result.ok = false;
      result.errors.push(`Playlist: ${e.message}`);
      ctx.state.channels = [];
    }
    return result;
  });
}

module.exports = { register };
