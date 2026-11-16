'use strict';
// Feature module "recordings": recording engine (ffmpeg), scheduler, series rules and reminders.
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { createEngine } = require('../recordings/engine');
const { createManager } = require('../recordings/manager');

function guideSchedule(tvgId, fromMs, toMs) {
  try {
    const g = require('../guidedb');
    const db = g.getDb && g.getDb();
    if (db && typeof db.schedule === 'function') return Promise.resolve(db.schedule(tvgId, fromMs, toMs));
  } catch { /* guidedb not available */ }
  try { // fall back to the registered guide:schedule IPC handler (private Electron API, guarded)
    const h = require('electron').ipcMain._invokeHandlers;
    const fn = h && h.get && h.get('guide:schedule');
    if (fn) return Promise.resolve(fn({}, tvgId, fromMs, toMs));
  } catch { /* ignore */ }
  return Promise.resolve([]);
}

function register(ctx) {
  const electron = require('electron');
  const file = path.join(ctx.userData, 'recordings.json');
  const cfg = () => (ctx.settings.get().features || {}).recordings || {};
  const defaultFolder = () => path.join(ctx.app.getPath('videos'), 'Nuvio IPTV');
  const config = () => ({ ...cfg(), folder: cfg().folder || defaultFolder() });

  const engine = createEngine();
  const mgr = createManager({
    engine,
    getConfig: config,
    load() { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } },
    save(s) { const tmp = file + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(s)); fs.renameSync(tmp, file); },
    getSchedule: guideSchedule,
    fileSize: (f) => { try { return fs.statSync(f).size; } catch { return null; } },
    freeBytes: (dir) => { try { const s = fs.statfsSync(dir); return Number(s.bavail) * Number(s.bsize); } catch { return null; } },
    mkdirp: (d) => fs.mkdirSync(d, { recursive: true }),
    exists: fs.existsSync,
    removeFile: (f) => fs.rmSync(f, { force: true }),
    log: (...a) => console.log('[recordings]', ...a),
    onChange: () => push(),
    notify(r) {
      if (!electron.Notification || !electron.Notification.isSupported()) return;
      const mins = Math.max(0, Math.round((r.startMs - Date.now()) / 60000));
      const n = new electron.Notification({ title: r.title || r.channel.name, body: `${mins > 0 ? `Starts in ${mins} min` : 'Starting now'} on ${r.channel.name}. Click to watch.` });
      n.on('click', () => {
        const w = ctx.window();
        if (w && !w.isDestroyed()) { if (w.isMinimized()) w.restore(); w.show(); w.focus(); }
        ctx.send('recordings:remindClicked', { channel: r.channel, startMs: r.startMs, title: r.title });
      });
      n.show();
    },
  });

  let pushQueued = false;
  function push() {
    if (pushQueued) return;
    pushQueued = true;
    setTimeout(() => { pushQueued = false; ctx.send('recordings:updated', snapshot()); }, 50);
  }
  function snapshot() {
    return {
      items: mgr.list().map((it) => ({ ...it, fileUrl: it.files && it.files[0] ? pathToFileURL(it.files[0]).href : null })),
      rules: mgr.listRules(), reminders: mgr.listReminders(), config: config(),
    };
  }
  const wrap = (fn) => async (...a) => { const r = fn(...a); push(); return r; };
  const find = (id) => mgr.list().find((x) => x.id === id);

  ctx.handle('recordings:list', () => snapshot());
  ctx.handle('recordings:scheduleOne', wrap((ch, s, e, title, opts) => mgr.scheduleOne(ch, s, e, title, opts || {})));
  ctx.handle('recordings:recordNow', wrap((ch, minutes, title) => mgr.recordNow(ch, minutes, title)));
  ctx.handle('recordings:stop', wrap((id) => mgr.stop(id)));
  ctx.handle('recordings:remove', wrap((id, deleteFile) => mgr.remove(id, !!deleteFile)));
  ctx.handle('recordings:addSeriesRule', wrap((ch, title) => mgr.addSeriesRule(ch, title)));
  ctx.handle('recordings:removeSeriesRule', wrap((id) => mgr.removeSeriesRule(id)));
  ctx.handle('recordings:listRules', () => mgr.listRules());
  ctx.handle('recordings:remind', wrap((ch, s, title, leadMin) => mgr.remind(ch, s, title, leadMin)));
  ctx.handle('recordings:listReminders', () => mgr.listReminders());
  ctx.handle('recordings:removeReminder', wrap((id) => mgr.removeReminder(id)));
  ctx.handle('recordings:config', () => config());
  ctx.handle('recordings:setConfig', (patch) => {
    const cur = cfg();
    const next = { ...cur };
    if (typeof patch.folder === 'string' && patch.folder) next.folder = patch.folder.slice(0, 1024);
    if (patch.container === 'ts' || patch.container === 'mkv') next.container = patch.container;
    for (const k of ['padBeforeMin', 'padAfterMin', 'maxHours', 'remindLeadMin']) if (typeof patch[k] === 'number' && patch[k] >= 0 && patch[k] < 1000) next[k] = patch[k];
    ctx.settings.save({ features: { recordings: next } });
    push();
    return config();
  });
  ctx.handle('recordings:openFolder', (id) => {
    const it = id && find(id);
    const f = it && it.files && it.files[0];
    if (f && fs.existsSync(f)) electron.shell.showItemInFolder(f);
    else { const d = config().folder; fs.mkdirSync(d, { recursive: true }); return electron.shell.openPath(d); }
  });
  ctx.handle('recordings:openFile', async (id) => {
    const it = find(id);
    const f = it && it.files && it.files[0];
    if (!f || !fs.existsSync(f)) throw new Error('File not found');
    const err = await electron.shell.openPath(f);
    if (err) throw new Error(err);
  });

  mgr.start();
  let quitting = false;
  ctx.app.on('before-quit', (e) => {
    if (quitting) return;
    quitting = true;
    if (!engine.count()) { mgr.shutdown(); return; }
    e.preventDefault();
    mgr.shutdown().finally(() => ctx.app.quit());
  });
}

module.exports = { register };
