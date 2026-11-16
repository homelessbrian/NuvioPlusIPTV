'use strict';
// Guide feature: SQLite-backed XMLTV store (src/guidedb.js). Contract in docs/CONTRACTS.md, details in docs/guide.md.
const path = require('node:path');
const { openGuideDb, STALE_MS } = require('../guidedb');
const { guideSource, sourceSignature, openGuideText } = require('../guideimport');

const MAX_IDS = 600;
const ids = (a) => (Array.isArray(a) ? a.filter((x) => typeof x === 'string' && x).slice(0, MAX_IDS) : []);

function register(ctx, opts = {}) {
  const file = opts.file || path.join(ctx.userData, 'guide.db');
  let db;
  try { db = openGuideDb(file); } catch {
    // Unreadable or corrupt store: it is only a cache, start over.
    for (const s of ['', '-wal', '-shm']) { try { require('node:fs').rmSync(file + s, { force: true }); } catch { /* ignore */ } }
    db = openGuideDb(file);
  }
  ctx.state.guideDb = db;
  let running = null;

  // Loads the guide when the source changed, the stored copy is older than 12h, or force is set.
  async function load(force) {
    const src = guideSource(ctx.settings.get());
    if (!src) return { channels: 0, programmes: 0 };
    const signature = sourceSignature(src);
    if (!force && db.isFresh(signature, Date.now(), STALE_MS)) return db.stats();
    const stats = await db.replaceFrom(await openGuideText(src), { signature });
    ctx.send('guide:updated', stats);
    return stats;
  }

  ctx.handle('guide:load', (opts2) => {
    const force = !!(opts2 && opts2.force);
    if (!running) running = load(force).finally(() => { running = null; });
    return running;
  });
  ctx.handle('guide:nowNext', (tvgIds) => db.nowNext(ids(tvgIds)));
  ctx.handle('guide:schedule', (tvgId, fromMs, toMs) => (typeof tvgId === 'string' ? db.schedule(tvgId, fromMs, toMs) : []));
  ctx.handle('guide:range', (tvgIds, fromMs, toMs) => (Number.isFinite(fromMs) && Number.isFinite(toMs) ? db.range(ids(tvgIds), fromMs, toMs) : {}));
  ctx.handle('guide:search', (query, limit) => db.search(String(query == null ? '' : query).slice(0, 200), limit));

  try { db.prune(); } catch { /* corrupt or locked: next import rebuilds */ }
  return { load };
}

module.exports = { register };
