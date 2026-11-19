'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { spawn } = require('node:child_process');
const path = require('node:path');

test('Sports tab renders games from the (faked) ESPN feed, finds a channel and follows a team', { timeout: 120000 }, async (t) => {
  if (process.env.SKIP_E2E) return t.skip('SKIP_E2E set');
  const server = http.createServer((req, res) => {
    if (req.url === '/p.m3u') res.end('#EXTM3U\n#EXTINF:-1 group-title="Sports",NFL 01 - Lions vs Bears\nhttp://127.0.0.1:9/a.m3u8\n#EXTINF:-1 group-title="News",Beta News\nhttp://127.0.0.1:9/b.m3u8\n');
    else { res.statusCode = 404; res.end('nope'); }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const electron = require('electron');
  const out = await new Promise((resolve, reject) => {
    const p = spawn(electron, [path.join(__dirname, 'sports-ui-main.js'), base], { env: { ...process.env, ELECTRON_ENABLE_LOGGING: '0' } });
    let buf = '';
    p.stdout.on('data', (d) => { buf += d; });
    p.on('exit', () => resolve(buf));
    p.on('error', reject);
  });
  server.close();
  const line = out.split('\n').find((l) => l.startsWith('SPORTS'));
  assert.ok(line && line.startsWith('SPORTS '), 'no output:\n' + out);
  const r = JSON.parse(line.slice(7));
  assert.ok(r.games.some((g) => /Lions/.test(g) && /Bears/.test(g) && /LIVE/.test(g)), JSON.stringify(r.games));
  assert.ok(r.channels.includes('NFL 01 - Lions vs Bears'), JSON.stringify(r.channels));
  assert.match(r.hero, /Detroit Lions/);
  assert.match(r.rowTitle, /Lions/);
});
