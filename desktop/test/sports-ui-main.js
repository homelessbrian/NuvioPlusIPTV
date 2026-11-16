'use strict';
// `electron test/sports-ui-main.js <baseUrl>`: the real app with ESPN faked by the saved fixtures (dates moved to "now"),
// opens the Sports tab and reports what rendered.
const { app } = require('electron');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createStore } = require('../src/store');

const base = process.argv[process.argv.length - 1];
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-sports-'));
app.setPath('userData', dir);
createStore(dir, null).save({
  playlist: { kind: 'url', value: base + '/p.m3u' }, lastChannelUrl: '', volume: 1,
  features: { sports: { region: 'US' } },
});

const delta = Date.now() - Date.parse('2026-11-15T18:30:00Z');
const shift = (text) => text.replace(/"(date|endDate)": "(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})Z"/g, (_m, k, d) => `"${k}": "${new Date(Date.parse(d + ':00Z') + delta).toISOString().slice(0, 16)}Z"`);
const fixture = (n) => shift(fs.readFileSync(path.join(__dirname, 'fixtures', 'sports', n), 'utf8'));
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, opts) => {
  const u = String(url);
  if (!u.includes('espn.com')) return realFetch(url, opts);
  let body = '{"events":[]}';
  if (/football\/nfl\/scoreboard/.test(u)) body = fixture('nfl-scoreboard.json');
  else if (/soccer\/eng\.1\/scoreboard/.test(u)) body = fixture('soccer-scoreboard.json');
  else if (/standings/.test(u)) body = fixture('nba-standings.json');
  return new Response(body, { status: 200, headers: { 'content-type': 'application/json' } });
};
require('../main.js');

app.on('browser-window-created', (_e, win) => {
  win.webContents.once('did-finish-load', () => {
    setTimeout(async () => {
      try {
        const out = await win.webContents.executeJavaScript(`(async () => {
          const wait = async (fn, ms) => { const end = Date.now() + ms; while (Date.now() < end) { if (fn()) return true; await new Promise(r => setTimeout(r, 100)); } return false; };
          const q = (s) => document.querySelector(s);
          await wait(() => document.querySelectorAll('.row-ch').length > 0, 20000);
          NuvioUI.select('sports');
          q('.sp-tab:nth-of-type(2)').click();
          await wait(() => q('.sp-game'), 20000);
          const games = [...document.querySelectorAll('.sp-game')].map(g => g.innerText.split(String.fromCharCode(10)).join(' | '));
          const lions = [...document.querySelectorAll('.sp-game')].find(g => g.innerText.includes('Lions'));
          lions.click();
          await wait(() => q('.sp-ch'), 20000);
          const channels = [...document.querySelectorAll('.sp-chname')].map(n => n.textContent);
          const hero = q('.sp-hero').innerText.split(String.fromCharCode(10)).join(' | ');
          const followBtn = [...document.querySelectorAll('.sp-follow')].find(b => b.textContent.includes('Lions'));
          followBtn.click();
          q('.sp-tab').click();
          await wait(() => document.querySelector('.sp-rowtitle'), 10000);
          const rowTitle = q('.sp-rowtitle').innerText.split(String.fromCharCode(10)).join(' ');
          return JSON.stringify({ games, channels, hero, rowTitle, status: q('.sp-status').textContent });
        })()`);
        console.log('SPORTS ' + out);
        app.exit(0);
      } catch (e) { console.log('SPORTS-ERR ' + e.message); app.exit(2); }
    }, 500);
  });
});
