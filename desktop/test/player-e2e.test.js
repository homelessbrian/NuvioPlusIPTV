'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');

test('player plays TS/HLS/backup/fallback scenarios in Electron', { timeout: 240000 }, async (t) => {
  if (process.env.SKIP_E2E) return t.skip('SKIP_E2E set');
  let ffmpeg;
  try { ffmpeg = require('ffmpeg-static'); } catch { return t.skip('ffmpeg-static not installed'); }
  if (!ffmpeg || !fs.existsSync(ffmpeg)) return t.skip('ffmpeg binary missing');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-media-'));
  const ts = path.join(dir, 's.ts');
  const gen = spawnSync(ffmpeg, ['-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=320x240:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440', '-t', '6',
    '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-g', '25', '-c:a', 'aac', '-f', 'mpegts', ts]);
  if (gen.status !== 0) return t.skip('ffmpeg could not generate a sample (no libx264?)');
  fs.mkdirSync(path.join(dir, 'h'));
  const hl = spawnSync(ffmpeg, ['-v', 'error', '-i', ts, '-c', 'copy', '-f', 'hls', '-hls_time', '2', '-hls_list_size', '0', '-hls_segment_filename', path.join(dir, 'h', 'seg%d.ts'), path.join(dir, 'h', 'index.m3u8')]);
  if (hl.status !== 0) return t.skip('ffmpeg could not generate HLS');

  const tsBuf = fs.readFileSync(ts);
  const server = http.createServer((req, res) => {
    const u = req.url.split('?')[0];
    const sendTs = () => { res.writeHead(200, { 'content-type': 'video/mp2t', 'access-control-allow-origin': '*' }); res.end(tsBuf); };
    if (u === '/m.ts' || u === '/live/u/p/1' || u === '/fake.m3u8') return sendTs();
    if (u === '/live/u/p/2') {
      const pl = fs.readFileSync(path.join(dir, 'h', 'index.m3u8'), 'utf8').replace(/^seg/gm, '/hls/seg');
      res.writeHead(200, { 'content-type': 'text/plain', 'access-control-allow-origin': '*' }); return res.end(pl);
    }
    const m = /^\/hls\/([\w.]+)$/.exec(u);
    if (m && fs.existsSync(path.join(dir, 'h', m[1]))) {
      res.writeHead(200, { 'content-type': m[1].endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t', 'access-control-allow-origin': '*' });
      return res.end(fs.readFileSync(path.join(dir, 'h', m[1])));
    }
    res.statusCode = 404; res.end('nope');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const electron = require('electron');
  const out = await new Promise((resolve, reject) => {
    const p = spawn(electron, [path.join(__dirname, 'player-e2e-main.js'), base], { env: { ...process.env, ELECTRON_ENABLE_LOGGING: '0' } });
    let buf = ''; p.stdout.on('data', (d) => { buf += d; }); p.on('exit', () => resolve(buf)); p.on('error', reject);
  });
  server.close();
  const line = out.split('\n').find((l) => l.startsWith('PE2E '));
  assert.ok(line, 'no PE2E output:\n' + out);
  const r = JSON.parse(line.slice(5));
  const by = Object.fromEntries(r.map((x) => [x.name, x]));
  console.log(JSON.stringify(r));
  for (const n of ['ts-ext', 'ts-noext', 'hls', 'hls-noext', 'ts-as-m3u8', 'backup']) assert.ok(by[n].ok, `${n} did not play: ${JSON.stringify(by[n])}`);
  assert.equal(by['ts-ext'].engine, 'mpegts');
  assert.equal(by['ts-noext'].engine, 'mpegts');
  assert.equal(by.hls.engine, 'hls');
  assert.equal(by['hls-noext'].engine, 'hls');
  assert.equal(by['ts-as-m3u8'].engine, 'mpegts');
  assert.equal(by.backup.si, 2);
  assert.match(by['mp4-less-unsupported'].msg, /not supported/);
});
