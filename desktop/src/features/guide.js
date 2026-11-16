'use strict';
// Guide feature. INTERIM in-memory implementation of the contract in docs/CONTRACTS.md so the app
// works end to end; the guide work stream replaces it with the SQLite store (src/guidedb.js).
const { loadGuide } = require('../sources');
const { nowNext } = require('../xmltv');

function register(ctx) {
  let guide = null;
  ctx.handle('guide:load', async () => {
    guide = null;
    const g = await loadGuide(ctx.settings.get().guide);
    guide = g;
    return { channels: g ? g.programmes.size : 0, programmes: g ? g.count : 0 };
  });
  ctx.handle('guide:nowNext', (ids) => {
    const out = {};
    if (!guide || !Array.isArray(ids)) return out;
    const at = Date.now();
    for (const id of ids.slice(0, 500)) {
      const r = nowNext(guide.programmes.get(String(id)), at);
      if (r.now || r.next) out[id] = r;
    }
    return out;
  });
  ctx.handle('guide:schedule', (id, fromMs, toMs) => {
    const list = (guide && guide.programmes.get(String(id))) || [];
    return list.filter((p) => (fromMs == null || p.stop > fromMs) && (toMs == null || p.start < toMs));
  });
}
module.exports = { register };
