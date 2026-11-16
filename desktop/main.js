'use strict';
const { app, BrowserWindow, ipcMain, dialog, safeStorage, session } = require('electron');
const path = require('node:path');
const { createStore } = require('./src/store');
const { loadPlaylist, loadGuide } = require('./src/sources');
const { nowNext } = require('./src/xmltv');

const store = createStore(app.getPath('userData'), safeStorage);
let settings;
let channels = [];
let guide = null; // { programmes, channelNames }
let win;

const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src * data: blob:",
  "media-src * blob: data:",
  "connect-src *",
  "worker-src blob:",
  "object-src 'none'",
  "base-uri 'none'",
  "frame-src 'none'",
].join('; ');

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 800, minWidth: 900, minHeight: 600,
    backgroundColor: '#0b0d12',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
    },
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
      // IPTV servers rarely send CORS headers; the player needs to read their HLS playlists.
      for (const k of Object.keys(headers)) if (k.toLowerCase().startsWith('access-control-allow-')) delete headers[k];
      headers['Access-Control-Allow-Origin'] = ['*'];
    } else {
      headers['Content-Security-Policy'] = [CSP];
    }
    cb({ responseHeaders: headers });
  });
  session.defaultSession.setPermissionRequestHandler((_wc, _perm, cb) => cb(false));

  ipcMain.handle('settings:get', () => settings);
  ipcMain.handle('settings:save', (_e, next) => {
    settings = { ...settings, ...sanitize(next) };
    store.save(settings);
    return settings;
  });
  ipcMain.handle('dialog:pick', async (_e, kind) => {
    const filters = kind === 'guide'
      ? [{ name: 'XMLTV guide', extensions: ['xml', 'gz'] }]
      : [{ name: 'M3U playlist', extensions: ['m3u', 'm3u8'] }];
    const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters });
    return r.canceled ? null : r.filePaths[0];
  });
  ipcMain.handle('library:load', async () => {
    const result = { ok: true, errors: [], groups: [], channels: [], guideChannels: 0 };
    try {
      const pl = await loadPlaylist(settings.playlist);
      channels = pl.channels;
      result.channels = channels;
      result.groups = pl.groups;
    } catch (e) {
      result.ok = false;
      result.errors.push(`Playlist: ${e.message}`);
      channels = [];
    }
    guide = null;
    try {
      guide = await loadGuide(settings.guide);
      if (guide) result.guideChannels = guide.programmes.size;
    } catch (e) {
      result.errors.push(`Guide: ${e.message}`);
    }
    return result;
  });
  ipcMain.handle('guide:nowNext', (_e, ids) => {
    const out = {};
    if (!guide || !Array.isArray(ids)) return out;
    const at = Date.now();
    for (const id of ids.slice(0, 500)) {
      const { now, next } = nowNext(guide.programmes.get(String(id)), at);
      if (now || next) out[id] = { now, next };
    }
    return out;
  });
  ipcMain.handle('guide:schedule', (_e, id) => (guide && guide.programmes.get(String(id))) || []);

  createWindow();
  if (process.argv.includes('--smoke')) {
    win.webContents.once('did-finish-load', () => setTimeout(() => app.exit(0), 1500));
    win.webContents.once('did-fail-load', () => app.exit(1));
  }
});

function sanitize(next) {
  const src = (s) => (s && typeof s.value === 'string' && (s.kind === 'url' || s.kind === 'file')
    ? { kind: s.kind, value: s.value.slice(0, 4096) } : null);
  const out = {};
  if ('playlist' in next) out.playlist = src(next.playlist);
  if ('guide' in next) out.guide = src(next.guide);
  if (typeof next.lastChannelUrl === 'string') out.lastChannelUrl = next.lastChannelUrl.slice(0, 4096);
  if (typeof next.volume === 'number') out.volume = Math.min(1, Math.max(0, next.volume));
  return out;
}

app.on('window-all-closed', () => app.quit());
