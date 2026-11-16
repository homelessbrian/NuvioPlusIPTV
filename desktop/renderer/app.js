'use strict';
(() => {
  const ROW_H = 56;
  const $ = (id) => document.getElementById(id);
  const state = {
    channels: [], groups: [], group: 'All', query: '', filtered: [],
    sel: 0, playing: null, guide: new Map(), settings: null, hls: null, sources: { playlist: null, guide: null },
  };

  // ---------- helpers
  const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function setStatus(text, isError) {
    const s = $('status');
    s.textContent = text || '';
    s.className = isError ? 'error' : '';
  }

  // ---------- filtering and list
  function applyFilter() {
    const q = state.query.trim().toLowerCase();
    state.filtered = state.channels.filter((c) =>
      (state.group === 'All' || c.group === state.group) && (!q || c._lc.includes(q)));
    state.sel = Math.min(state.sel, Math.max(0, state.filtered.length - 1));
    $('list-spacer').style.height = state.filtered.length * ROW_H + 'px';
    $('list').scrollTop = 0;
    renderRows();
    setStatus(state.channels.length ? `${state.filtered.length} of ${state.channels.length} channels` : '');
  }

  let rowsQueued = false;
  function renderRows() {
    if (rowsQueued) return;
    rowsQueued = true;
    requestAnimationFrame(() => { rowsQueued = false; drawRows(); });
  }

  function drawRows() {
    const list = $('list');
    const start = Math.max(0, Math.floor(list.scrollTop / ROW_H) - 4);
    const end = Math.min(state.filtered.length, start + Math.ceil(list.clientHeight / ROW_H) + 8);
    const rows = $('list-rows');
    rows.style.top = start * ROW_H + 'px';
    rows.replaceChildren();
    const ids = [];
    for (let i = start; i < end; i++) {
      const ch = state.filtered[i];
      const row = el('div', 'row-ch' + (i === state.sel ? ' sel' : '') + (state.playing && state.playing.id === ch.id ? ' playing' : ''));
      row.dataset.index = String(i);
      if (ch.logo && /^https?:/i.test(ch.logo)) {
        const img = el('img'); img.src = ch.logo; img.loading = 'lazy'; img.alt = ''; img.onerror = () => img.replaceWith(el('div', 'logo-ph'));
        row.append(img);
      } else row.append(el('div', 'logo-ph'));
      const txt = el('div', 'txt');
      txt.append(el('div', 'name', ch.name || '(unnamed)'));
      const g = ch.tvgId && state.guide.get(ch.tvgId);
      txt.append(el('div', 'sub', g && g.now ? `${g.now.title} · until ${fmtTime(g.now.stop)}` : ch.group));
      row.append(txt);
      rows.append(row);
      if (ch.tvgId) ids.push(ch.tvgId);
    }
    wantGuide(ids);
  }

  let guideTimer = null;
  function wantGuide(ids) {
    if (!ids.length) return;
    clearTimeout(guideTimer);
    guideTimer = setTimeout(async () => {
      const stale = ids.filter((id) => { const g = state.guide.get(id); return !g || Date.now() - g.at > 60000; });
      if (!stale.length) return;
      const res = await window.nuvio.nowNext(stale);
      for (const id of stale) state.guide.set(id, { ...(res[id] || {}), at: Date.now() });
      renderRows();
    }, 150);
  }

  function renderGroups() {
    const box = $('groups');
    box.replaceChildren();
    const all = [{ name: 'All', count: state.channels.length }, ...state.groups];
    for (const g of all) {
      const b = el('button', g.name === state.group ? 'active' : '');
      b.append(el('span', null, g.name), el('span', 'count', String(g.count)));
      b.onclick = () => { state.group = g.name; renderGroups(); applyFilter(); };
      box.append(b);
    }
  }

  // ---------- playback
  function showPlayerMsg(text) { $('player-msg').textContent = text || ''; }

  function stopPlayback() {
    if (state.hls) { state.hls.destroy(); state.hls = null; }
    const v = $('video');
    v.removeAttribute('src');
    v.load();
  }

  async function play(ch) {
    state.playing = ch;
    showPlayerMsg('');
    $('now-title').textContent = ch.name;
    $('now-sub').textContent = ch.group;
    $('now-desc').textContent = '';
    window.nuvio.saveSettings({ lastChannelUrl: ch.url });
    renderRows();
    stopPlayback();
    const v = $('video');
    const isHls = /\.m3u8(\?|$)/i.test(ch.url);
    const isFile = /\.(mp4|webm|m4v|mkv)(\?|$)/i.test(ch.url);
    const native = () => { v.src = ch.url; v.play().catch(() => {}); };
    if (/^(rtmp|rtsp|udp):/i.test(ch.url)) {
      showPlayerMsg('This stream type (' + ch.url.split(':')[0] + ') is not supported on Windows yet.');
    } else if (isFile) {
      native();
    } else if (window.Hls && window.Hls.isSupported()) {
      let manifestOk = false;
      let netRetries = 0;
      const hls = new window.Hls({ lowLatencyMode: false, maxBufferLength: 30 });
      state.hls = hls;
      hls.on(window.Hls.Events.MANIFEST_PARSED, () => { manifestOk = true; v.play().catch(() => {}); });
      hls.on(window.Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal || state.hls !== hls) return;
        if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR && manifestOk && netRetries++ < 3) { hls.startLoad(); return; }
        if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && manifestOk) { hls.recoverMediaError(); return; }
        stopPlayback();
        if (!manifestOk && !isHls) {
          // Not HLS: let the browser try the URL directly (works for MP4-style streams).
          native();
          v.onerror = () => showPlayerMsg('Could not play this stream. Raw MPEG-TS streams are not supported on Windows yet.');
        } else {
          showPlayerMsg('Could not play this channel (' + (data.details || data.type) + ').');
        }
      });
      hls.loadSource(ch.url);
      hls.attachMedia(v);
    } else {
      native();
    }
    showSchedule(ch);
  }

  async function showSchedule(ch) {
    const box = $('schedule');
    box.replaceChildren();
    if (!ch.tvgId) return;
    const list = await window.nuvio.schedule(ch.tvgId);
    if (!state.playing || state.playing.id !== ch.id) return;
    const now = Date.now();
    for (const p of list) {
      if (p.stop < now) continue;
      const isNow = p.start <= now;
      const item = el('div', 'item' + (isNow ? ' now' : ''));
      item.append(el('div', 't', `${fmtTime(p.start)} – ${fmtTime(p.stop)}`), el('div', 'title', p.title || ''));
      box.append(item);
      if (isNow) { $('now-sub').textContent = `${ch.group} · ${p.title}`; $('now-desc').textContent = p.desc || ''; }
    }
  }

  // ---------- navigation
  function move(delta) {
    if (!state.filtered.length) return;
    state.sel = Math.min(state.filtered.length - 1, Math.max(0, state.sel + delta));
    const list = $('list');
    const top = state.sel * ROW_H;
    if (top < list.scrollTop) list.scrollTop = top;
    else if (top + ROW_H > list.scrollTop + list.clientHeight) list.scrollTop = top + ROW_H - list.clientHeight;
    renderRows();
  }
  function stepChannel(delta) {
    if (!state.filtered.length) return;
    const cur = state.playing ? state.filtered.findIndex((c) => c.id === state.playing.id) : state.sel;
    state.sel = (Math.max(cur, 0) + delta + state.filtered.length) % state.filtered.length;
    move(0);
    play(state.filtered[state.sel]);
  }

  $('list').addEventListener('scroll', renderRows);
  $('list').addEventListener('click', (e) => {
    const row = e.target.closest('.row-ch');
    if (!row) return;
    state.sel = Number(row.dataset.index);
    play(state.filtered[state.sel]);
  });
  $('search').addEventListener('input', (e) => { state.query = e.target.value; applyFilter(); });
  window.addEventListener('keydown', (e) => {
    const typing = e.target === $('search') || e.target.tagName === 'INPUT';
    if ($('settings-dialog').open) return;
    if (e.key === '/' && !typing) { e.preventDefault(); $('search').focus(); return; }
    if (e.key === 'Escape') {
      if (document.fullscreenElement) document.exitFullscreen();
      else if (typing) { e.target.blur(); $('list').focus(); }
      return;
    }
    if (typing) { if (e.key === 'ArrowDown') { e.preventDefault(); $('list').focus(); } return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); move(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
    else if (e.key === 'PageDown') { e.preventDefault(); move(10); }
    else if (e.key === 'PageUp') { e.preventDefault(); move(-10); }
    else if (e.key === 'Enter') { if (state.filtered[state.sel]) play(state.filtered[state.sel]); }
    else if (e.key === ']') stepChannel(1);
    else if (e.key === '[') stepChannel(-1);
    else if (e.key.toLowerCase() === 'f') {
      if (document.fullscreenElement) document.exitFullscreen(); else $('video-wrap').requestFullscreen();
    } else if (e.key.toLowerCase() === 'm') { $('video').muted = !$('video').muted; }
  });

  // ---------- settings dialog
  const dlg = $('settings-dialog');
  const draft = { playlist: null, guide: null };
  function openSettings() {
    draft.playlist = state.sources.playlist; draft.guide = state.sources.guide;
    $('pl-url').value = draft.playlist && draft.playlist.kind === 'url' ? draft.playlist.value : '';
    $('epg-url').value = draft.guide && draft.guide.kind === 'url' ? draft.guide.value : '';
    $('pl-file-name').textContent = draft.playlist && draft.playlist.kind === 'file' ? draft.playlist.value : '';
    $('epg-file-name').textContent = draft.guide && draft.guide.kind === 'file' ? draft.guide.value : '';
    dlg.showModal();
  }
  $('settings-btn').onclick = openSettings;
  $('settings-cancel').onclick = () => dlg.close();
  $('pl-file').onclick = async () => { const p = await window.nuvio.pickFile('playlist'); if (p) { draft.playlist = { kind: 'file', value: p }; $('pl-url').value = ''; $('pl-file-name').textContent = p; } };
  $('epg-file').onclick = async () => { const p = await window.nuvio.pickFile('guide'); if (p) { draft.guide = { kind: 'file', value: p }; $('epg-url').value = ''; $('epg-file-name').textContent = p; } };
  $('settings-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const pu = $('pl-url').value.trim(); const eu = $('epg-url').value.trim();
    const playlist = pu ? { kind: 'url', value: pu } : draft.playlist;
    const guide = eu ? { kind: 'url', value: eu } : draft.guide;
    dlg.close();
    state.sources = { playlist, guide };
    await window.nuvio.saveSettings({ playlist, guide });
    await loadLibrary();
  });

  // ---------- library
  async function loadLibrary() {
    if (!state.sources.playlist) { setStatus('Add a playlist in Settings to get started.'); openSettings(); return; }
    setStatus('Loading playlist and guide…');
    const r = await window.nuvio.load();
    state.channels = r.channels.map((c) => ({ ...c, _lc: (c.name + ' ' + c.group).toLowerCase() }));
    state.groups = r.groups;
    state.group = 'All'; state.query = ''; $('search').value = ''; state.guide.clear();
    renderGroups(); applyFilter();
    if (r.errors.length) setStatus(r.errors.join('  ·  '), true);
    else if (r.guideChannels) setStatus(`${state.channels.length} channels · guide for ${r.guideChannels} channels`);
    const last = state.channels.find((c) => c.url === state.settings.lastChannelUrl);
    if (last && !state.playing) { state.sel = Math.max(0, state.filtered.indexOf(last)); move(0); }
  }

  (async function init() {
    state.settings = await window.nuvio.getSettings();
    state.sources = { playlist: state.settings.playlist, guide: state.settings.guide };
    $('video').volume = state.settings.volume;
    $('video').addEventListener('volumechange', () => window.nuvio.saveSettings({ volume: $('video').volume }));
    window.addEventListener('resize', renderRows);
    await loadLibrary();
  })();
})();
