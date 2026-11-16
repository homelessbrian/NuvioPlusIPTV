'use strict';
// End-to-end check: `electron test/e2e-main.js <baseUrl>`. Starts the real app against a local
// fixture server, then reads what the UI actually rendered.
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createStore } = require('../src/store');

const base = process.argv[process.argv.length - 1];
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-e2e-'));
app.setPath('userData', dir);
createStore(dir, null).save({
  playlist: { kind: 'url', value: base + '/p.m3u' },
  guide: { kind: 'url', value: base + '/g.xml' },
  lastChannelUrl: '', volume: 1,
});
require('../main.js');

app.on('browser-window-created', (_e, win) => {
  win.webContents.on('console-message', (_ev, ...a) => console.log('CONSOLE', JSON.stringify(a)));
  win.webContents.once('did-finish-load', () => {
    setTimeout(async () => {
      try {
        const out = await win.webContents.executeJavaScript(`(async () => {
          const wait = async (fn, ms) => { const end = Date.now() + ms; while (Date.now() < end) { if (fn()) return true; await new Promise(r => setTimeout(r, 100)); } return false; };
          const nl = String.fromCharCode(10);
          // Rows appear once the playlist has loaded; the first row shows its programme once the guide has.
          await wait(() => { const r = document.querySelector('.row-ch'); return r && r.innerText.includes('Big Game'); }, 25000);
          const rows = [...document.querySelectorAll('.row-ch')].map(r => r.innerText.split(nl).join(' | '));
          if (!rows.length) return JSON.stringify({ norows: true, status: document.querySelector('.lt-status').textContent });
          document.querySelector('.row-ch').click();
          await wait(() => document.querySelector('.schedule') && document.querySelector('.schedule').innerText.includes('Post Game'), 15000);
          return JSON.stringify({ rows, groups: [...document.querySelectorAll('.lt-groups button')].map(b=>b.innerText.split(nl).join(' ')),
            title: document.querySelector('.now-info h2').textContent, schedule: document.querySelector('.schedule').innerText,
            status: document.querySelector('.lt-status').textContent, msg: document.querySelector('.player-msg').textContent });
        })()`);
        console.log('E2E ' + out);
        app.exit(0);
      } catch (e) { console.log('E2E-ERR ' + e.message); app.exit(2); }
    }, 500);
  });
});
