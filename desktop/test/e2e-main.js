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
          const rows = [...document.querySelectorAll('.row-ch')].map(r => r.innerText.split(String.fromCharCode(10)).join(' | '));
          if (!document.querySelector('.row-ch')) return JSON.stringify({ norows: true, status: document.getElementById('status').textContent, html: document.body.innerText.slice(0,400) });
          document.querySelector('.row-ch').click();
          await new Promise(r => setTimeout(r, 2500));
          return JSON.stringify({ rows, groups: [...document.querySelectorAll('#groups button')].map(b=>b.innerText.split(String.fromCharCode(10)).join(' ')),
            title: document.getElementById('now-title').textContent, schedule: document.getElementById('schedule').innerText,
            status: document.getElementById('status').textContent, msg: document.getElementById('player-msg').textContent });
        })()`);
        console.log('E2E ' + out);
        app.exit(0);
      } catch (e) { console.log('E2E-ERR ' + e.message); app.exit(2); }
    }, 2500);
  });
});
