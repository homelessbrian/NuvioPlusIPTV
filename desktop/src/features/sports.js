'use strict';
// Feature module "sports": IPC handlers over src/sports/service.js. Owned by the sports work stream.
const { createService } = require('../sports/service');

function register(ctx) {
  let guideDb = null;
  const getGuide = () => {
    if (guideDb) return guideDb;
    try { guideDb = require('../guidedb').getDb() || null; } catch { guideDb = null; } // guide stream may not have landed / DB not open yet
    return guideDb;
  };
  const svc = createService({
    getSettings: () => ctx.settings.get(),
    saveSettings: (p) => ctx.settings.save(p),
    getChannels: () => ctx.state.channels || [],
    getGuide: () => { const g = getGuide(); if (!g) guideDb = null; return g; },
    send: (ch, payload) => ctx.send(ch, payload),
    userData: ctx.userData,
    log: (m) => { try { console.warn('[sports]', m); } catch { /* ignore */ } },
  });
  svc.start();

  ctx.handle('sports:leagues', () => svc.leagues());
  ctx.handle('sports:toggleLeague', (id) => svc.toggleLeague(String(id)));
  ctx.handle('sports:events', (dayOffset, opts) => svc.eventsForDay(dayOffset, opts || {}));
  ctx.handle('sports:mine', () => svc.myEvents());
  ctx.handle('sports:follows', () => svc.getFollows());
  ctx.handle('sports:follow', (item) => svc.follow(item));
  ctx.handle('sports:unfollow', (item) => svc.unfollow(item));
  ctx.handle('sports:channels', (eventId) => svc.channelsFor(String(eventId)));
  ctx.handle('sports:networks', () => svc.leagueNetworks());
  ctx.handle('sports:standings', (leagueId) => svc.standings(String(leagueId)));
  ctx.handle('sports:settings', () => svc.settings());
  ctx.handle('sports:saveSettings', (patch) => svc.saveSettings(patch));
  ctx.handle('sports:visible', (v) => svc.setVisible(v));
  ctx.handle('sports:refresh', async () => { await svc.feeds.ensure(svc.activeLeagueIds(), { deadlineMs: 6000, force: true }); return true; });

  if (ctx.app && ctx.app.on) ctx.app.on('before-quit', () => svc.stop());
}
module.exports = { register };
