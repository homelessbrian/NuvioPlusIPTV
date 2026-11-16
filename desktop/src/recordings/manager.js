'use strict';
// Recording scheduler: scheduled items, series rules, reminders. Pure logic; all I/O is injected so it is testable
// with a fake clock and a fake ffmpeg engine.
const { buildFileName, normTitle } = require('./util');

const MIN = 60000;
const MAX_RETRIES = 5;
const RETRY_DELAY = 3000;
const RULE_EVERY = 60000;
const RULE_AHEAD = 48 * 3600000;
const MONO_GRACE = 60000;

function createManager(deps) {
  const {
    engine,
    now = Date.now,
    mono = () => Number(process.hrtime.bigint() / 1000000n),
    load = () => null,
    save = () => {},
    getConfig = () => ({}),
    getSchedule = async () => [],
    notify = () => {},
    onChange = () => {},
    fileSize = () => null,
    freeBytes = () => null,
    mkdirp = () => {},
    exists = () => false,
    removeFile = () => {},
    log = () => {},
  } = deps;
  let seq = 0;
  const newId = deps.newId || (() => `r${now().toString(36)}${(seq++).toString(36)}${Math.random().toString(36).slice(2, 6)}`);

  const state = { items: [], rules: [], cancelled: {}, reminders: [] };
  const running = new Map(); // item id -> { handle }
  const monoDeadline = new Map(); // item id -> monotonic ms at which we force stop
  let timer = null;
  let lastRules = 0;
  let rulesBusy = false;
  let closing = false;

  const cfg = () => {
    const c = getConfig() || {};
    return {
      folder: c.folder, container: c.container === 'mkv' ? 'mkv' : 'ts',
      padBefore: (c.padBeforeMin != null ? c.padBeforeMin : 1) * MIN, padAfter: (c.padAfterMin != null ? c.padAfterMin : 2) * MIN,
      maxMs: (c.maxHours || 8) * 3600000, minFree: (c.minFreeMb != null ? c.minFreeMb : 500) * 1048576,
      leadMs: (c.remindLeadMin != null ? c.remindLeadMin : 5) * MIN,
    };
  };

  const chKey = (ch) => String(ch.url);
  const epKey = (ch, title, start) => `${chKey(ch)}|${normTitle(title)}|${start}`;
  const effStart = (it) => it.startMs - (it.padBefore || 0);
  const effEnd = (it) => it.endMs + (it.padAfter || 0);
  const cleanChannel = (ch) => ({ id: ch.id, name: String(ch.name || 'Channel'), tvgId: ch.tvgId || '', logo: ch.logo || '', group: ch.group || '', url: String(ch.url), backups: Array.isArray(ch.backups) ? ch.backups.slice(0, 5) : [] });

  function persist() {
    try { save(JSON.parse(JSON.stringify({ v: 1, ...state }))); } catch (e) { log('save failed', e.message); }
  }
  function changed(p = true) { if (p) persist(); try { onChange(); } catch { /* ignore */ } }

  function restore() {
    const s = load();
    if (s && typeof s === 'object') {
      state.items = Array.isArray(s.items) ? s.items : [];
      state.rules = Array.isArray(s.rules) ? s.rules : [];
      state.cancelled = s.cancelled && typeof s.cancelled === 'object' ? s.cancelled : {};
      state.reminders = Array.isArray(s.reminders) ? s.reminders : [];
    }
    const t = now();
    for (const it of state.items) {
      if (it.status !== 'recording') continue;
      // Interrupted by an app exit/crash: resume if the window is still open, otherwise close it out.
      if (t < effEnd(it)) { it.retryAt = t; it.retries = 0; } else finalize(it, 'interrupted');
    }
    for (const k of Object.keys(state.cancelled)) if (state.cancelled[k] < t - 30 * 86400000) delete state.cancelled[k];
    persist();
  }

  function totalBytes(it) { let n = 0; for (const f of it.files || []) n += fileSize(f) || 0; return n; }

  function finalize(it, why) {
    running.delete(it.id); monoDeadline.delete(it.id);
    it.bytes = totalBytes(it);
    it.endedAt = now();
    delete it.retryAt;
    if (it.bytes > 0) { it.status = 'completed'; if (why === 'interrupted') it.note = 'Recording was interrupted'; }
    else { it.status = 'failed'; if (!it.error) it.error = why === 'interrupted' ? 'Interrupted before any data was written' : 'No data was recorded'; }
  }

  function pickUrl(it) {
    const urls = [it.channel.url, ...(it.channel.backups || [])];
    return urls[(it.retries || 0) % urls.length];
  }

  function launch(it) {
    const c = cfg();
    const part = (it.files || []).length + 1;
    const file = buildFileName(c.folder, it.channel.name, it.title, it.startedAt || now(), c.container, part, exists);
    it.files = [...(it.files || []), file];
    delete it.retryAt;
    const run = { handle: null };
    running.set(it.id, run);
    run.handle = engine.start({ url: pickUrl(it), file }, (code, errTail, requested) => onExit(it, run, code, errTail, requested));
  }

  function onExit(it, run, code, errTail, requested) {
    if (running.get(it.id) !== run) return;
    running.delete(it.id);
    if (closing) return; // app is exiting: keep the item as 'recording' so the next launch resumes it
    const t = now();
    const size = totalBytes(it);
    if (requested || t >= effEnd(it)) { finalize(it); return changed(); }
    // Unexpected end while the window is still open: stream dropped, ffmpeg crashed, or the machine slept.
    it.retries = (it.retries || 0) + 1;
    if (errTail) it.error = errTail.split('\n').slice(-2).join(' ').slice(0, 300);
    if ((it.retries > MAX_RETRIES && size === 0) || it.retries > MAX_RETRIES * 4) { finalize(it); return changed(); }
    it.retryAt = t + RETRY_DELAY;
    log('retry', it.id, code);
    changed();
  }

  function startItem(it) {
    const c = cfg();
    const t = now();
    // One recording per channel: fold an overlapping job into the running one.
    const other = state.items.find((x) => x !== it && x.status === 'recording' && chKey(x.channel) === chKey(it.channel));
    if (other) {
      const ne = effEnd(it);
      if (ne > effEnd(other)) {
        const delta = ne - effEnd(other);
        other.endMs += delta;
        if (monoDeadline.has(other.id)) monoDeadline.set(other.id, monoDeadline.get(other.id) + delta);
      }
      if (it.title && !other.title.includes(it.title)) other.title += ' + ' + it.title;
      state.items.splice(state.items.indexOf(it), 1);
      return;
    }
    try {
      if (!c.folder) throw new Error('No recordings folder set');
      mkdirp(c.folder);
      const free = freeBytes(c.folder);
      if (free != null && free < c.minFree) throw new Error(`Not enough free disk space (${Math.round(free / 1048576)} MB free)`);
    } catch (e) {
      it.status = 'failed'; it.error = e.message; it.endedAt = t;
      return;
    }
    it.status = 'recording';
    it.startedAt = t;
    it.files = []; it.retries = 0; delete it.error;
    monoDeadline.set(it.id, mono() + Math.max(0, effEnd(it) - t) + MONO_GRACE);
    launch(it);
  }

  function stopRunning(it) {
    const run = running.get(it.id);
    if (run) { it.stopping = true; run.handle.stop(); } else { finalize(it); }
  }

  function tick() {
    const t = now();
    const m = mono();
    let dirty = false;
    for (const it of [...state.items]) {
      if (it.status === 'scheduled') {
        if (t >= effEnd(it)) { it.status = 'missed'; dirty = true; }
        else if (t >= effStart(it)) { startItem(it); dirty = true; }
      } else if (it.status === 'recording') {
        const run = running.get(it.id);
        const over = t >= effEnd(it) || (monoDeadline.has(it.id) && m >= monoDeadline.get(it.id));
        if (run) {
          if (over && !it.stopping) { stopRunning(it); }
        } else if (over) { finalize(it); dirty = true; }
        else if (it.retryAt != null && t >= it.retryAt) { launch(it); dirty = true; }
      }
    }
    // Reminders.
    const c = cfg();
    const before = state.reminders.length;
    state.reminders = state.reminders.filter((r) => {
      if (t >= r.startMs - (r.leadMs != null ? r.leadMs : c.leadMs)) {
        if (t < r.startMs + 5 * MIN) { try { notify(r); } catch (e) { log('notify failed', e.message); } }
        return false;
      }
      return true;
    });
    if (state.reminders.length !== before) dirty = true;
    if (t - lastRules >= RULE_EVERY) { lastRules = t; syncRules().catch((e) => log('rules', e.message)); }
    if (dirty) changed(); else if (running.size) changed(false);
  }

  // ---- series rules ----
  function titleMatches(rule, progTitle) {
    const want = normTitle(rule.title);
    if (!want) return false;
    const full = normTitle(progTitle);
    if (full === want) return true;
    const head = normTitle(String(progTitle).split(/\s[-:\u2013]\s|:\s/)[0]);
    return head === want;
  }

  async function syncRules() {
    if (rulesBusy || !state.rules.length) return 0;
    rulesBusy = true;
    let added = 0;
    try {
      const t = now();
      const c = cfg();
      for (const rule of [...state.rules]) {
        let progs = [];
        try { progs = (await getSchedule(rule.channel.tvgId, t, t + RULE_AHEAD)) || []; } catch (e) { log('schedule', e.message); }
        for (const p of progs) {
          if (!titleMatches(rule, p.title) || p.stop <= t) continue;
          const key = epKey(rule.channel, p.title, p.start);
          if (state.cancelled[key] != null || state.items.some((x) => x.key === key)) continue;
          state.items.push({ id: newId(), key, channel: rule.channel, title: p.title, startMs: p.start, endMs: p.stop, padBefore: c.padBefore, padAfter: c.padAfter, status: 'scheduled', seriesRuleId: rule.id, createdAt: t });
          added++;
        }
      }
    } finally { rulesBusy = false; }
    if (added) { tick(); changed(); }
    return added;
  }

  // ---- public API ----
  function scheduleOne(channel, startMs, endMs, title, opts = {}) {
    if (!channel || !channel.url) throw new Error('Channel has no stream URL');
    startMs = Number(startMs); endMs = Number(endMs);
    if (!(endMs > startMs)) throw new Error('End must be after start');
    const c = cfg();
    const t = now();
    if (endMs + (opts.padAfterMs != null ? opts.padAfterMs : c.padAfter) <= t) throw new Error('That programme has already ended');
    const ch = cleanChannel(channel);
    title = String(title || '').slice(0, 200);
    const key = epKey(ch, title, startMs);
    const dup = state.items.find((x) => x.key === key && ['scheduled', 'recording'].includes(x.status));
    if (dup) return dup;
    delete state.cancelled[key]; // explicit user intent beats an earlier cancel
    const it = {
      id: newId(), key, channel: ch, title, startMs, endMs,
      padBefore: opts.padBeforeMs != null ? opts.padBeforeMs : c.padBefore, padAfter: opts.padAfterMs != null ? opts.padAfterMs : c.padAfter,
      status: 'scheduled', seriesRuleId: opts.seriesRuleId || null, createdAt: t,
    };
    state.items.push(it);
    tick();
    changed();
    return it;
  }

  function recordNow(channel, minutes, title) {
    if (!channel || !channel.url) throw new Error('Channel has no stream URL');
    const c = cfg();
    const t = now();
    const ch = cleanChannel(channel);
    const existing = state.items.find((x) => x.status === 'recording' && chKey(x.channel) === chKey(ch));
    const dur = minutes > 0 ? Math.min(minutes * MIN, c.maxMs) : c.maxMs;
    if (existing) { // already recording this channel: only extend
      if (t + dur > effEnd(existing)) { const d = t + dur - effEnd(existing); existing.endMs += d; if (monoDeadline.has(existing.id)) monoDeadline.set(existing.id, monoDeadline.get(existing.id) + d); changed(); }
      return existing;
    }
    const it = { id: newId(), key: `now|${chKey(ch)}|${t}`, channel: ch, title: String(title || ch.name).slice(0, 200), startMs: t, endMs: t + dur, padBefore: 0, padAfter: 0, status: 'scheduled', createdAt: t, manual: true };
    state.items.push(it);
    tick();
    changed();
    return state.items.find((x) => x.id === it.id) || it;
  }

  function cancelKey(it) { if (it.key && !it.key.startsWith('now|')) state.cancelled[it.key] = it.startMs; }

  function stop(id) {
    const it = state.items.find((x) => x.id === id);
    if (!it) return false;
    if (it.status === 'scheduled') { cancelKey(it); state.items.splice(state.items.indexOf(it), 1); changed(); return true; }
    if (it.status === 'recording') { stopRunning(it); changed(); }
    return true;
  }

  function remove(id, deleteFile) {
    const it = state.items.find((x) => x.id === id);
    if (!it) return false;
    if (it.status === 'recording') { stopRunning(it); }
    cancelKey(it);
    state.items.splice(state.items.indexOf(it), 1);
    if (deleteFile) for (const f of it.files || []) { try { removeFile(f); } catch (e) { log('delete failed', e.message); } }
    changed();
    return true;
  }

  function addSeriesRule(channel, title) {
    if (!channel || !channel.url || !channel.tvgId) throw new Error('Series rules need a channel with a guide id (tvg-id)');
    const nt = normTitle(title);
    if (!nt) throw new Error('Title is empty');
    const ch = cleanChannel(channel);
    let rule = state.rules.find((r) => chKey(r.channel) === chKey(ch) && normTitle(r.title) === nt);
    if (!rule) { rule = { id: newId(), channel: ch, title: String(title).trim().slice(0, 200), createdAt: now() }; state.rules.push(rule); }
    changed();
    syncRules().catch((e) => log('rules', e.message));
    return rule;
  }

  function removeSeriesRule(id, cancelUpcoming = true) {
    const i = state.rules.findIndex((r) => r.id === id);
    if (i < 0) return false;
    state.rules.splice(i, 1);
    if (cancelUpcoming) state.items = state.items.filter((x) => !(x.seriesRuleId === id && x.status === 'scheduled'));
    changed();
    return true;
  }

  function remind(channel, startMs, title, leadMin) {
    if (!channel || !channel.url) throw new Error('Channel has no stream URL');
    const ch = cleanChannel(channel);
    startMs = Number(startMs);
    if (!(startMs > now())) throw new Error('That programme has already started');
    title = String(title || '').slice(0, 200);
    const dup = state.reminders.find((r) => chKey(r.channel) === chKey(ch) && r.startMs === startMs && r.title === title);
    if (dup) return dup;
    const r = { id: newId(), channel: ch, startMs, title };
    if (leadMin > 0) r.leadMs = leadMin * MIN;
    state.reminders.push(r);
    tick();
    changed();
    return r;
  }
  function removeReminder(id) { const n = state.reminders.length; state.reminders = state.reminders.filter((r) => r.id !== id); if (n !== state.reminders.length) changed(); return n !== state.reminders.length; }

  function view(it) {
    const o = { ...it };
    delete o.stopping;
    if (it.status === 'recording') o.bytes = totalBytes(it);
    else if (it.status === 'completed') { o.bytes = totalBytes(it); o.fileMissing = (it.files || []).length > 0 && o.bytes === 0; }
    o.running = running.has(it.id);
    return o;
  }

  function start(intervalMs = 1000) {
    restore();
    tick();
    timer = setInterval(tick, intervalMs);
    if (timer.unref) timer.unref();
  }
  async function shutdown() {
    closing = true;
    if (timer) clearInterval(timer);
    timer = null;
    // Leave 'recording' state in place so the next launch resumes anything still inside its window.
    persist();
    await engine.shutdown();
  }

  return {
    start, restore, tick, syncRules, shutdown,
    list: () => state.items.map(view),
    listRules: () => state.rules.map((r) => ({ ...r })),
    listReminders: () => state.reminders.map((r) => ({ ...r })),
    cancelledKeys: () => ({ ...state.cancelled }),
    scheduleOne, recordNow, stop, remove, addSeriesRule, removeSeriesRule, remind, removeReminder,
  };
}

module.exports = { createManager };
