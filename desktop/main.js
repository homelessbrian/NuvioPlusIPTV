'use strict';
const { app, BrowserWindow, ipcMain, dialog, safeStorage, session } = require('electron');
const path = require('node:path');
const { createStore } = require('./src/store');

// Features register their own IPC handlers (channel names are "<feature>:<action>").
const FEATURES = ['library', 'guide', 'ondemand', 'sports', 'recordings'];

const CSP = [
  "default-src 'self'", "script-src 'self'", "style-src 'self'", 'img-src * data: blob:',
  'media-src * blob: data:', 'connect-src *', 'worker-src blob:', "object-src 'none'", "base-uri 'none'", "frame-src 'none'",
].join('; ');

let win;
const store = createStore(app.getPath('userData'), safeStorage);
let settings;

const ctx = {
  app,
  dialog,
  userData: app.getPath('userData'),
  // Shared, in-memory state that features may read (set by the owning feature).
  state: { channels: [], guideDb: null },
  settings: {
    get: () => settings,
    save(partial) { settings = { ...settings, ...sanitize(partial) }; store.save(settings); return settings; },
  },
  window: () => win,
  send(channel, payload) { if (win && !win.isDestroyed()) win.webContents.send(channel, payload); },
  handle(channel, fn) {
    if (!/^[a-z]+:[A-Za-z]+$/.test(channel)) throw new Error('bad channel name ' + channel);
    ipcMain.handle(channel, (_e, ...args) => fn(...args));
  },
};

function createWindow() {
  win = new BrowserWindow({
    width: 1400, height: 860, minWidth: 1000, minHeight: 640,
    backgroundColor: '#0b0d12', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true },
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

app.whenReady().then(() => {
  settings = store.load();
  session.defaultSession.webRequest.onHeadersReceived((details, cb) => {
    const headers = { ...details.responseHeaders };
    if (/^https?:/i.test(details.url)) {
      // IPTV servers rarely send CORS headers; the player needs to read their playlists and segments.
      for (const k of Object.keys(headers)) if (k.toLowerCase().startsWith('access-control-allow-')) delete headers[k];
      headers['Access-Control-Allow-Origin'] = ['*'];
    } else {
      headers['Content-Security-Policy'] = [CSP];
    }
    cb({ responseHeaders: headers });
  });
  session.defaultSession.setPermissionRequestHandler((_wc, _perm, cb) => cb(false));

  ctx.handle('settings:get', () => settings);
  ctx.handle('settings:save', (next) => ctx.settings.save(next));
  ctx.handle('dialog:pick', async (kind) => {
    const filters = {
      guide: [{ name: 'XMLTV guide', extensions: ['xml', 'gz'] }],
      playlist: [{ name: 'M3U playlist', extensions: ['m3u', 'm3u8'] }],
      backup: [{ name: 'JSON', extensions: ['json'] }],
    }[kind] || [];
    const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters });
    return r.canceled ? null : r.filePaths[0];
  });
  ctx.handle('dialog:folder', async () => {
    const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'] });
    return r.canceled ? null : r.filePaths[0];
  });

  ctx.handle('settings:export', async () => {
    const r = await dialog.showSaveDialog(win, { defaultPath: 'nuvio-iptv-backup.json', filters: [{ name: 'JSON', extensions: ['json'] }] });
    if (r.canceled || !r.filePath) return null;
    require('node:fs').writeFileSync(r.filePath, JSON.stringify({ format: 1, savedAt: Date.now(), data: settings }, null, 2));
    return { path: r.filePath };
  });
  ctx.handle('settings:import', async () => {
    const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters: [{ name: 'JSON', extensions: ['json'] }] });
    if (r.canceled) return { ok: false };
    const j = JSON.parse(require('node:fs').readFileSync(r.filePaths[0], 'utf8'));
    if (!j || j.format !== 1 || typeof j.data !== 'object') throw new Error('Not a Nuvio IPTV backup file.');
    ctx.settings.save(j.data);
    return { ok: true };
  });

  for (const name of FEATURES) require(`./src/features/${name}`).register(ctx);

  createWindow();
  if (process.argv.includes('--smoke')) {
    win.webContents.once('did-finish-load', () => setTimeout(() => app.exit(0), 1500));
    win.webContents.once('did-fail-load', () => app.exit(1));
  }
});

// Settings are a flat object. Known keys are validated; feature keys live under "features.<name>".
function sanitize(next) {
  const src = (s) => (s && typeof s.value === 'string' && ['url', 'file'].includes(s.kind) ? { kind: s.kind, value: s.value.slice(0, 4096) } : null);
  const out = {};
  if ('playlist' in next) out.playlist = src(next.playlist);
  if ('guide' in next) out.guide = src(next.guide);
  if ('xtream' in next) {
    const x = next.xtream;
    out.xtream = x && typeof x.server === 'string' ? { server: x.server.slice(0, 512), username: String(x.username || '').slice(0, 256), password: String(x.password || '').slice(0, 256) } : null;
  }
  if (typeof next.lastChannelUrl === 'string') out.lastChannelUrl = next.lastChannelUrl.slice(0, 4096);
  if (typeof next.volume === 'number') out.volume = Math.min(1, Math.max(0, next.volume));
  for (const k of ['favorites', 'hidden', 'recents']) if (Array.isArray(next[k])) out[k] = next[k].filter((x) => typeof x === 'string').slice(0, 20000);
  if (next.features && typeof next.features === 'object') {
    out.features = { ...(settings.features || {}) };
    for (const [k, v] of Object.entries(next.features)) if (/^[a-z]+$/.test(k)) out.features[k] = v;
  }
  return out;
}

app.on('window-all-closed', () => app.quit());
module.exports = { ctx };
