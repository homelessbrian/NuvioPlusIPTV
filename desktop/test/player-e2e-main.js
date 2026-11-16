'use strict';
// Player e2e: `electron test/player-e2e-main.js <baseUrl>`. Plays a set of scenarios in the real renderer and prints `PE2E <json>`.
const { app } = require('electron');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createStore } = require('../src/store');

const base = process.argv[process.argv.length - 1];
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-pe2e-'));
app.setPath('userData', dir);
createStore(dir, null).save({ playlist: null, guide: null, lastChannelUrl: '', volume: 1 });
require('../main.js');

const scenarios = [
  { name: 'ts-ext', ch: { name: 'TS ext', url: base + '/m.ts' }, engine: 'mpegts' },
  { name: 'ts-noext', ch: { name: 'TS noext', url: base + '/live/u/p/1' }, engine: 'mpegts' },
  { name: 'hls', ch: { name: 'HLS', url: base + '/hls/index.m3u8' }, engine: 'hls' },
  { name: 'hls-noext', ch: { name: 'HLS noext', url: base + '/live/u/p/2' }, engine: 'hls' },
  { name: 'ts-as-m3u8', ch: { name: 'Mislabeled', url: base + '/fake.m3u8' }, engine: 'mpegts' },
  { name: 'backup', ch: { name: 'Backup', url: base + '/dead.m3u8', backups: [base + '/dead2.ts', base + '/m.ts'] }, engine: 'mpegts', si: 2 },
  { name: 'mp4-less-unsupported', ch: { name: 'RTMP', url: 'rtmp://example.invalid/live' }, expectMsg: /not supported/ },
];

app.on('browser-window-created', (_e, win) => {
  win.webContents.on('console-message', (_ev, ...a) => { if (process.env.PE2E_VERBOSE) console.log('CONSOLE', JSON.stringify(a)); });
  win.webContents.once('did-finish-load', () => {
    setTimeout(async () => {
      try {
        const out = await win.webContents.executeJavaScript(`(async () => {
          const scenarios = ${JSON.stringify(scenarios)};
          const res = [];
          const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
          for (const sc of scenarios) {
            window.NuvioPlayer.stop();
            window.NuvioPlayer.play(sc.ch);
            const v = window.NuvioPlayer.video();
            const t0 = Date.now(); let ok = false;
            while (Date.now() - t0 < 25000) {
              if (sc.expectMsg) { if (document.querySelector('.player-msg').textContent) break; }
              else if (v.videoWidth > 0 && v.currentTime > 0.4) { ok = true; break; }
              await sleep(200);
            }
            const dbg = window.NuvioPlayer._debug();
            res.push({ name: sc.name, ok, width: v.videoWidth, t: v.currentTime, engine: dbg.engine, si: dbg.session && dbg.session.si, msg: document.querySelector('.player-msg').textContent, ms: Date.now() - t0 });
          }
          return JSON.stringify(res);
        })()`);
        console.log('PE2E ' + out);
        app.exit(0);
      } catch (e) { console.log('PE2E-ERR ' + e.message); app.exit(2); }
    }, 2000);
  });
});
