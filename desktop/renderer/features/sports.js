'use strict';
// Sports tab: My Sports (live + upcoming + a row per followed team/league/name) and All Events (day tabs, league filter),
// game drawer with "Channels showing this game", follow buttons, Hide Scores. Lists are windowed (virtualised).
(() => {
  const H_HEAD = 38; const H_GAME = 78; const H_STRIP = 150; const H_NOTE = 64;
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const invoke = (...a) => window.nuvio.invoke(...a);
  const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const fmtDay = (ms) => new Date(ms).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
  const isHttp = (u) => typeof u === 'string' && /^https?:/i.test(u);
  const hex = (n) => (typeof n === 'number' ? '#' + (n & 0xffffff).toString(16).padStart(6, '0') : null);

  const st = {
    view: 'mine', day: 0, league: '', events: [], mine: null, loading: false, settings: { hideScores: false }, leagues: [],
    items: [], offsets: [], total: 0, openId: null, drawerSeq: 0, ui: {}, visible: false, timer: null, seq: 0, panel: null,
  };

  // ------------------------------------------------------------------------------------------------ data
  async function load() {
    const seq = ++st.seq;
    setStatus('Loading…');
    try {
      if (st.view === 'mine') {
        const r = await invoke('sports:mine');
        if (seq !== st.seq) return;
        st.mine = r; st.loading = r.loading;
      } else {
        const r = await invoke('sports:events', st.day, {});
        if (seq !== st.seq) return;
        st.events = r.events; st.loading = r.loading;
      }
    } catch (e) {
      if (seq === st.seq) setStatus('Could not load sports: ' + (e && e.message ? e.message : e), true);
      return;
    }
    build();
    refreshDrawerEvent();
    const n = st.view === 'mine' ? (st.mine.live.length + st.mine.upcoming.length) : st.events.length;
    setStatus(st.loading ? 'Loading more leagues…' : st.view === 'mine' ? `${n} live or upcoming games` : `${st.events.length} events`);
  }
  function setStatus(t, err) { if (st.ui.status) { st.ui.status.textContent = t || ''; st.ui.status.classList.toggle('error', !!err); } }
  const allVisibleEvents = () => (st.view === 'mine' && st.mine ? [...st.mine.live, ...st.mine.upcoming, ...st.mine.rows.flatMap((r) => r.events)] : st.events);

  // ------------------------------------------------------------------------------------------------ list building
  function build() {
    const items = [];
    if (st.view === 'mine') {
      const m = st.mine;
      if (!m) return;
      if (m.live.length) { items.push({ t: 'h', text: `Live now (${m.live.length})`, live: true, h: H_HEAD }); for (const ev of m.live) items.push({ t: 'g', ev, h: H_GAME }); }
      if (m.upcoming.length) { items.push({ t: 'h', text: 'Upcoming', h: H_HEAD }); for (const ev of m.upcoming) items.push({ t: 'g', ev, h: H_GAME }); }
      if (!m.rows.length) items.push({ t: 'note', text: 'Follow teams, leagues or names to see them here. Open any game in All Events and press Follow, or use Leagues.', h: H_NOTE });
      else if (!m.live.length && !m.upcoming.length && !m.loading) items.push({ t: 'note', text: 'Nothing live or coming up for what you follow.', h: H_NOTE });
      for (const row of m.rows) { items.push({ t: 'rowh', row, h: H_HEAD }); items.push({ t: 'strip', row, h: row.events.length ? H_STRIP : 44 }); }
    } else {
      const list = st.league ? st.events.filter((e) => e.leagueId === st.league) : st.events;
      const groups = [['Live now', list.filter((e) => e.state === 'live'), true], ['Upcoming', list.filter((e) => e.state === 'pre'), false], ['Finished', list.filter((e) => e.state === 'post'), false]];
      for (const [name, evs, live] of groups) {
        if (!evs.length) continue;
        items.push({ t: 'h', text: `${name} (${evs.length})`, live, h: H_HEAD });
        for (const ev of evs) items.push({ t: 'g', ev, h: H_GAME });
      }
      if (!list.length) items.push({ t: 'note', text: st.loading ? 'Loading games…' : 'No games on this day for the selected leagues.', h: H_NOTE });
    }
    st.items = items;
    let y = 0; st.offsets = items.map((i) => { const o = y; y += i.h; return o; }); st.total = y;
    st.ui.spacer.style.height = st.total + 'px';
    updateLeagueFilter();
    draw();
  }

  let queued = false;
  const redraw = () => { if (queued) return; queued = true; requestAnimationFrame(() => { queued = false; draw(); }); };

  function draw() {
    const { list, rows } = st.ui;
    const top = list.scrollTop; const bottom = top + list.clientHeight;
    // binary search for the first visible item
    let lo = 0; let hi = st.items.length - 1; let first = 0;
    while (lo <= hi) { const mid = (lo + hi) >> 1; if (st.offsets[mid] + st.items[mid].h < top - 200) lo = mid + 1; else { first = mid; hi = mid - 1; } }
    rows.replaceChildren();
    for (let i = first; i < st.items.length && st.offsets[i] < bottom + 200; i++) {
      const it = st.items[i];
      const node = renderItem(it);
      node.style.top = st.offsets[i] + 'px';
      node.style.height = it.h + 'px';
      rows.append(node);
    }
  }

  function renderItem(it) {
    if (it.t === 'h') return el('div', 'sp-head abs' + (it.live ? ' live' : ''), it.text);
    if (it.t === 'note') return el('div', 'sp-note abs', it.text);
    if (it.t === 'g') return gameRow(it.ev);
    if (it.t === 'rowh') {
      const h = el('div', 'sp-head abs row');
      const left = el('span', 'sp-rowtitle');
      if (isHttp(it.row.logo)) left.append(logo(it.row.logo, 'sp-rowlogo'));
      left.append(el('span', null, it.row.name), el('span', 'sp-kind', it.row.kind === 'team' ? 'Team' : it.row.kind === 'league' ? 'League' : 'Name'));
      const un = el('button', 'sp-mini', 'Unfollow');
      un.onclick = () => unfollowRow(it.row);
      h.append(left, un);
      return h;
    }
    // strip of compact cards
    const s = el('div', 'sp-strip abs');
    if (!it.row.events.length) s.append(el('div', 'sp-dim', 'No recent or upcoming games found.'));
    for (const ev of it.row.events) s.append(card(ev));
    return s;
  }

  function logo(src, cls) {
    const img = el('img', cls || 'sp-logo');
    img.src = src; img.loading = 'lazy'; img.alt = '';
    img.onerror = () => img.classList.add('missing');
    return img;
  }
  function teamBox(side, cls) {
    const b = el('div', cls || 'sp-team');
    if (side.team.logo && isHttp(side.team.logo)) b.append(logo(side.team.logo)); else b.append(el('div', 'sp-logo ph', (side.team.abbr || side.team.name || '?').slice(0, 3)));
    b.append(el('span', 'sp-tname' + (side.winner ? ' win' : ''), side.team.name));
    if (side.score != null && side.score !== '') b.append(el('span', 'sp-score' + (side.winner ? ' win' : ''), side.score));
    return b;
  }
  function badge(ev) {
    if (ev.state === 'live') return el('span', 'sp-badge live', 'LIVE');
    if (ev.state === 'post') return el('span', 'sp-badge post', 'FINAL');
    return null;
  }
  function whenText(ev) {
    if (ev.state === 'live') return ev.status || 'In progress';
    if (ev.state === 'post') return ev.hasTime ? fmtDay(ev.startMs) : (ev.note || 'Finished');
    if (!ev.hasTime) return ev.note || fmtDay(ev.startMs);
    return `${fmtDay(ev.startMs)} · ${fmtTime(ev.startMs)}`;
  }
  function leagueTag(ev) {
    const t = el('span', 'sp-league', ev.leagueShort || ev.leagueName);
    const c = hex(ev.leagueColor); if (c) t.style.setProperty('--lc', c);
    return t;
  }

  function gameRow(ev) {
    const r = el('div', 'sp-game abs' + (ev.state === 'live' ? ' is-live' : '') + (ev.followed ? ' followed' : '') + (st.openId === ev.id ? ' sel' : ''));
    r.tabIndex = 0; r.setAttribute('role', 'button'); r.dataset.id = ev.id;
    const when = el('div', 'sp-when');
    const b = badge(ev);
    if (b) when.append(b);
    when.append(el('div', 'sp-whentext', whenText(ev)));
    const mid = el('div', 'sp-mid');
    if (ev.away && ev.home) {
      const order = ev.sport === 'FOOTBALL' || ev.sport === 'RUGBY' || ev.sport === 'CRICKET' || ev.sport === 'AUSSIE_RULES' ? [ev.home, ev.away] : [ev.away, ev.home];
      mid.append(teamBox(order[0]), teamBox(order[1]));
    } else {
      mid.append(el('div', 'sp-eventtitle', ev.title));
      if (ev.subtitle) mid.append(el('div', 'sp-dim sp-sub', ev.subtitle));
    }
    const right = el('div', 'sp-right');
    right.append(leagueTag(ev));
    if (ev.broadcasts && ev.broadcasts.length) right.append(el('span', 'sp-net', ev.broadcasts.slice(0, 2).join(' · ')));
    r.append(when, mid, right);
    r.onclick = () => openDrawer(ev);
    r.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDrawer(ev); } };
    r.style.position = 'absolute';
    return r;
  }

  function card(ev) {
    const c = el('div', 'sp-card' + (ev.state === 'live' ? ' is-live' : ''));
    c.tabIndex = 0; c.setAttribute('role', 'button');
    const head = el('div', 'sp-cardhead');
    head.append(leagueTag(ev), el('span', 'sp-dim', whenText(ev)));
    c.append(head);
    if (ev.away && ev.home) { c.append(teamBox(ev.away, 'sp-team sm'), teamBox(ev.home, 'sp-team sm')); }
    else c.append(el('div', 'sp-eventtitle', ev.title));
    c.onclick = () => openDrawer(ev);
    c.onkeydown = (e) => { if (e.key === 'Enter') openDrawer(ev); };
    return c;
  }

  // ------------------------------------------------------------------------------------------------ follow actions
  async function unfollowRow(row) {
    if (row.kind === 'team') await invoke('sports:unfollow', { kind: 'team', leagueId: row.leagueId, teamId: row.teamId });
    else if (row.kind === 'league') await invoke('sports:unfollow', { kind: 'league', id: row.leagueId });
    else await invoke('sports:unfollow', { kind: 'name', word: row.word });
    load();
  }

  // ------------------------------------------------------------------------------------------------ drawer
  function findById(id) { return allVisibleEvents().find((e) => e.id === id) || null; }
  function refreshDrawerEvent() {
    if (!st.openId) return;
    const ev = findById(st.openId);
    if (ev) renderDrawerBody(ev, true);
  }

  function openDrawer(ev) {
    st.openId = ev.id;
    st.ui.drawer.hidden = false;
    renderDrawerBody(ev, false);
    redraw();
  }
  function closeDrawer() { st.openId = null; st.ui.drawer.hidden = true; st.drawerSeq++; redraw(); }

  function followBtn(label, on, onClick) {
    const b = el('button', 'sp-follow' + (on ? ' on' : ''), (on ? '✓ ' : '+ ') + label);
    b.onclick = async () => { b.disabled = true; try { await onClick(on); } finally { b.disabled = false; } };
    return b;
  }

  async function renderDrawerBody(ev, soft) {
    const d = st.ui.drawerBody;
    const prevChannels = soft ? d.querySelector('.sp-channels') : null;
    const keepKey = prevChannels && prevChannels.dataset.sig;
    const sigNow = `${ev.id}|${ev.state}`;
    const body = el('div', 'sp-dbody');
    const head = el('div', 'sp-dhead');
    head.append(leagueTag(ev), el('span', 'sp-dim', ev.leagueName));
    const x = el('button', 'sp-x', '✕'); x.title = 'Close (Esc)'; x.onclick = closeDrawer; head.append(x);
    body.append(head);

    const hero = el('div', 'sp-hero');
    if (ev.away && ev.home) {
      for (const side of [ev.away, ev.home]) {
        const t = el('div', 'sp-hteam');
        if (isHttp(side.team.logo)) t.append(logo(side.team.logo, 'sp-biglogo')); else t.append(el('div', 'sp-biglogo ph', (side.team.abbr || '?').slice(0, 3)));
        t.append(el('div', 'sp-hname' + (side.winner ? ' win' : ''), side.team.fullName || side.team.name));
        if (side.record) t.append(el('div', 'sp-dim', side.record));
        t.append(el('div', 'sp-hscore', side.score != null && side.score !== '' ? side.score : '–'));
        hero.append(t);
      }
    } else {
      hero.append(el('div', 'sp-htitle', ev.title));
    }
    body.append(hero);
    const meta = el('div', 'sp-meta');
    const b = badge(ev); if (b) meta.append(b);
    meta.append(el('span', null, whenText(ev)));
    if (ev.situation) meta.append(el('span', 'sp-dim', ev.situation));
    body.append(meta);
    if (ev.hidden && ev.state !== 'pre') body.append(el('div', 'sp-dim sp-hidenote', 'Scores are hidden. Turn off Hide Scores to see them.'));
    const info = el('div', 'sp-info');
    if (ev.subtitle) info.append(el('div', null, ev.subtitle));
    if (ev.venue && !(ev.subtitle || '').includes(ev.venue)) info.append(el('div', null, ev.venue));
    if (ev.broadcasts && ev.broadcasts.length) info.append(el('div', null, 'On: ' + ev.broadcasts.join(', ')));
    if (ev.away && ev.home && ev.home.periods.length) {
      const t = el('table', 'sp-lines');
      for (const side of [ev.away, ev.home]) { const tr = el('tr'); tr.append(el('th', null, side.team.abbr || side.team.name)); for (const p of side.periods) tr.append(el('td', null, p)); t.append(tr); }
      info.append(t);
    }
    if (ev.sessions && ev.sessions.length) {
      const ul = el('ul', 'sp-sessions');
      for (const s of ev.sessions) ul.append(el('li', null, `${s.label} · ${fmtDay(s.startMs)} ${fmtTime(s.startMs)}`));
      info.append(ul);
    }
    if (ev.standings && ev.standings.length) {
      const t = el('table', 'sp-results');
      for (const r of ev.standings.slice(0, 12)) { const tr = el('tr'); tr.append(el('td', 'sp-pos', r.pos || ''), el('td', null, r.name), el('td', 'sp-dim', r.detail || '')); t.append(tr); }
      info.append(t);
    }
    body.append(info);

    // follow buttons
    const fl = el('div', 'sp-follows');
    const follows = await invoke('sports:follows').catch(() => ({ teams: [], leagues: [], words: [] }));
    if (st.openId !== ev.id) return;
    const fam = (id) => id.split('/')[0];
    const sameFam = (a, b) => a === b || (['soccer', 'rugby', 'lacrosse'].includes(fam(a)) && fam(a) === fam(b));
    const isTeam = (s) => follows.teams.some((t) => t.teamId === s.team.id && sameFam(t.leagueId, ev.leagueId));
    for (const side of ev.away && ev.home ? [ev.home, ev.away] : []) {
      fl.append(followBtn(side.team.name, isTeam(side), async (on) => {
        const item = { kind: 'team', leagueId: ev.leagueId, teamId: side.team.id, name: side.team.name, abbr: side.team.abbr, color: side.team.color, logo: side.team.logo };
        await invoke(on ? 'sports:unfollow' : 'sports:follow', item); load();
      }));
    }
    const lf = follows.leagues.includes(ev.leagueId);
    fl.append(followBtn(ev.leagueName, lf, async (on) => { await invoke(on ? 'sports:unfollow' : 'sports:follow', { kind: 'league', id: ev.leagueId }); load(); }));
    const tbl = el('button', 'sp-mini', 'League table');
    tbl.onclick = () => showStandings(ev.leagueId, ev.leagueName);
    fl.append(tbl);
    body.append(fl);

    // channels
    const ch = el('div', 'sp-channels'); ch.dataset.sig = sigNow;
    body.append(ch);
    const slot = el('div', 'sp-standings'); slot.id = 'sp-standings-slot';
    body.append(slot);
    d.replaceChildren(body);
    if (soft && keepKey === sigNow && prevChannels) { ch.replaceWith(prevChannels); return; }
    loadChannels(ev, ch);
  }

  async function loadChannels(ev, box) {
    const seq = ++st.drawerSeq;
    box.append(el('h3', null, 'Channels showing this game'), el('div', 'sp-dim', 'Searching your channels and guide…'));
    let r;
    try { r = await invoke('sports:channels', ev.id); } catch (e) { if (seq === st.drawerSeq) { box.replaceChildren(el('h3', null, 'Channels showing this game'), el('div', 'sp-dim error', 'Search failed: ' + (e && e.message || e))); } return; }
    if (seq !== st.drawerSeq || st.openId !== ev.id) return;
    box.replaceChildren(el('h3', null, `Channels showing this game${r.groups.length ? ` (${r.total})` : ''}`));
    if (!r.found) { box.append(el('div', 'sp-dim', 'This game is no longer in the feed.')); return; }
    if (!r.channelCount) { box.append(el('div', 'sp-dim', 'No channels loaded yet. Add a playlist in Settings.')); return; }
    if (!r.groups.length) { box.append(el('div', 'sp-dim', r.guide ? 'None of your channels or guide listings show this game yet.' : 'Nothing found by channel name. Load a guide (Settings) to search listings too.')); return; }
    for (const g of r.groups) box.append(channelRow(g));
  }

  function channelRow(g) {
    const wrap = el('div', 'sp-chgroup');
    const row = (m, isCopy) => {
      const r = el('div', 'sp-ch' + (m.sure ? ' sure' : '') + (isCopy ? ' copy' : ''));
      if (isHttp(m.channel.logo)) r.append(logo(m.channel.logo, 'sp-chlogo')); else r.append(el('div', 'sp-chlogo ph'));
      const txt = el('div', 'sp-chtxt');
      const name = el('div', 'sp-chname', m.channel.name);
      if (m.quality) name.append(el('span', 'sp-q', m.quality));
      txt.append(name);
      const why = (m.program ? `${m.program.title} · ${fmtTime(m.program.start)}–${fmtTime(m.program.stop)}` : m.how) + (m.channel.group ? ` · ${m.channel.group}` : '');
      txt.append(el('div', 'sp-dim sp-chwhy', why));
      const play = el('button', 'primary sp-play', 'Play');
      play.onclick = () => { if (window.NuvioPlayer) window.NuvioPlayer.play(m.channel); else window.NuvioUI.toast('Player not available', 'error'); };
      r.append(txt, el('span', 'sp-conf' + (m.sure ? ' sure' : ''), m.sure ? 'Sure' : 'Maybe'), play);
      if (window.NuvioRecordings) {
        const rec = el('button', 'sp-mini', m.program ? 'Record' : 'Record now');
        rec.onclick = () => { if (m.program) window.NuvioRecordings.schedule(m.channel, m.program); else window.NuvioRecordings.recordNow(m.channel); };
        r.append(rec);
        if (m.program && m.program.start > Date.now()) {
          const rem = el('button', 'sp-mini', 'Remind'); rem.onclick = () => window.NuvioRecordings.remind(m.channel, m.program); r.append(rem);
        }
      }
      return r;
    };
    wrap.append(row(g.lead, false));
    if (g.copies.length) {
      const more = el('button', 'sp-mini sp-more', `+ ${g.copies.length} other ${g.copies.length === 1 ? 'copy' : 'copies'}`);
      const box = el('div', 'sp-copies'); box.hidden = true;
      for (const c of g.copies) box.append(row(c, true));
      more.onclick = () => { box.hidden = !box.hidden; };
      wrap.append(more, box);
    }
    return wrap;
  }

  async function showStandings(leagueId, name) {
    const slot = document.getElementById('sp-standings-slot');
    if (!slot) return;
    slot.replaceChildren(el('h3', null, `${name} table`), el('div', 'sp-dim', 'Loading…'));
    let s;
    try { s = await invoke('sports:standings', leagueId); } catch { s = null; }
    slot.replaceChildren(el('h3', null, `${name} table${s && s.season ? ' · ' + s.season : ''}`));
    if (!s) { slot.append(el('div', 'sp-dim', 'No table available for this competition.')); return; }
    for (const g of s.groups.slice(0, 6)) {
      if (s.groups.length > 1) slot.append(el('div', 'sp-grp', g.parent ? `${g.parent} · ${g.name}` : g.name));
      const t = el('table', 'sp-table');
      const hr = el('tr'); hr.append(el('th', null, '#'), el('th', 'left', ''));
      for (const c of s.columns) hr.append(el('th', null, c.label));
      t.append(hr);
      for (const r of g.rows.slice(0, 20)) {
        const tr = el('tr'); tr.append(el('td', null, String(r.pos)), el('td', 'left', r.shortName));
        for (const c of s.columns) tr.append(el('td', null, r.values[c.key] || ''));
        t.append(tr);
      }
      slot.append(t);
    }
  }

  // ------------------------------------------------------------------------------------------------ leagues panel
  async function openLeagues() {
    st.leagues = await invoke('sports:leagues');
    const p = st.ui.panel;
    p.hidden = false;
    renderLeagues();
  }
  function renderLeagues() {
    const p = st.ui.panel;
    const box = el('div', 'sp-pbox');
    const head = el('div', 'sp-dhead');
    head.append(el('strong', null, 'Leagues'), el('span', 'sp-dim', 'Shown leagues appear in All Events; ★ follows every game.'));
    const x = el('button', 'sp-x', '✕'); x.onclick = () => { p.hidden = true; load(); }; head.append(x);
    box.append(head);
    const q = el('input'); q.type = 'search'; q.placeholder = 'Filter leagues'; box.append(q);
    const list = el('div', 'sp-plist'); box.append(list);
    const sel = el('select'); const regions = ['', 'US', 'UK', 'IE', 'CA', 'AU', 'OTHER'];
    const names = { '': 'Region: automatic', US: 'United States', UK: 'United Kingdom', IE: 'Ireland', CA: 'Canada', AU: 'Australia & New Zealand', OTHER: 'Somewhere else' };
    for (const r of regions) { const o = el('option', null, names[r]); o.value = r; sel.append(o); }
    sel.value = st.settings.region || '';
    sel.onchange = async () => { st.settings = await invoke('sports:saveSettings', { region: sel.value }); st.leagues = await invoke('sports:leagues'); fill(); };
    head.after(sel);
    const fill = () => {
      list.replaceChildren();
      const needle = q.value.trim().toLowerCase();
      let sport = '';
      for (const l of st.leagues) {
        if (needle && !(l.name.toLowerCase().includes(needle) || l.short.toLowerCase().includes(needle) || l.sportLabel.toLowerCase().includes(needle))) continue;
        if (l.sportLabel !== sport) { sport = l.sportLabel; list.append(el('div', 'sp-grp', `${l.icon} ${sport}`)); }
        const row = el('div', 'sp-lrow' + (l.feedable ? '' : ' nofeed'));
        const cb = el('input'); cb.type = 'checkbox'; cb.checked = l.shown; cb.disabled = !l.feedable;
        cb.onchange = async () => { st.leagues = await invoke('sports:toggleLeague', l.id); fill(); };
        const nm = el('span', 'sp-lname', l.name); const tg = el('span', 'sp-league', l.short); const c = hex(l.color); if (c) tg.style.setProperty('--lc', c);
        const star = el('button', 'sp-star' + (l.followed ? ' on' : ''), l.followed ? '★' : '☆'); star.title = l.followed ? 'Unfollow league' : 'Follow every game';
        star.disabled = !l.feedable;
        star.onclick = async () => { await invoke(l.followed ? 'sports:unfollow' : 'sports:follow', { kind: 'league', id: l.id }); st.leagues = await invoke('sports:leagues'); fill(); };
        row.append(cb, tg, nm, l.feedable ? star : el('span', 'sp-dim', 'guide only'));
        list.append(row);
      }
    };
    q.oninput = fill;
    fill();
    p.replaceChildren(box);
  }

  // ------------------------------------------------------------------------------------------------ toolbar & lifecycle
  function dayChips() {
    const bar = st.ui.days; bar.replaceChildren();
    const base = new Date(); base.setHours(0, 0, 0, 0);
    for (let i = -7; i <= 7; i++) {
      const d = new Date(base); d.setDate(d.getDate() + i);
      const label = i === 0 ? 'Today' : i === 1 ? 'Tomorrow' : i === -1 ? 'Yesterday' : d.toLocaleDateString([], { weekday: 'short', day: 'numeric' });
      const b = el('button', 'sp-day' + (i === st.day ? ' on' : ''), label);
      b.title = d.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' });
      b.dataset.off = String(i);
      b.onclick = () => { st.day = i; dayChips(); load(); };
      bar.append(b);
    }
    const on = bar.querySelector('.on'); if (on && on.scrollIntoView) on.scrollIntoView({ inline: 'center', block: 'nearest' });
  }
  function updateLeagueFilter() {
    const sel = st.ui.leagueSel;
    if (st.view !== 'all') { sel.hidden = true; return; }
    sel.hidden = false;
    const counts = new Map();
    for (const e of st.events) counts.set(e.leagueId, { n: ((counts.get(e.leagueId) || {}).n || 0) + 1, name: e.leagueName });
    const cur = st.league;
    sel.replaceChildren();
    const all = el('option', null, `All leagues (${st.events.length})`); all.value = ''; sel.append(all);
    for (const [id, v] of [...counts].sort((a, b) => a[1].name.localeCompare(b[1].name))) { const o = el('option', null, `${v.name} (${v.n})`); o.value = id; sel.append(o); }
    if (cur && !counts.has(cur)) st.league = '';
    sel.value = st.league;
  }
  function setView(v) {
    st.view = v;
    st.ui.tabMine.classList.toggle('on', v === 'mine');
    st.ui.tabAll.classList.toggle('on', v === 'all');
    st.ui.days.hidden = v !== 'all';
    st.ui.list.scrollTop = 0;
    load();
  }

  function mount(root) {
    root.classList.add('sp-root');
    const bar = el('div', 'sp-bar');
    const tabMine = el('button', 'sp-tab on', 'My Sports'); const tabAll = el('button', 'sp-tab', 'All Events');
    tabMine.onclick = () => setView('mine'); tabAll.onclick = () => setView('all');
    const leagueSel = el('select', 'sp-leaguesel'); leagueSel.hidden = true;
    leagueSel.onchange = () => { st.league = leagueSel.value; build(); };
    const hide = el('label', 'sp-hide'); const cb = el('input'); cb.type = 'checkbox';
    hide.append(cb, el('span', null, 'Hide scores'));
    cb.onchange = async () => { st.settings = await invoke('sports:saveSettings', { hideScores: cb.checked }); load(); };
    const leaguesBtn = el('button', null, 'Leagues'); leaguesBtn.onclick = openLeagues;
    const refresh = el('button', null, 'Refresh'); refresh.onclick = async () => { setStatus('Refreshing…'); try { await invoke('sports:refresh'); } catch { /* ignore */ } load(); };
    const status = el('span', 'sp-status');
    bar.append(tabMine, tabAll, leagueSel, el('span', 'sp-spacer'), status, hide, leaguesBtn, refresh);
    const days = el('div', 'sp-days'); days.hidden = true;
    const list = el('div', 'sp-list'); const spacer = el('div', 'sp-spacer-v'); const rows = el('div', 'sp-rows');
    list.append(spacer, rows);
    list.addEventListener('scroll', redraw, { passive: true });
    window.addEventListener('resize', redraw);
    const drawer = el('aside', 'sp-drawer'); drawer.hidden = true; const drawerBody = el('div', 'sp-drawerbody'); drawer.append(drawerBody);
    const panel = el('div', 'sp-panel'); panel.hidden = true;
    panel.addEventListener('click', (e) => { if (e.target === panel) { panel.hidden = true; load(); } });
    root.append(bar, days, list, drawer, panel);
    Object.assign(st.ui, { bar, tabMine, tabAll, leagueSel, status, days, list, spacer, rows, drawer, drawerBody, panel, hideCb: cb });
    root.addEventListener('keydown', (e) => { if (e.key === 'Escape') { if (!panel.hidden) { panel.hidden = true; load(); } else if (st.openId) closeDrawer(); } });
    dayChips();
    window.nuvio.on('sports:updated', () => { if (st.visible) { clearTimeout(st.timer); st.timer = setTimeout(load, 300); } });
    invoke('sports:settings').then((s) => { st.settings = s; cb.checked = !!s.hideScores; }).catch(() => {});
  }

  window.NuvioUI.registerTab({
    id: 'sports', title: 'Sports', mount,
    onShow() {
      st.visible = true;
      invoke('sports:visible', true).catch(() => {});
      invoke('sports:settings').then((s) => { st.settings = s; if (st.ui.hideCb) st.ui.hideCb.checked = !!s.hideScores; }).catch(() => {});
      load();
    },
    onHide() { st.visible = false; invoke('sports:visible', false).catch(() => {}); },
  });
})();
