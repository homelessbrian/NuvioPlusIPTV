'use strict';
// On Demand tab: Xtream movies and series. Virtualised poster grid, categories, search, detail panel, season/episode picker.
(() => {
  const CARD_W = 148; const CARD_H = 258; const GAP = 12; const PAD = 14;
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const invoke = (ch, ...a) => window.nuvio.invoke(ch, ...a);
  const lsGet = (k) => { try { return localStorage.getItem(k); } catch { return null; } };
  const lsSet = (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };
  const cleanErr = (e) => String((e && e.message) || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');

  const st = {
    kind: lsGet('nuvio.od.kind') === 'series' ? 'series' : 'movies',
    cats: { movies: null, series: null }, cat: { movies: lsGet('nuvio.od.cat.movies') || 'all', series: lsGet('nuvio.od.cat.series') || 'all' },
    items: [], query: '', searchResult: null, loadSeq: 0, sig: null, status: null, ui: {}, detail: null, cards: new Map(), playing: null,
  };

  const initials = (name) => (String(name || '?').replace(/[^\p{L}\p{N} ]/gu, '').trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('') || '?').toUpperCase();

  function poster(url, name, cls) {
    const box = el('div', cls || 'od-poster');
    const fb = el('span', 'od-fallback', initials(name));
    box.append(fb);
    if (url && /^https?:/i.test(url)) {
      const img = new Image();
      img.loading = 'lazy'; img.decoding = 'async'; img.alt = ''; img.referrerPolicy = 'no-referrer';
      img.onload = () => { fb.remove(); box.append(img); };
      img.onerror = () => { img.remove(); };
      img.src = url;
    }
    return box;
  }

  const stars = (r) => (r > 0 ? '★ ' + (Math.round(r * 20) / 10).toFixed(1) : '');

  // ---------- shell ----------
  function mount(root) {
    root.classList.add('od-root');
    const bar = el('div', 'od-bar');
    const toggle = el('div', 'od-toggle'); toggle.setAttribute('role', 'tablist');
    const btns = {};
    for (const [k, label] of [['movies', 'Movies'], ['series', 'Series']]) {
      const b = el('button', 'od-kind', label); b.setAttribute('role', 'tab');
      b.onclick = () => setKind(k);
      btns[k] = b; toggle.append(b);
    }
    const cat = el('select', 'od-cat'); cat.setAttribute('aria-label', 'Category');
    cat.onchange = () => { st.cat[st.kind] = cat.value; lsSet('nuvio.od.cat.' + st.kind, cat.value); loadItems(); };
    const search = el('input', 'od-search'); search.type = 'search'; search.placeholder = 'Search movies and series'; search.setAttribute('aria-label', 'Search');
    let timer = 0;
    search.oninput = () => { clearTimeout(timer); timer = setTimeout(() => { st.query = search.value.trim(); loadItems(); }, 250); };
    const count = el('span', 'od-count dim');
    bar.append(toggle, cat, search, count);

    const body = el('div', 'od-body');
    const scroller = el('div', 'od-scroll'); scroller.tabIndex = -1;
    const spacer = el('div', 'od-spacer'); scroller.append(spacer);
    const msg = el('div', 'od-msg'); msg.hidden = true;
    const detail = el('aside', 'od-detail'); detail.hidden = true;
    body.append(scroller, msg, detail);
    root.append(bar, body);
    st.ui = { root, bar, btns, cat, search, count, scroller, spacer, msg, detail };
    scroller.addEventListener('scroll', () => draw(), { passive: true });
    new ResizeObserver(() => draw(true)).observe(scroller);
    root.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !st.ui.detail.hidden) { closeDetail(); } });
    syncKindButtons();
  }

  function syncKindButtons() {
    for (const [k, b] of Object.entries(st.ui.btns)) { b.classList.toggle('active', k === st.kind); b.setAttribute('aria-selected', String(k === st.kind)); }
  }

  function showMessage(title, text, actions) {
    const m = st.ui.msg; m.replaceChildren();
    m.append(el('h2', null, title));
    if (text) m.append(el('p', 'dim', text));
    const row = el('div', 'od-msg-actions');
    for (const a of actions || []) { const b = el('button', a.primary ? 'primary' : '', a.label); b.onclick = a.run; row.append(b); }
    m.append(row);
    m.hidden = false; st.ui.scroller.hidden = true; st.ui.bar.classList.add('disabled');
  }
  function hideMessage() { st.ui.msg.hidden = true; st.ui.scroller.hidden = false; st.ui.bar.classList.remove('disabled'); }

  const goSettings = () => window.NuvioUI.select('settings');

  // ---------- data ----------
  async function activate() {
    const seq = ++st.loadSeq;
    let status;
    try { status = await invoke('ondemand:status'); } catch (e) { status = { configured: true, ok: false, message: cleanErr(e) }; }
    if (seq !== st.loadSeq) return;
    st.status = status;
    if (!status.configured) {
      showMessage('On Demand needs an Xtream login', 'Add your provider’s server, username and password in Settings to browse movies and series.', [{ label: 'Open Settings', primary: true, run: goSettings }]);
      return;
    }
    if (!status.ok) {
      showMessage('Can’t open your provider', status.message || 'The provider did not accept the login.', [
        { label: 'Try again', primary: true, run: () => { invoke('ondemand:refresh').catch(() => {}); activate(); } }, { label: 'Open Settings', run: goSettings }]);
      return;
    }
    hideMessage();
    await loadCategories();
    await loadItems();
  }

  async function loadCategories() {
    const kind = st.kind;
    if (!st.cats[kind]) {
      try { st.cats[kind] = await invoke('ondemand:categories', kind); } catch (e) { showMessage('Couldn’t load categories', cleanErr(e), [{ label: 'Try again', primary: true, run: activate }, { label: 'Open Settings', run: goSettings }]); st.cats[kind] = null; return; }
    }
    if (kind !== st.kind) return;
    const sel = st.ui.cat; sel.replaceChildren();
    const total = st.cats[kind].reduce((n, c) => n + c.count, 0);
    const add = (value, label) => { const o = el('option', null, label); o.value = value; sel.append(o); };
    add('all', `All (${total})`);
    for (const c of st.cats[kind]) add(c.id, `${c.name} (${c.count})`);
    if (![...sel.options].some((o) => o.value === st.cat[kind])) st.cat[kind] = 'all';
    sel.value = st.cat[kind];
  }

  async function loadItems() {
    const seq = ++st.loadSeq; const kind = st.kind;
    closeDetail();
    try {
      let items;
      if (st.query.length >= 2) {
        const r = await invoke('ondemand:search', st.query);
        if (seq !== st.loadSeq) return;
        st.searchResult = r;
        items = r[kind];
        const other = kind === 'movies' ? r.series : r.movies;
        st.ui.count.textContent = `${items.length}${r.truncated ? '+' : ''} found` + (other.length ? ` · ${other.length} in ${kind === 'movies' ? 'Series' : 'Movies'}` : '');
      } else {
        const r = await invoke(kind === 'series' ? 'ondemand:series' : 'ondemand:movies', st.cat[kind]);
        if (seq !== st.loadSeq) return;
        items = r.items;
        st.ui.count.textContent = `${r.total} titles`;
      }
      st.items = items;
      st.ui.cat.disabled = st.query.length >= 2;
      hideMessage();
      st.ui.scroller.scrollTop = 0;
      for (const c of st.cards.values()) c.remove();
      st.cards.clear();
      if (!items.length) {
        st.ui.spacer.style.height = '0px';
        showMessage(st.query ? 'No matches' : 'Nothing here', st.query ? `Nothing in ${kind} matches “${st.query}”.` : 'This category is empty.', []);
        st.ui.scroller.hidden = false; st.ui.bar.classList.remove('disabled');
        return;
      }
      draw(true);
    } catch (e) {
      if (seq !== st.loadSeq) return;
      showMessage('Couldn’t load the list', cleanErr(e), [{ label: 'Try again', primary: true, run: () => { invoke('ondemand:refresh').catch(() => {}); st.cats = { movies: null, series: null }; activate(); } }, { label: 'Open Settings', run: goSettings }]);
    }
  }

  function setKind(k) {
    if (k === st.kind) return;
    st.kind = k; lsSet('nuvio.od.kind', k); syncKindButtons();
    st.ui.search.placeholder = 'Search movies and series';
    if (st.status && st.status.ok) loadCategories().then(loadItems); else activate();
  }

  // ---------- virtual grid ----------
  let lastCols = 0;
  function layout() {
    const w = st.ui.scroller.clientWidth - PAD * 2;
    const cols = Math.max(1, Math.floor((w + GAP) / (CARD_W + GAP)));
    const slack = (w - (cols * CARD_W + (cols - 1) * GAP)) / 2;
    return { cols, left: PAD + Math.max(0, slack) };
  }

  function draw(force) {
    const sc = st.ui.scroller;
    if (!st.items.length || sc.hidden) return;
    const { cols, left } = layout();
    if (force || cols !== lastCols) { for (const c of st.cards.values()) c.remove(); st.cards.clear(); lastCols = cols; }
    const rowH = CARD_H + GAP;
    const rows = Math.ceil(st.items.length / cols);
    st.ui.spacer.style.height = rows * rowH + PAD * 2 + 'px';
    const first = Math.max(0, Math.floor((sc.scrollTop - PAD) / rowH) - 1);
    const last = Math.min(rows - 1, Math.ceil((sc.scrollTop + sc.clientHeight) / rowH) + 1);
    const from = first * cols; const to = Math.min(st.items.length, (last + 1) * cols);
    for (const [i, node] of st.cards) if (i < from || i >= to) { node.remove(); st.cards.delete(i); }
    for (let i = from; i < to; i++) {
      if (st.cards.has(i)) continue;
      const node = card(st.items[i], i);
      node.style.transform = `translate(${left + (i % cols) * (CARD_W + GAP)}px, ${PAD + Math.floor(i / cols) * rowH}px)`;
      st.ui.spacer.append(node); st.cards.set(i, node);
    }
  }

  function card(item, index) {
    const b = el('button', 'od-card'); b.type = 'button'; b.title = item.name;
    b.append(poster(item.poster, item.name));
    b.append(el('div', 'od-title', item.name));
    const meta = [item.year || '', stars(item.rating)].filter(Boolean).join('  ·  ');
    b.append(el('div', 'od-meta dim', meta || ' '));
    b.onclick = () => openDetail(item, index);
    return b;
  }

  // ---------- detail ----------
  function closeDetail() { st.detail = null; st.ui.detail.hidden = true; st.ui.detail.replaceChildren(); }

  async function openDetail(item) {
    const kind = st.kind;
    const d = st.ui.detail; d.replaceChildren(); d.hidden = false;
    const token = st.detail = { id: item.id, kind };
    const close = el('button', 'od-close', '×'); close.title = 'Close'; close.setAttribute('aria-label', 'Close'); close.onclick = closeDetail;
    const head = el('div', 'od-d-head');
    head.append(poster(item.poster, item.name, 'od-d-poster'));
    const hinfo = el('div', 'od-d-info');
    const title = el('h2', 'od-d-title', item.name);
    const facts = el('div', 'od-d-facts dim', [item.year || '', stars(item.rating)].filter(Boolean).join('  ·  '));
    const playBtn = el('button', 'primary od-play', 'Play'); playBtn.disabled = true;
    hinfo.append(title, facts, playBtn);
    head.append(hinfo);
    const plot = el('p', 'od-d-plot dim', 'Loading details…');
    const extra = el('div', 'od-d-extra');
    const eps = el('div', 'od-eps');
    d.append(close, head, plot, extra, eps);
    close.focus();
    try {
      const info = await invoke('ondemand:info', kind, item.id);
      if (st.detail !== token) return;
      title.textContent = info.name || item.name;
      facts.textContent = [info.year || item.year || '', stars(info.rating || item.rating), info.genre, durationLabel(info)].filter(Boolean).join('  ·  ');
      plot.textContent = info.plot || 'No description available.';
      for (const [label, value] of [['Cast', info.cast], ['Director', info.director]]) {
        if (!value) continue;
        const row = el('div', 'od-d-row'); row.append(el('span', 'dim', label), el('span', null, value)); extra.append(row);
      }
      if (info.poster && !item.poster) { const np = poster(info.poster, item.name, 'od-d-poster'); head.firstChild.replaceWith(np); }
      if (kind === 'series') renderEpisodes(item, info, eps, playBtn, token);
      else { playBtn.disabled = false; playBtn.onclick = () => play(playBtn, () => invoke('ondemand:playUrl', 'movie', item.id)); }
    } catch (e) {
      if (st.detail !== token) return;
      plot.textContent = 'Couldn’t load details: ' + cleanErr(e);
      if (kind !== 'series') { playBtn.disabled = false; playBtn.onclick = () => play(playBtn, () => invoke('ondemand:playUrl', 'movie', item.id)); }
    }
  }

  function durationLabel(info) {
    const secs = info.durationSecs || 0;
    if (secs > 0) { const m = Math.round(secs / 60); return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`; }
    return info.duration && /^\d+$/.test(info.duration) ? info.duration + 'm' : (info.duration || '');
  }

  function renderEpisodes(item, info, box, playBtn, token) {
    if (!info.seasons.length) { box.append(el('p', 'dim', 'No episodes are listed for this series.')); return; }
    const sel = el('select', 'od-season'); sel.setAttribute('aria-label', 'Season');
    info.seasons.forEach((s, i) => { const o = el('option', null, `${s.name} (${s.episodes.length})`); o.value = String(i); sel.append(o); });
    const list = el('div', 'od-ep-list');
    const render = () => {
      list.replaceChildren();
      const s = info.seasons[Number(sel.value)];
      for (const e of s.episodes) {
        const row = el('button', 'od-ep'); row.type = 'button';
        row.append(el('span', 'od-ep-n', String(e.episode || '')), el('span', 'od-ep-t', e.title));
        const dur = e.durationSecs ? Math.round(e.durationSecs / 60) + 'm' : e.duration;
        if (dur) row.append(el('span', 'od-ep-d dim', dur));
        row.onclick = () => play(row, () => invoke('ondemand:playUrl', 'series', item.id, e.id, e.ext), `${info.name || item.name} S${s.number}E${e.episode}`);
        list.append(row);
      }
    };
    sel.onchange = render; render();
    box.append(sel, list);
    const firstSeason = info.seasons.find((s) => s.episodes.length) || info.seasons[0];
    const firstEp = firstSeason.episodes[0];
    if (firstEp) {
      playBtn.disabled = false; playBtn.textContent = `Play S${firstSeason.number}E${firstEp.episode}`;
      playBtn.onclick = () => play(playBtn, () => invoke('ondemand:playUrl', 'series', item.id, firstEp.id, firstEp.ext), `${info.name || item.name} S${firstSeason.number}E${firstEp.episode}`);
    }
    void token;
  }

  async function play(btn, resolve, nameOverride) {
    if (btn.disabled && btn.classList.contains('od-play')) return;
    btn.classList.add('busy');
    try {
      const r = await resolve();
      if (!window.NuvioPlayer) throw new Error('The player is not available.');
      await window.NuvioPlayer.play({ name: nameOverride || r.name, url: r.url });
    } catch (e) {
      window.NuvioUI.toast(cleanErr(e), 'error');
    } finally { btn.classList.remove('busy'); }
  }

  // ---------- registration ----------
  const sigOf = (s) => JSON.stringify(s && s.xtream ? [s.xtream.server, s.xtream.username, s.xtream.password] : null);

  async function onShow() {
    let s = null;
    try { s = await invoke('settings:get'); } catch { /* ignore */ }
    const sig = sigOf(s);
    if (sig !== st.sig) {
      st.sig = sig; st.cats = { movies: null, series: null }; st.items = [];
      for (const c of st.cards.values()) c.remove();
      st.cards.clear(); closeDetail();
      await activate();
    } else if (st.status && st.status.ok) draw(true);
    else if (!st.status) await activate();
  }

  window.NuvioUI.registerTab({ id: 'ondemand', title: 'On Demand', mount, onShow });
})();
