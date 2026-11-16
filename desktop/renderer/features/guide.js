'use strict';
// Guide tab: TiviMate-style EPG grid. Virtualised in both directions (rows and time), programmes are
// loaded lazily per 6h block for the visible channels through guide:range.
(() => {
  const CH_W = 210;          // channel column width
  const ROW_H = 54;
  const HEAD_H = 34;
  const PX_MIN = 4;          // pixels per minute (30 min = 120px)
  const PX_MS = PX_MIN / 60000;
  const HALF = 30 * 60000;
  const BLK = 6 * 3600e3;
  const BACK = 6 * 3600e3;
  const AHEAD = 7 * 24 * 3600e3;

  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const fmtDay = (ms) => new Date(ms).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });

  const st = {
    channels: [], filtered: [], group: 'All', query: '', settings: {},
    t0: 0, t1: 0,
    row: 0, ft: 0,                    // keyboard focus: row index in `filtered` and an instant in time
    data: new Map(),                  // tvgId -> { byStart: Map, arr: Array|null }
    loaded: new Set(),                // `${tvgId}|${block}`
    pending: new Set(),
    gen: 0,                           // bumped when cached data is dropped
    ui: {}, hasGuide: null, requested: false, mounted: false, pop: null,
  };

  const favs = () => new Set(st.settings.favorites || []);
  const hiddenSet = () => new Set(st.settings.hidden || []);

  // ---- data ----
  function listFor(id) {
    const d = st.data.get(id);
    if (!d) return null;
    if (!d.arr) d.arr = [...d.byStart.values()].sort((a, b) => a.start - b.start);
    return d.arr;
  }
  function merge(id, progs) {
    let d = st.data.get(id);
    if (!d) { d = { byStart: new Map(), arr: null }; st.data.set(id, d); }
    for (const p of progs) d.byStart.set(p.start, p);
    d.arr = null;
  }
  function dropData() { st.data.clear(); st.loaded.clear(); st.pending.clear(); st.gen++; }

  let needTimer = null;
  function need(ids, tA, tB) {
    if (!ids.length) return;
    clearTimeout(needTimer);
    needTimer = setTimeout(() => fetchBlocks(ids, tA, tB), 90);
  }
  async function fetchBlocks(ids, tA, tB) {
    const gen = st.gen;
    if (st.loaded.size > 30000) dropData();
    const b0 = Math.floor(tA / BLK); const b1 = Math.floor(tB / BLK);
    let changed = false;
    for (let b = b0; b <= b1; b++) {
      const miss = ids.filter((id) => !st.loaded.has(id + '|' + b) && !st.pending.has(id + '|' + b));
      if (!miss.length) continue;
      for (const id of miss) st.pending.add(id + '|' + b);
      let res = null;
      try { res = await window.nuvio.invoke('guide:range', miss, b * BLK, (b + 1) * BLK); } catch { res = null; }
      if (gen !== st.gen) return;
      for (const id of miss) {
        st.pending.delete(id + '|' + b);
        if (res) { st.loaded.add(id + '|' + b); if (res[id]) merge(id, res[id]); }
      }
      changed = true;
    }
    if (changed) scheduleDraw();
  }

  // ---- filtering ----
  function groupNames() {
    const seen = new Map();
    for (const c of st.channels) { const g = c.group || ''; if (g) seen.set(g, (seen.get(g) || 0) + 1); }
    return [...seen.entries()];
  }
  function renderGroupSelect() {
    const sel = st.ui.group;
    const cur = st.group;
    sel.replaceChildren();
    const add = (value, label) => { const o = el('option', null, label); o.value = value; sel.append(o); };
    add('All', `All channels (${st.channels.length})`);
    add('\u0000fav', `Favorites (${favs().size})`);
    for (const [g, n] of groupNames()) add('g:' + g, `${g} (${n})`);
    sel.value = cur; if (sel.value !== cur) { st.group = 'All'; sel.value = 'All'; }
  }
  function applyFilter(keepFocus) {
    const q = st.query.trim().toLowerCase();
    const f = favs(); const h = hiddenSet();
    const g = st.group;
    st.filtered = st.channels.filter((c) => {
      if (h.has(c.url)) return false;
      if (g === '\u0000fav') { if (!f.has(c.url)) return false; } else if (g !== 'All' && 'g:' + (c.group || '') !== g) return false;
      return !q || ((c._lc || (c.name || '').toLowerCase()).includes(q));
    });
    if (!keepFocus) { st.row = 0; st.ui.sc.scrollTop = 0; }
    st.row = Math.min(st.row, Math.max(0, st.filtered.length - 1));
    st.ui.spacer.style.height = HEAD_H + st.filtered.length * ROW_H + 'px';
    st.ui.empty.hidden = st.filtered.length > 0;
    if (!st.filtered.length) st.ui.empty.textContent = st.channels.length ? 'No channels match.' : 'No channels yet. Add a playlist in Settings and open Live TV.';
    updateStatus();
    scheduleDraw();
  }
  function updateStatus() {
    const n = st.filtered.length;
    const t = st.channels.length ? `${n} channel${n === 1 ? '' : 's'}` : '';
    const g = st.hasGuide === false ? ' · no guide loaded (set a guide URL or file in Settings)' : '';
    st.ui.status.textContent = t + g;
  }

  // ---- geometry ----
  const xOf = (t) => CH_W + (t - st.t0) * PX_MS;
  function setTimeline() {
    const now = Date.now();
    st.t0 = Math.floor((now - BACK) / HALF) * HALF;
    st.t1 = Math.ceil((now + AHEAD) / HALF) * HALF;
    st.stamp = now;
    const w = CH_W + (st.t1 - st.t0) * PX_MS;
    st.ui.spacer.style.width = w + 'px';
    st.ui.head.style.width = w + 'px';
    st.ui.rows.style.width = w + 'px';
    buildTicks();
  }
  function buildTicks() {
    const frag = document.createDocumentFragment();
    for (let t = st.t0; t < st.t1; t += HALF) {
      const tick = el('div', 'gd-tick');
      tick.style.left = xOf(t) + 'px';
      tick.style.width = HALF * PX_MS + 'px';
      const d = new Date(t);
      const midnight = d.getHours() === 0 && d.getMinutes() === 0;
      tick.textContent = midnight ? fmtDay(t) : fmtTime(t);
      if (midnight) tick.classList.add('day');
      frag.append(tick);
    }
    st.ui.ticks.replaceChildren(frag);
  }
  function scrollToTime(t, lead = HALF) {
    st.ui.sc.scrollLeft = Math.max(0, (t - lead - st.t0) * PX_MS);
  }
  const visibleTime = () => {
    const sc = st.ui.sc;
    return { tA: st.t0 + sc.scrollLeft / PX_MS, tB: st.t0 + (sc.scrollLeft + sc.clientWidth - CH_W) / PX_MS };
  };

  // ---- drawing ----
  let queued = false;
  function scheduleDraw() { if (queued) return; queued = true; requestAnimationFrame(() => { queued = false; draw(); }); }

  function progAt(row, t) {
    const ch = st.filtered[row];
    const list = ch && ch.tvgId ? listFor(ch.tvgId) : null;
    if (!list) return null;
    for (const p of list) if (p.start <= t && p.stop > t) return p;
    return null;
  }

  function logo(ch) {
    if (ch.logo && /^https?:/i.test(ch.logo)) {
      const img = el('img'); img.src = ch.logo; img.loading = 'lazy'; img.alt = '';
      img.onerror = () => img.replaceWith(el('div', 'gd-logo-ph'));
      return img;
    }
    return el('div', 'gd-logo-ph');
  }

  function draw() {
    if (!st.mounted) return;
    const { sc, rows } = st.ui;
    const n = st.filtered.length;
    const first = Math.max(0, Math.floor(sc.scrollTop / ROW_H) - 2);
    const last = Math.min(n, Math.ceil((sc.scrollTop + sc.clientHeight) / ROW_H) + 2);
    const { tA, tB } = visibleTime();
    const now = Date.now();
    const f = favs();
    const frag = document.createDocumentFragment();
    const wantIds = [];
    for (let i = first; i < last; i++) {
      const ch = st.filtered[i];
      const row = el('div', 'gd-row' + (i === st.row ? ' sel' : ''));
      row.style.top = i * ROW_H + 'px';
      row.style.height = ROW_H + 'px';
      const cc = el('div', 'gd-ch'); cc.dataset.row = String(i);
      const star = el('button', 'gd-star' + (f.has(ch.url) ? ' on' : ''), f.has(ch.url) ? '★' : '☆');
      star.dataset.fav = String(i); star.title = 'Favorite'; star.tabIndex = -1;
      cc.append(logo(ch), el('div', 'gd-name', ch.name || '(unnamed)'), star);
      row.append(cc);
      const id = ch.tvgId;
      const list = id ? listFor(id) : null;
      if (id) wantIds.push(id);
      let any = false;
      if (list) {
        for (const p of list) {
          if (p.stop <= tA || p.start >= tB) continue;
          any = true;
          const left = xOf(Math.max(p.start, st.t0));
          const right = xOf(Math.min(p.stop, st.t1));
          const cell = el('div', 'gd-prog');
          cell.style.left = left + 'px';
          cell.style.width = Math.max(2, right - left - 2) + 'px';
          cell.dataset.row = String(i); cell.dataset.start = String(p.start);
          if (p.start <= now && p.stop > now) cell.classList.add('live');
          if (p.stop <= now) cell.classList.add('past');
          if (i === st.row && p.start <= st.ft && p.stop > st.ft) cell.classList.add('focus');
          // keep the title readable while the cell is partly scrolled under the channel column
          const shift = Math.min(Math.max(0, sc.scrollLeft + CH_W - left), Math.max(0, right - left - 70));
          const t = el('div', 'gd-title', p.title || '(no title)');
          const s = el('div', 'gd-time', `${fmtTime(p.start)} - ${fmtTime(p.stop)}`);
          if (shift > 0) { t.style.marginLeft = shift + 'px'; s.style.marginLeft = shift + 'px'; }
          cell.append(t, s);
          row.append(cell);
        }
      }
      if (!any) {
        const none = el('div', 'gd-none' + (i === st.row ? ' focus' : ''), id ? (st.loaded.has(id + '|' + Math.floor(tA / BLK)) ? 'No guide data' : '') : 'No guide id');
        none.style.left = Math.max(CH_W, sc.scrollLeft + CH_W) + 8 + 'px';
        none.dataset.row = String(i);
        row.append(none);
      }
      frag.append(row);
    }
    rows.replaceChildren(frag);
    placeNowLine(now);
    if (wantIds.length) need([...new Set(wantIds)], tA - HALF, tB + HALF);
  }

  function placeNowLine(now = Date.now()) {
    const line = st.ui.now;
    line.style.left = xOf(now) + 'px';
    line.style.height = HEAD_H + st.filtered.length * ROW_H + 'px';
  }

  // ---- focus & keyboard ----
  function ensureVisible() {
    const sc = st.ui.sc;
    const top = st.row * ROW_H;
    if (top < sc.scrollTop) sc.scrollTop = top;
    else if (top + ROW_H + HEAD_H > sc.scrollTop + sc.clientHeight) sc.scrollTop = top + ROW_H + HEAD_H - sc.clientHeight;
    const p = progAt(st.row, st.ft);
    const s = p ? p.start : Math.floor(st.ft / HALF) * HALF;
    const e = p ? p.stop : s + HALF;
    const l = xOf(Math.max(s, st.t0)); const r = xOf(Math.min(e, st.t1));
    if (l < sc.scrollLeft + CH_W) sc.scrollLeft = Math.max(0, l - CH_W);
    else if (r > sc.scrollLeft + sc.clientWidth) sc.scrollLeft = Math.max(0, Math.min(r - sc.clientWidth, l - CH_W));
  }
  function moveRow(d) {
    if (!st.filtered.length) return;
    st.row = Math.min(st.filtered.length - 1, Math.max(0, st.row + d));
    ensureVisible(); scheduleDraw();
  }
  function moveTime(dir) {
    const cur = progAt(st.row, st.ft);
    const list = (st.filtered[st.row] && st.filtered[st.row].tvgId && listFor(st.filtered[st.row].tvgId)) || [];
    let t;
    if (dir > 0) {
      const nx = list.find((p) => p.start >= (cur ? cur.stop : st.ft + 1));
      t = nx ? nx.start : (cur ? cur.stop : st.ft + HALF);
    } else {
      const prev = [...list].reverse().find((p) => p.start < (cur ? cur.start : st.ft));
      t = prev ? prev.start : (cur ? cur.start - HALF : st.ft - HALF);
    }
    st.ft = Math.min(st.t1 - 1, Math.max(st.t0, t));
    ensureVisible(); scheduleDraw();
  }
  function scrollTime(dir) { st.ui.sc.scrollLeft += dir * 2 * 3600e3 * PX_MS; }
  function jumpNow() { st.ft = Date.now(); scrollToTime(st.ft); ensureVisible(); scheduleDraw(); }

  function playRow(i) {
    const ch = st.filtered[i];
    if (!ch) return;
    closePop();
    if (window.NuvioPlayer) window.NuvioPlayer.play(ch);
    window.nuvio.invoke('settings:get').then((s) => {
      const recents = [ch.url, ...(s.recents || []).filter((u) => u !== ch.url)].slice(0, 50);
      return window.nuvio.invoke('settings:save', { lastChannelUrl: ch.url, recents });
    }).then((s) => { if (s) st.settings = s; }).catch(() => {});
  }

  async function toggleFav(i) {
    const ch = st.filtered[i]; if (!ch) return;
    const s = new Set((await window.nuvio.invoke('settings:get')).favorites || []);
    if (s.has(ch.url)) s.delete(ch.url); else s.add(ch.url);
    st.settings = await window.nuvio.invoke('settings:save', { favorites: [...s] });
    renderGroupSelect();
    if (st.group === '\u0000fav') applyFilter(true); else scheduleDraw();
  }

  // ---- popover ----
  function closePop() { if (st.pop) { st.pop.remove(); st.pop = null; } }
  function openPop(i, p, anchor) {
    closePop();
    const ch = st.filtered[i];
    if (!ch) return;
    const pop = el('div', 'gd-pop'); pop.setAttribute('role', 'dialog');
    const bar = el('div', 'gd-pop-head');
    bar.append(el('div', 'gd-pop-ch', ch.name || ''));
    const x = el('button', 'gd-pop-x', '×'); x.title = 'Close (Esc)'; x.onclick = closePop; bar.append(x);
    pop.append(bar, el('h3', null, p.title || '(no title)'),
      el('div', 'gd-pop-time dim', `${fmtDay(p.start)}  ${fmtTime(p.start)} - ${fmtTime(p.stop)}  (${Math.round((p.stop - p.start) / 60000)} min)`));
    if (p.desc) pop.append(el('p', 'gd-pop-desc', p.desc));
    const play = el('button', 'primary', 'Play channel'); play.onclick = () => playRow(i);
    const acts = el('div', 'gd-pop-acts'); acts.append(play);
    if (window.NuvioRecordings) {
      const rec = el('button', null, 'Record'); rec.onclick = () => { window.NuvioRecordings.schedule(ch, p); closePop(); };
      const ser = el('button', null, 'Record series'); ser.onclick = () => { window.NuvioRecordings.scheduleSeries(ch, p.title); closePop(); };
      const rem = el('button', null, 'Remind me'); rem.onclick = () => { window.NuvioRecordings.remind(ch, p); closePop(); };
      acts.append(rec, ser, rem);
    }
    pop.append(acts);
    st.ui.root.append(pop);
    const rect = anchor.getBoundingClientRect(); const rr = st.ui.root.getBoundingClientRect();
    const w = 360;
    pop.style.left = Math.max(8, Math.min(rect.left - rr.left, rr.width - w - 8)) + 'px';
    const below = rect.bottom - rr.top + 6;
    pop.style.top = (below + 200 > rr.height ? Math.max(8, rect.top - rr.top - 206) : below) + 'px';
    st.pop = pop;
    play.focus();
  }

  // ---- loading ----
  function adoptChannels() {
    const list = window.NuvioLiveTv && window.NuvioLiveTv.channels ? window.NuvioLiveTv.channels() : [];
    if (list === st.channelsRef && list.length === st.channels.length) return;
    st.channelsRef = list;
    st.channels = list.map((c) => (c._lc ? c : { ...c, _lc: ((c.name || '') + '').toLowerCase() }));
    dropData();
    if (st.mounted) { renderGroupSelect(); applyFilter(); }
  }
  async function refreshSettings() {
    try { st.settings = await window.nuvio.invoke('settings:get'); } catch { /* keep old */ }
    if (st.mounted) { renderGroupSelect(); applyFilter(true); }
  }
  async function reloadGuide(force) {
    st.ui.refresh.disabled = true;
    st.ui.status.textContent = 'Loading guide...';
    try {
      const r = await window.nuvio.invoke('guide:load', { force: !!force });
      st.hasGuide = !!(r && r.programmes);
    } catch (e) {
      window.NuvioUI.toast('Guide: ' + e.message, 'error');
    } finally { st.ui.refresh.disabled = false; }
    dropData(); updateStatus(); scheduleDraw();
  }

  window.addEventListener('nuvio:library', () => { adoptChannels(); });
  if (window.nuvio && window.nuvio.on) {
    window.nuvio.on('guide:updated', (s) => { st.hasGuide = !!(s && s.programmes); dropData(); if (st.mounted) { updateStatus(); scheduleDraw(); } });
  }

  window.NuvioUI.registerTab({
    id: 'guide', title: 'Guide',
    mount(root) {
      root.classList.add('gd');
      const bar = el('div', 'gd-toolbar');
      const group = el('select'); group.title = 'Channel group';
      const search = el('input'); search.type = 'search'; search.placeholder = 'Filter channels ( / )'; search.autocomplete = 'off';
      const nowBtn = el('button', null, 'Now'); nowBtn.title = 'Jump to now (Home)';
      const refresh = el('button', null, 'Refresh guide'); refresh.title = 'Download the guide again';
      const status = el('div', 'gd-status dim'); status.setAttribute('role', 'status');
      bar.append(group, search, nowBtn, refresh, status);

      const sc = el('div', 'gd-scroll'); sc.tabIndex = 0; sc.setAttribute('aria-label', 'Programme guide');
      const spacer = el('div', 'gd-spacer');
      const head = el('div', 'gd-head');
      const corner = el('div', 'gd-corner', 'Channels');
      const ticks = el('div', 'gd-ticks');
      head.append(corner, ticks);
      const rows = el('div', 'gd-rows');
      const nowLine = el('div', 'gd-now');
      spacer.append(head, rows, nowLine);
      const empty = el('div', 'gd-empty dim', 'No channels yet.');
      sc.append(spacer);
      root.append(bar, sc, empty);
      Object.assign(st.ui, { root, group, search, status, sc, spacer, head, ticks, rows, now: nowLine, empty, refresh });
      st.mounted = true;

      group.onchange = () => { st.group = group.value; applyFilter(); };
      search.addEventListener('input', () => { st.query = search.value; applyFilter(); });
      nowBtn.onclick = jumpNow;
      refresh.onclick = () => reloadGuide(true);
      sc.addEventListener('scroll', () => { closePop(); scheduleDraw(); }, { passive: true });
      window.addEventListener('resize', scheduleDraw);

      sc.addEventListener('click', (e) => {
        const fav = e.target.closest('[data-fav]');
        if (fav) { toggleFav(Number(fav.dataset.fav)); return; }
        const prog = e.target.closest('.gd-prog');
        if (prog) {
          const i = Number(prog.dataset.row);
          const p = (listFor(st.filtered[i].tvgId) || []).find((x) => x.start === Number(prog.dataset.start));
          st.row = i;
          if (p) { st.ft = p.start; openPop(i, p, prog); }
          scheduleDraw(); sc.focus({ preventScroll: true });
          return;
        }
        const ch = e.target.closest('.gd-ch');
        if (ch) { st.row = Number(ch.dataset.row); scheduleDraw(); playRow(st.row); }
      });
      sc.addEventListener('dblclick', (e) => { const p = e.target.closest('.gd-prog'); if (p) playRow(Number(p.dataset.row)); });

      window.addEventListener('keydown', (e) => {
        if (window.NuvioUI.activeId() !== 'guide') return;
        const tag = e.target.tagName;
        const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
        if (e.key === 'Escape') { if (st.pop) { closePop(); sc.focus(); } else if (typing) { e.target.blur(); sc.focus(); } return; }
        if (e.key === '/' && !typing) { e.preventDefault(); search.focus(); return; }
        if (typing) { if (e.key === 'ArrowDown' && tag === 'INPUT') { e.preventDefault(); sc.focus(); } return; }
        if (st.pop && st.pop.contains(e.target)) return;
        if (e.ctrlKey || e.metaKey || e.altKey) return;
        if (tag === 'BUTTON' && (e.key === 'Enter' || e.key === ' ')) return;
        const page = Math.max(1, Math.floor((sc.clientHeight - HEAD_H) / ROW_H) - 1);
        switch (e.key) {
          case 'ArrowDown': e.preventDefault(); moveRow(1); break;
          case 'ArrowUp': e.preventDefault(); moveRow(-1); break;
          case 'PageDown': e.preventDefault(); moveRow(page); break;
          case 'PageUp': e.preventDefault(); moveRow(-page); break;
          case 'ArrowRight': e.preventDefault(); if (e.shiftKey) scrollTime(1); else moveTime(1); break;
          case 'ArrowLeft': e.preventDefault(); if (e.shiftKey) scrollTime(-1); else moveTime(-1); break;
          case 'Home': e.preventDefault(); jumpNow(); break;
          case 'Enter': e.preventDefault(); playRow(st.row); break;
          case ' ': case 'i': {
            const p = progAt(st.row, st.ft);
            const cell = p && sc.querySelector(`.gd-prog[data-row="${st.row}"][data-start="${p.start}"]`);
            if (p && cell) { e.preventDefault(); openPop(st.row, p, cell); }
            break;
          }
          case 's': { const ch = st.filtered[st.row]; if (ch) toggleFav(st.row); break; }
          default: break;
        }
      });

      setTimeline();
      st.ft = Date.now();
      renderGroupSelect();
      adoptChannels();
      applyFilter();
      setInterval(() => { if (window.NuvioUI.activeId() === 'guide') { placeNowLine(); scheduleDraw(); } }, 60000);
    },
    async onShow() {
      await refreshSettings();
      adoptChannels();
      if (Date.now() - st.stamp > 3600e3) { setTimeline(); dropData(); }
      if (!st.channels.length && !st.requested && window.NuvioLiveTv && (st.settings.playlist || st.settings.xtream)) {
        st.requested = true;
        window.NuvioLiveTv.reload();
      }
      if (st.hasGuide === null) {
        st.hasGuide = undefined;
        reloadGuide(false);
      }
      if (!st.shown) { st.shown = true; st.ft = Date.now(); scrollToTime(st.ft); }
      scheduleDraw();
      st.ui.sc.focus({ preventScroll: true });
    },
  });
})();
