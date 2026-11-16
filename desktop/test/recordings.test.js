'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const { sanitizeName, buildFileName, ffmpegPath } = require('../src/recordings/util');
const { createEngine, buildArgs } = require('../src/recordings/engine');
const { createManager } = require('../src/recordings/manager');

const MIN = 60000;
const CH = { id: '1', name: 'BBC One', tvgId: 'bbc1', url: 'http://x/bbc1.m3u8' };

function harness(over = {}) {
  const clock = { t: Date.UTC(2026, 10, 16, 20, 0, 0), m: 1000 };
  const runs = [];
  const engine = {
    start(spec, onExit) {
      const r = { spec, onExit, stopped: false, handle: null };
      r.handle = { stop() { r.stopped = true; setImmediate(() => onExit(0, '', true)); }, kill() {} };
      runs.push(r);
      return r.handle;
    },
    shutdown: async () => {},
  };
  const sizes = new Map();
  let saved = null;
  let schedule = [];
  const notes = [];
  const deps = {
    engine, now: () => clock.t, mono: () => clock.m,
    getConfig: () => ({ folder: 'C:\\rec', padBeforeMin: 0, padAfterMin: 0, ...(over.config || {}) }),
    load: () => saved, save: (s) => { saved = s; },
    fileSize: (f) => sizes.get(f) || 0, freeBytes: () => over.free != null ? over.free : 1e12,
    mkdirp() {}, exists: () => false, removeFile(f) { sizes.delete(f); },
    getSchedule: async () => schedule, notify: (r) => notes.push(r),
  };
  const mgr = createManager(deps);
  const advance = (ms) => { clock.t += ms; clock.m += ms; mgr.tick(); };
  return { mgr, clock, runs, sizes, advance, notes, getSaved: () => saved, setSchedule: (s) => { schedule = s; }, deps };
}

test('sanitizeName handles illegal, reserved and empty names', () => {
  assert.strictEqual(sanitizeName('A/B:C*D?'), 'A_B_C_D_');
  assert.strictEqual(sanitizeName('CON'), '_CON');
  assert.strictEqual(sanitizeName('  ...  '), 'Recording');
  assert.strictEqual(sanitizeName('x'.repeat(200)).length, 80);
  assert.strictEqual(sanitizeName('Show.'), 'Show');
});

test('buildFileName is unique and sane', () => {
  const taken = new Set();
  const ex = (p) => taken.has(p);
  const a = buildFileName('C:\\r', 'BBC: One', 'News/Weather', Date.UTC(2026, 0, 1), 'ts', 1, ex);
  assert.match(path.basename(a), /^BBC_ One - News_Weather - \d{4}-\d\d-\d\d \d\d-\d\d\.ts$/);
  taken.add(a);
  assert.match(buildFileName('C:\\r', 'BBC: One', 'News/Weather', Date.UTC(2026, 0, 1), 'ts', 1, ex), /\(2\)\.ts$/);
});

test('ffmpeg args: reconnect flags only for http, q-able, copy', () => {
  const a = buildArgs({ url: 'http://a/b.ts', file: 'o.ts' });
  assert.ok(a.includes('-reconnect') && a.includes('-reconnect_streamed') && a.includes('-reconnect_delay_max'));
  assert.ok(a.includes('-user_agent') && a.includes('copy'));
  assert.ok(!buildArgs({ url: 'udp://1.2.3.4:1', file: 'o.ts' }).includes('-reconnect'));
});

test('scheduled recording starts and stops at the right times', () => {
  const h = harness();
  const s = h.clock.t + 10 * MIN;
  const it = h.mgr.scheduleOne(CH, s, s + 30 * MIN, 'News');
  assert.strictEqual(it.status, 'scheduled');
  h.advance(9 * MIN);
  assert.strictEqual(h.runs.length, 0);
  h.advance(1 * MIN);
  assert.strictEqual(h.runs.length, 1);
  assert.strictEqual(h.mgr.list()[0].status, 'recording');
  h.sizes.set(h.runs[0].spec.file, 1234);
  h.advance(29 * MIN);
  assert.ok(!h.runs[0].stopped);
  h.advance(1 * MIN);
  assert.ok(h.runs[0].stopped);
  return new Promise((r) => setImmediate(r)).then(() => {
    const x = h.mgr.list()[0];
    assert.strictEqual(x.status, 'completed');
    assert.strictEqual(x.bytes, 1234);
  });
});

test('padding extends the window', () => {
  const h = harness();
  const s = h.clock.t + 10 * MIN;
  h.mgr.scheduleOne(CH, s, s + 10 * MIN, 'P', { padBeforeMs: 2 * MIN, padAfterMs: 3 * MIN });
  h.advance(7 * MIN); assert.strictEqual(h.runs.length, 0);
  h.advance(1 * MIN); assert.strictEqual(h.runs.length, 1);
  h.advance(14 * MIN); assert.ok(!h.runs[0].stopped);
  h.advance(1 * MIN); assert.ok(h.runs[0].stopped);
});

test('catch up: started late inside window; missed windows are marked', () => {
  const h = harness();
  const s = h.clock.t + 1 * MIN;
  h.mgr.scheduleOne(CH, s, s + 60 * MIN, 'Long');
  h.mgr.scheduleOne({ ...CH, url: 'http://x/2' }, s, s + 5 * MIN, 'Short');
  h.advance(10 * MIN); // "woke from sleep" 10 min in
  const l = h.mgr.list();
  assert.strictEqual(l.find((x) => x.title === 'Long').status, 'recording');
  assert.strictEqual(l.find((x) => x.title === 'Short').status, 'missed');
});

test('one recording per channel: duplicate recordNow and overlapping schedule fold together', () => {
  const h = harness();
  const a = h.mgr.recordNow(CH, 30);
  const b = h.mgr.recordNow(CH, 60);
  assert.strictEqual(a.id, b.id);
  assert.strictEqual(h.runs.length, 1);
  assert.strictEqual(h.mgr.list().length, 1);
  assert.strictEqual(h.mgr.list()[0].endMs, h.clock.t + 60 * MIN);
  h.mgr.scheduleOne(CH, h.clock.t, h.clock.t + 90 * MIN, 'Overlap');
  assert.strictEqual(h.mgr.list().length, 1);
  assert.strictEqual(h.runs.length, 1);
  assert.strictEqual(h.mgr.list()[0].endMs, h.clock.t + 90 * MIN);
});

test('recordNow with minutes limit stops itself; default is capped', () => {
  const h = harness({ config: { maxHours: 1 } });
  h.mgr.recordNow(CH, 5);
  h.advance(5 * MIN);
  assert.ok(h.runs[0].stopped);
  const h2 = harness({ config: { maxHours: 1 } });
  const it = h2.mgr.recordNow(CH);
  assert.strictEqual(it.endMs - it.startMs, 60 * MIN);
});

test('unexpected ffmpeg exit retries within the window with a new part, then completes', async () => {
  const h = harness();
  h.mgr.recordNow(CH, 30);
  h.sizes.set(h.runs[0].spec.file, 500);
  h.runs[0].onExit(1, 'Connection reset', false);
  assert.strictEqual(h.mgr.list()[0].status, 'recording');
  h.advance(4000);
  assert.strictEqual(h.runs.length, 2);
  assert.notStrictEqual(h.runs[0].spec.file, h.runs[1].spec.file);
  h.advance(30 * MIN);
  await new Promise((r) => setImmediate(r));
  assert.strictEqual(h.mgr.list()[0].status, 'completed');
});

test('monotonic guard stops a recording if the wall clock jumps backwards', () => {
  const h = harness();
  h.mgr.recordNow(CH, 10);
  h.clock.t -= 3 * 3600000; h.clock.m += 12 * MIN; // clock set back, real time moved on
  h.mgr.tick();
  assert.ok(h.runs[0].stopped);
});

test('disk space check fails the job without spawning', () => {
  const h = harness({ free: 10 * 1048576 });
  h.mgr.recordNow(CH, 5);
  assert.strictEqual(h.runs.length, 0);
  const it = h.mgr.list()[0];
  assert.strictEqual(it.status, 'failed');
  assert.match(it.error, /disk space/);
});

test('cancelled scheduled item does not come back (explicit and via series rule)', async () => {
  const h = harness();
  const s = h.clock.t + 60 * MIN;
  h.setSchedule([{ start: s, stop: s + 30 * MIN, title: 'The Show: Pilot' }, { start: s + 24 * 60 * MIN, stop: s + 25 * 60 * MIN, title: 'The Show' }, { start: s, stop: s + MIN, title: 'The Showdown' }]);
  const rule = h.mgr.addSeriesRule(CH, 'The Show');
  await h.mgr.syncRules();
  let l = h.mgr.list();
  assert.strictEqual(l.length, 2); // Showdown not matched
  assert.ok(l.every((x) => x.seriesRuleId === rule.id));
  await h.mgr.syncRules();
  assert.strictEqual(h.mgr.list().length, 2); // no duplicates
  h.mgr.stop(l[0].id); // cancel one episode
  await h.mgr.syncRules();
  l = h.mgr.list();
  assert.strictEqual(l.length, 1);
  assert.ok(Object.keys(h.mgr.cancelledKeys()).length === 1);
  // explicit user scheduling re-enables it
  h.mgr.scheduleOne(CH, s, s + 30 * MIN, 'The Show: Pilot');
  assert.strictEqual(h.mgr.list().length, 2);
});

test('removing a completed episode keeps it from being re-recorded', async () => {
  const h = harness();
  const s = h.clock.t + 1 * MIN;
  h.setSchedule([{ start: s, stop: s + 30 * MIN, title: 'Show' }]);
  h.mgr.addSeriesRule(CH, 'Show');
  await h.mgr.syncRules();
  h.advance(2 * MIN);
  h.sizes.set(h.runs[0].spec.file, 99);
  h.advance(40 * MIN);
  await new Promise((r) => setImmediate(r));
  assert.strictEqual(h.mgr.list()[0].status, 'completed');
  await h.mgr.syncRules(); assert.strictEqual(h.mgr.list().length, 1);
  h.mgr.remove(h.mgr.list()[0].id, true);
  h.setSchedule([{ start: s, stop: s + 30 * MIN, title: 'Show' }]);
  await h.mgr.syncRules();
  assert.strictEqual(h.mgr.list().length, 0);
});

test('series rule requires a guide id; removing a rule drops its upcoming items', async () => {
  const h = harness();
  assert.throws(() => h.mgr.addSeriesRule({ name: 'x', url: 'u' }, 'T'));
  const s = h.clock.t + 60 * MIN;
  h.setSchedule([{ start: s, stop: s + MIN, title: 'T' }]);
  const r = h.mgr.addSeriesRule(CH, 'T');
  await h.mgr.syncRules();
  assert.strictEqual(h.mgr.list().length, 1);
  h.mgr.removeSeriesRule(r.id);
  assert.strictEqual(h.mgr.list().length, 0);
});

test('reminders fire once before start, not after they are stale', () => {
  const h = harness();
  const s = h.clock.t + 20 * MIN;
  h.mgr.remind(CH, s, 'Match');
  assert.strictEqual(h.mgr.remind(CH, s, 'Match').id, h.mgr.listReminders()[0].id); // de-duped
  h.advance(14 * MIN); assert.strictEqual(h.notes.length, 0);
  h.advance(1 * MIN); assert.strictEqual(h.notes.length, 1);
  h.advance(5 * MIN); assert.strictEqual(h.notes.length, 1);
  assert.strictEqual(h.mgr.listReminders().length, 0);
  const h2 = harness();
  h2.mgr.remind(CH, h2.clock.t + 10 * MIN, 'Late');
  h2.advance(30 * MIN);
  assert.strictEqual(h2.notes.length, 0);
  assert.throws(() => h2.mgr.remind(CH, h2.clock.t - 1, 'Past'));
});

test('persistence round-trip restores schedule, rules, reminders, cancelled set', async () => {
  const h = harness();
  const s = h.clock.t + 60 * MIN;
  h.mgr.scheduleOne(CH, s, s + 30 * MIN, 'A');
  const b = h.mgr.scheduleOne({ ...CH, url: 'http://x/b' }, s, s + 30 * MIN, 'B');
  h.mgr.stop(b.id);
  h.mgr.addSeriesRule(CH, 'Rule');
  h.mgr.remind(CH, s, 'R');
  const saved = JSON.parse(JSON.stringify(h.getSaved()));
  const h2 = harness();
  h2.deps.load = () => saved;
  const m2 = createManager(h2.deps);
  m2.restore();
  assert.deepStrictEqual(m2.list().map((x) => x.title), ['A']);
  assert.strictEqual(m2.listRules().length, 1);
  assert.strictEqual(m2.listReminders().length, 1);
  assert.strictEqual(Object.keys(m2.cancelledKeys()).length, 1);
});

test('restart resumes an interrupted recording inside its window and closes one outside it', () => {
  const h = harness();
  h.mgr.recordNow(CH, 30);
  h.sizes.set(h.runs[0].spec.file, 10);
  const saved = JSON.parse(JSON.stringify(h.getSaved()));
  const h2 = harness();
  h2.deps.load = () => saved;
  const m2 = createManager(h2.deps);
  m2.restore(); m2.tick();
  assert.strictEqual(h2.runs.length, 1);
  const h3 = harness();
  h3.clock.t += 2 * 3600000;
  h3.deps.load = () => saved;
  h3.deps.fileSize = () => 10;
  const m3 = createManager(h3.deps);
  m3.restore();
  assert.strictEqual(m3.list()[0].status, 'completed');
});

// ---- real ffmpeg smoke test ----
const FF = ffmpegPath();
let ffOk = false;
try { ffOk = cp.spawnSync(FF, ['-version'], { windowsHide: true }).status === 0; } catch { /* ignore */ }

test('real ffmpeg records a locally generated test pattern', { skip: ffOk ? false : 'ffmpeg not runnable' }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nuvio-rec-'));
  try {
    const src = path.join(dir, 'src.ts');
    const gen = cp.spawnSync(FF, ['-y', '-f', 'lavfi', '-i', 'testsrc=duration=2:size=160x120:rate=10', '-c:v', 'mpeg2video', '-f', 'mpegts', src], { windowsHide: true });
    assert.strictEqual(gen.status, 0, String(gen.stderr));
    const out = path.join(dir, 'out.ts');
    const engine = createEngine({ bin: FF });
    const res = await new Promise((resolve) => engine.start({ url: src, file: out }, (code, tail, requested) => resolve({ code, tail, requested })));
    assert.strictEqual(res.code, 0, res.tail);
    assert.ok(fs.statSync(out).size > 1000);

    // graceful stop: -re keeps it running in real time, then 'q' must end it cleanly with data on disk
    const out2 = path.join(dir, 'out2.ts');
    const long = path.join(dir, 'long.ts');
    const gen2 = cp.spawnSync(FF, ['-y', '-f', 'lavfi', '-i', 'testsrc=duration=20:size=160x120:rate=10', '-c:v', 'mpeg2video', '-f', 'mpegts', long], { windowsHide: true });
    assert.strictEqual(gen2.status, 0);
    const p = new Promise((resolve) => { const h = engine.start({ url: long, file: out2, inputArgs: ['-re'] }, (code, tail, requested) => resolve({ code, requested })); setTimeout(() => h.stop(), 1500); });
    const r2 = await p;
    assert.strictEqual(r2.requested, true);
    assert.ok(fs.statSync(out2).size > 0);
    assert.strictEqual(engine.count(), 0);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
