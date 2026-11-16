'use strict';
// Thin wrapper around ffmpeg: one child process per recording. `spawn` is injectable for tests.
const cp = require('node:child_process');
const { ffmpegPath } = require('./util');

const DEFAULT_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';

function buildArgs({ url, file, userAgent, headers, inputArgs }) {
  const args = ['-hide_banner', '-loglevel', 'warning', '-nostats'];
  if (/^https?:/i.test(url)) {
    args.push('-user_agent', userAgent || DEFAULT_UA);
    if (headers && Object.keys(headers).length) args.push('-headers', Object.entries(headers).map(([k, v]) => `${k}: ${v}\r\n`).join(''));
    args.push('-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '5', '-rw_timeout', '20000000');
  }
  if (inputArgs) args.push(...inputArgs);
  args.push('-i', url, '-map', '0:v?', '-map', '0:a?', '-c', 'copy', '-n', file);
  return args;
}

function createEngine({ spawn = cp.spawn, bin, killAfterMs = 8000 } = {}) {
  const live = new Set();
  const exe = bin || ffmpegPath();

  function start(spec, onExit) {
    const args = buildArgs(spec);
    let tail = '';
    let requested = false;
    let done = false;
    let child;
    let killTimer = null;
    const finish = (code, extra) => {
      if (done) return;
      done = true;
      clearTimeout(killTimer);
      live.delete(handle);
      onExit(code, extra || tail.slice(-600).trim(), requested);
    };
    const handle = {
      args,
      stop() {
        if (done || requested) return;
        requested = true;
        try { child.stdin.write('q'); child.stdin.end(); } catch { /* ignore */ }
        killTimer = setTimeout(() => { try { child.kill(); } catch { /* ignore */ } }, killAfterMs);
        if (killTimer.unref) killTimer.unref();
      },
      kill() { try { child.kill(); } catch { /* ignore */ } },
      get pid() { return child && child.pid; },
    };
    live.add(handle);
    try {
      child = spawn(exe, args, { stdio: ['pipe', 'ignore', 'pipe'], windowsHide: true });
    } catch (e) { setImmediate(() => finish(-1, e.message)); return handle; }
    if (child.stdin) child.stdin.on('error', () => {});
    if (child.stderr) child.stderr.on('data', (d) => { tail = (tail + d).slice(-2000); });
    child.on('error', (e) => finish(-1, e.message));
    child.on('close', (code) => finish(code));
    return handle;
  }

  // Ask everything to finish, then force-kill stragglers. Resolves when all exited (or on timeout).
  function shutdown(timeoutMs = 4000) {
    return new Promise((resolve) => {
      if (!live.size) return resolve();
      for (const h of [...live]) h.stop();
      const t0 = Date.now();
      const iv = setInterval(() => {
        if (!live.size) { clearInterval(iv); resolve(); }
        else if (Date.now() - t0 > timeoutMs) { for (const h of [...live]) h.kill(); clearInterval(iv); resolve(); }
      }, 100);
    });
  }
  return { start, shutdown, count: () => live.size };
}

module.exports = { createEngine, buildArgs, DEFAULT_UA };
