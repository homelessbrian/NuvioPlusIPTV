'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { spawn } = require('node:child_process');
const path = require('node:path');

const fmt = (ms) => new Date(ms).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';

test('app loads playlist + guide, renders channels, starts playback request', { timeout: 180000 }, async (t) => {
  if (process.env.SKIP_E2E) return t.skip('SKIP_E2E set');
  const now = Date.now();
  const hits = [];
  const server = http.createServer((req, res) => {
    hits.push(req.url);
    if (req.url === '/p.m3u') {
      res.end(`#EXTM3U\n#EXTINF:-1 tvg-id="a.us" group-title="Sports",Alpha Sports\n${'http://127.0.0.1:' + server.address().port}/a.m3u8\n#EXTINF:-1 group-title="News",Beta News\nhttp://127.0.0.1:${server.address().port}/b.m3u8\n`);
    } else if (req.url === '/g.xml') {
      res.end(`<tv><programme start="${fmt(now - 1800e3)}" stop="${fmt(now + 1800e3)}" channel="a.us"><title>Big Game</title></programme><programme start="${fmt(now + 1800e3)}" stop="${fmt(now + 5400e3)}" channel="a.us"><title>Post Game</title></programme></tv>`);
    } else { res.statusCode = 404; res.end('nope'); }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const electron = require('electron');
  const out = await new Promise((resolve, reject) => {
    const p = spawn(electron, [path.join(__dirname, 'e2e-main.js'), base], { env: { ...process.env, ELECTRON_ENABLE_LOGGING: '0' } });
    let buf = '';
    p.stdout.on('data', (d) => { buf += d; });
    p.on('exit', () => resolve(buf));
    p.on('error', reject);
  });
  server.close();
  const line = out.split('\n').find((l) => l.startsWith('E2E '));
  assert.ok(line, 'no E2E output:\n' + out);
  const r = JSON.parse(line.slice(4));
  assert.equal(r.rows.length, 2);
  assert.match(r.rows[0], /Alpha Sports.*Big Game/);
  assert.equal(r.title, 'Alpha Sports');
  assert.match(r.schedule, /Big Game/);
  assert.match(r.schedule, /Post Game/);
  assert.ok(hits.includes('/a.m3u8'), 'player never requested the stream: ' + hits.join(','));
});
