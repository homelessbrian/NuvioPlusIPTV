'use strict';
// Live TV tab: groups, favorites, recents, hide/show, search, now/next, playback via NuvioPlayer.
(() => {
  const ROW_H = 56;
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const st = { channels: [], groups: [], group: 'All', query: '', filtered: [], sel: 0, playing: null, guide: new Map(), settings: null, showHidden: false, loaded: false, ui: {} };
  const favs = () => new Set(st.settings.favorites || []);
  const hidden = () => new Set(st.settings.hidden || []);
  const keyOf = (c) => c.url; // stable across reloads; ids are positional

  async function persist(patch) { st.settings = await window.nuvio.invoke('settings:save', patch); }

  function applyFilter() {
    const q = st.query.trim().toLowerCase();
    const f = favs(); const h = hidden();
    let recent = null;
    if (st.group === 'Recent') { recent = new Map((st.settings.recents || []).map((u, i) => [u, i])); }
    let list = st.channels.filter((c) => {
      if (!st.showHidden && h.has(keyOf(c)) && st.group !== 'Hidden') return false;
      if (st.group === 'Favorites') return f.has(keyOf(c)) && (!q || c._lc.includes(q));
      if (st.group === 'Recent') return recent.has(keyOf(c)) && (!q || c._lc.includes(q));
      if (st.group === 'Hidden') return h.has(keyOf(c)) && (!q || c._lc.includes(q));
      return (st.group === 'All' || c.group === st.group) && (!q || c._lc.includes(q));
    });
    if (recent) list = list.sort((a, b) => recent.get(keyOf(a)) - recent.get(keyOf(b)));
    st.filtered = list;
    st.sel = Math.min(st.sel, Math.max(0, list.length - 1));
    st.ui.spacer.style.height = list.length * ROW_H + 'px';
    st.ui.list.scrollTop = 0;
    renderRows();
    setStatus(st.channels.length ? `${list.length} of ${st.channels.length} channels` + (h.size ? ` · ${h.size} hidden` : '') : '');
  }

  function setStatus(text, isError) { st.ui.status.textContent = text || ''; st.ui.status.className = 'lt-status' + (isError ? ' error' : ''); }

  let queued = false;
  function renderRows() { if (queued) return; queued = true; requestAnimationFrame(() => { queued = false; drawRows(); }); }

  function drawRows() {
    const { list, rows } = st.ui;
    const start = Math.max(0, Math.floor(list.scrollTop / ROW_H) - 4);
    const end = Math.min(st.filtered.length, start + Math.ceil(list.clientHeight / ROW_H) + 8);
    rows.style.top = start * ROW_H + 'px';
    rows.replaceChildren();
    const f = favs(); const ids = [];
    for (let i = start; i < end; i++) {
      const ch = st.filtered[i];
      const row = el('div', 'row-ch' + (i === st.sel ? ' sel' : '') + (st.playing && st.playing.url === ch.url ? ' playing' : ''));
      row.dataset.index = String(i);
      if (ch.logo && /^https?:/i.test(ch.logo)) {
        const img = el('img'); img.src = ch.logo; img.loading = 'lazy'; img.alt = ''; img.onerror = () => img.replaceWith(el('div', 'logo-ph')); row.append(img);
      } else row.append(el('div', 'logo-ph'));
      const txt = el('div', 'txt');
      txt.append(el('div', 'name', ch.name || '(unnamed)'));
      const g = ch.tvgId && st.guide.get(ch.tvgId);
      txt.append(el('div', 'sub', g && g.now ? `${g.now.title} · until ${fmtTime(g.now.stop)}` : ch.group));
      const star = el('button', 'fav' + (f.has(keyOf(ch)) ? ' on' : ''), f.has(keyOf(ch)) ? '★' : '☆');
      star.title = 'Favorite (S)'; star.dataset.fav = String(i);
      row.append(txt, star);
      rows.append(row);
      if (ch.tvgId) ids.push(ch.tvgId);
    }
    wantGuide(ids);
  }

  let gTimer = null;
  function wantGuide(ids) {
    if (!ids.length) return;
    clearTimeout(gTimer);
    gTimer = setTimeout(async () => {
      const stale = ids.filter((id) => { const g = st.guide.get(id); return !g || Date.now() - g.at > 60000; });
      if (!stale.length) return;
      const res = await window.nuvio.invoke('guide:nowNext', stale).catch(() => ({}));
      for (const id of stale) st.guide.set(id, { ...(res[id] || {}), at: Date.now() });
      renderRows();
    }, 150);
  }

  function renderGroups() {
    const box = st.ui.groups; box.replaceChildren();
    const nFav = favs().size; const nHid = hidden().size;
    const all = [{ name: 'All', count: st.channels.length }, { name: 'Favorites', count: nFav }, { name: 'Recent', count: (st.settings.recents || []).length }];
    if (nHid) all.push({ name: 'Hidden', count: nHid });
    for (const g of [...all, ...st.groups]) {
      const b = el('button', g.name === st.group ? 'active' : '');
      b.append(el('span', null, g.name), el('span', 'count', String(g.count)));
      b.onclick = () => { st.group = g.name; renderGroups(); applyFilter(); };
      box.append(b);
    }
  }

  async function play(ch) {
    st.playing = ch;
    const recents = [keyOf(ch), ...(st.settings.recents || []).filter((u) => u !== keyOf(ch))].slice(0, 50);
    await persist({ lastChannelUrl: ch.url, recents });
    window.NuvioPlayer.play(ch);
    renderRows();
  }

  function move(delta) {
    if (!st.filtered.length) return;
    st.sel = Math.min(st.filtered.length - 1, Math.max(0, st.sel + delta));
    const { list } = st.ui; const top = st.sel * ROW_H;
    if (top < list.scrollTop) list.scrollTop = top; else if (top + ROW_H > list.scrollTop + list.clientHeight) list.scrollTop = top + ROW_H - list.clientHeight;
    renderRows();
  }
  function step(delta) {
    if (!st.filtered.length) return;
    const cur = st.playing ? st.filtered.findIndex((c) => c.url === st.playing.url) : st.sel;
    st.sel = (Math.max(cur, 0) + delta + st.filtered.length) % st.filtered.length;
    move(0); play(st.filtered[st.sel]);
  }
  async function toggle(listName, ch) {
    const s = new Set(st.settings[listName] || []); const k = keyOf(ch);
    if (s.has(k)) s.delete(k); else s.add(k);
    await persist({ [listName]: [...s] });
    renderGroups(); if (listName === 'hidden' || st.group === 'Favorites') applyFilter(); else renderRows();
  }

  async function load() {
    st.settings = await window.nuvio.invoke('settings:get');
    if (!st.settings.playlist && !st.settings.xtream) { setStatus('Add a playlist or Xtream login in Settings to get started.'); return; }
    setStatus('Loading playlist…');
    const r = await window.nuvio.invoke('library:load');
    st.channels = r.channels.map((c) => ({ ...c, _lc: (c.name + ' ' + c.group).toLowerCase() }));
    st.groups = r.groups; st.group = 'All'; st.query = ''; st.ui.search.value = ''; st.guide.clear(); st.loaded = true;
    renderGroups(); applyFilter();
    if (r.errors.length) setStatus(r.errors.join('  ·  '), true);
    window.nuvio.invoke('guide:load').then((g) => {
      if (g && g.channels) setStatus(`${st.channels.length} channels · guide for ${g.channels} channels`);
      st.guide.clear(); renderRows();
    }).catch((e) => window.NuvioUI.toast('Guide: ' + e.message, 'error'));
    window.dispatchEvent(new CustomEvent('nuvio:library', { detail: { channels: st.channels } }));
  }

  window.NuvioLiveTv = {
    reload: load,
    channels: () => st.channels,
    playByUrl(url) { const c = st.channels.find((x) => x.url === url); if (c) play(c); return !!c; },
  };

  window.NuvioUI.registerTab({
    id: 'livetv', title: 'Live TV',
    mount(root) {
      root.classList.add('lt');
      const groups = el('div', 'lt-groups');
      const main = el('div', 'lt-main');
      const bar = el('div', 'lt-toolbar');
      const search = el('input'); search.type = 'search'; search.placeholder = 'Search channels ( / )'; search.autocomplete = 'off';
      const hid = el('button', null, 'Show hidden'); hid.onclick = () => { st.showHidden = !st.showHidden; hid.textContent = st.showHidden ? 'Hide hidden' : 'Show hidden'; applyFilter(); };
      const refresh = el('button', null, 'Reload'); refresh.onclick = load;
      bar.append(search, hid, refresh);
      const status = el('div', 'lt-status'); status.setAttribute('role', 'status');
      const list = el('div', 'lt-list'); list.tabIndex = 0;
      const spacer = el('div', 'lt-spacer'); const rows = el('div', 'lt-rows'); spacer.append(rows); list.append(spacer);
      main.append(bar, status, list); root.append(groups, main);
      Object.assign(st.ui, { groups, search, status, list, spacer, rows });

      list.addEventListener('scroll', renderRows);
      window.addEventListener('resize', renderRows);
      list.addEventListener('click', (e) => {
        const fav = e.target.closest('[data-fav]');
        if (fav) { toggle('favorites', st.filtered[Number(fav.dataset.fav)]); return; }
        const row = e.target.closest('.row-ch'); if (!row) return;
        st.sel = Number(row.dataset.index); play(st.filtered[st.sel]);
      });
      search.addEventListener('input', (e) => { st.query = e.target.value; applyFilter(); });
      // Fullscreen channel up/down from the player overlay.
      window.addEventListener('nuvio:channelstep', (e) => { if (st.filtered.length) step(e.detail && e.detail.delta === -1 ? -1 : 1); });
      window.addEventListener('keydown', (e) => {
        if (window.NuvioUI.activeId() !== 'livetv') return;
        const typing = e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA';
        if (e.key === '/' && !typing) { e.preventDefault(); search.focus(); return; }
        if (e.key === 'Escape') { if (typing) { e.target.blur(); list.focus(); } return; }
        if (typing) { if (e.key === 'ArrowDown') { e.preventDefault(); list.focus(); } return; }
        const cur = st.filtered[st.sel];
        if (e.key === 'ArrowDown') { e.preventDefault(); move(1); } else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
        else if (e.key === 'PageDown') { e.preventDefault(); move(10); } else if (e.key === 'PageUp') { e.preventDefault(); move(-10); }
        else if (e.key === 'Enter') { if (cur) play(cur); }
        else if (e.key === ']') step(1); else if (e.key === '[') step(-1);
        else if (e.key.toLowerCase() === 's' && cur) toggle('favorites', cur);
        else if (e.key.toLowerCase() === 'h' && cur) toggle('hidden', cur);
        else if (e.key.toLowerCase() === 'r' && cur && window.NuvioRecordings) window.NuvioRecordings.recordNow(cur);
      });
      setStatus('Loading…');
    },
    async onShow() {
      if (!st.loaded) { load(); return; }
      // Favorites may have been changed from the Guide tab.
      st.settings = await window.nuvio.invoke('settings:get');
      renderGroups(); applyFilter();
    },
  });
})();
