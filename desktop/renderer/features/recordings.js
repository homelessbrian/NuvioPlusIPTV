'use strict';
// Recordings tab: recording now / scheduled / completed / series rules / reminders.
// Also exposes window.NuvioRecordings for other tabs (guide, sports):
//   recordNow(channel), schedule(channel, programme), remind(channel, programme)
(() => {
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const invoke = (...a) => window.nuvio.invoke(...a);
  const toast = (t, k) => { if (window.NuvioUI) window.NuvioUI.toast(t, k); };
  const fail = (e) => toast(String((e && e.message) || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, ''), 'error');

  const SECTIONS = [
    ['now', 'Recording now'], ['scheduled', 'Scheduled'], ['completed', 'Completed'], ['rules', 'Series rules'], ['reminders', 'Reminders'],
  ];
  const st = { data: { items: [], rules: [], reminders: [], config: {} }, section: 'now', ui: {}, mounted: false, seenAt: Date.now() };

  const fmtTime = (ms) => new Date(ms).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const fmtDur = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); const h = Math.floor(s / 3600); const m = Math.floor((s % 3600) / 60); const x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : String(m)) + ':' + String(x).padStart(2, '0'); };
  const fmtSize = (b) => (b >= 1073741824 ? (b / 1073741824).toFixed(2) + ' GB' : b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.round((b || 0) / 1024) + ' KB');
  const STATUS = { scheduled: 'Scheduled', recording: 'Recording', completed: 'Completed', failed: 'Failed', missed: 'Missed' };

  const bucket = (it) => (it.status === 'recording' ? 'now' : it.status === 'scheduled' ? 'scheduled' : 'completed');

  function btn(label, fn, cls) { const b = el('button', cls || '', label); b.onclick = async () => { try { await fn(); } catch (e) { fail(e); } }; return b; }

  function itemRow(it) {
    const row = el('div', 'rec-row');
    const main = el('div', 'rec-main');
    const head = el('div', 'rec-head');
    head.append(el('span', 'rec-badge ' + it.status, STATUS[it.status] || it.status), el('span', 'rec-title', it.title || it.channel.name));
    main.append(head);
    const sub = el('div', 'rec-sub dim');
    const parts = [it.channel.name];
    if (it.status === 'scheduled') parts.push(fmtTime(it.startMs) + ' – ' + new Date(it.endMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    if (it.status === 'recording') {
      const live = el('span', 'rec-live');
      live.dataset.start = String(it.startedAt || it.startMs); live.dataset.bytes = String(it.bytes || 0);

      sub.append(document.createTextNode(it.channel.name + ' · '), live, document.createTextNode(' · ends ' + new Date(it.endMs + (it.padAfter || 0)).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + (it.retryAt ? ' · reconnecting…' : '')));
    } else {
      if (it.status === 'completed') parts.push(fmtSize(it.bytes) + (it.files && it.files.length > 1 ? ` · ${it.files.length} parts` : ''));
      if (it.fileMissing) parts.push('file missing');
      if (it.error && it.status !== 'completed') parts.push(it.error);
      if (it.note) parts.push(it.note);
      if (it.seriesRuleId) parts.push('series');
      sub.textContent = parts.join(' · ');
    }
    main.append(sub);
    row.append(main);
    const acts = el('div', 'rec-acts');
    if (it.status === 'recording') {
      acts.append(btn('Stop', () => invoke('recordings:stop', it.id), 'primary'), btn('Open folder', () => invoke('recordings:openFolder', it.id)));
    } else if (it.status === 'scheduled') {
      acts.append(btn('Cancel', () => invoke('recordings:stop', it.id)));
    } else {
      if (it.status === 'completed' && !it.fileMissing && it.fileUrl) {
        acts.append(btn('Play', () => { if (window.NuvioPlayer) window.NuvioPlayer.play({ name: it.title || it.channel.name, url: it.fileUrl }); }, 'primary'),
          btn('Open in player', () => invoke('recordings:openFile', it.id)), btn('Open folder', () => invoke('recordings:openFolder', it.id)));
      }
      acts.append(btn('Delete', async () => {
        const del = it.status === 'completed' && !it.fileMissing && window.confirm('Delete the recorded file from disk too?\n\nOK = delete the file, Cancel = remove from the list only.');
        await invoke('recordings:remove', it.id, del);
      }));
    }
    row.append(acts);
    return row;
  }

  function ruleRow(r) {
    const row = el('div', 'rec-row');
    const main = el('div', 'rec-main');
    main.append(el('div', 'rec-title', r.title), el('div', 'rec-sub dim', `Every episode on ${r.channel.name}`));
    row.append(main, el('div', 'rec-acts'));
    row.lastChild.append(btn('Remove rule', () => invoke('recordings:removeSeriesRule', r.id)));
    return row;
  }
  function remRow(r) {
    const row = el('div', 'rec-row');
    const main = el('div', 'rec-main');
    main.append(el('div', 'rec-title', r.title || r.channel.name), el('div', 'rec-sub dim', `${r.channel.name} · ${fmtTime(r.startMs)}`));
    row.append(main, el('div', 'rec-acts'));
    row.lastChild.append(btn('Watch now', () => playChannel(r.channel)), btn('Remove', () => invoke('recordings:removeReminder', r.id)));
    return row;
  }

  function playChannel(ch) {
    if (window.NuvioUI && window.NuvioUI.tabs().includes('livetv')) window.NuvioUI.select('livetv');
    if (window.NuvioPlayer) window.NuvioPlayer.play(ch);
  }

  function render() {
    if (!st.mounted) return;
    const { items, rules, reminders } = st.data;
    const counts = { now: 0, scheduled: 0, completed: 0, rules: rules.length, reminders: reminders.length };
    for (const it of items) counts[bucket(it)]++;
    for (const [id, b] of Object.entries(st.ui.tabBtns)) b.firstChild.textContent = SECTIONS.find((s) => s[0] === id)[1] + (counts[id] ? ` (${counts[id]})` : '');
    for (const [id, b] of Object.entries(st.ui.tabBtns)) b.classList.toggle('active', id === st.section);
    const list = st.ui.list;
    list.replaceChildren();
    let rows;
    if (st.section === 'rules') rows = rules.map(ruleRow);
    else if (st.section === 'reminders') rows = reminders.slice().sort((a, b) => a.startMs - b.startMs).map(remRow);
    else {
      let arr = items.filter((it) => bucket(it) === st.section);
      arr = st.section === 'scheduled' ? arr.sort((a, b) => a.startMs - b.startMs) : arr.sort((a, b) => (b.startedAt || b.startMs) - (a.startedAt || a.startMs));
      rows = arr.map(itemRow);
    }
    if (!rows.length) {
      list.append(el('p', 'dim rec-empty', {
        now: 'Nothing is recording. Press "Record now" while a channel plays.', scheduled: 'No scheduled recordings. Schedule from the Guide or Sports tab.',
        completed: 'No finished recordings yet.', rules: 'No series rules. Add one below to record every episode of a show.', reminders: 'No reminders.',
      }[st.section]));
    } else list.append(...rows);
    st.ui.rule.hidden = st.section !== 'rules';
    st.ui.folder.textContent = 'Folder: ' + (st.data.config.folder || '(default)');
    tickLive();
  }

  function tickLive() {
    for (const n of document.querySelectorAll('#tab-recordings .rec-live')) {
      n.textContent = fmtDur(Date.now() - Number(n.dataset.start)) + ' · ' + fmtSize(Number(n.dataset.bytes));
    }
  }

  async function refresh() { try { st.data = await invoke('recordings:list'); render(); } catch (e) { fail(e); } }

  function currentChannel() { return window.NuvioPlayer && window.NuvioPlayer.current && window.NuvioPlayer.current(); }

  async function recordNow(channel, minutes) {
    const ch = channel || currentChannel();
    if (!ch || !ch.url) { toast('Play a channel first, then press Record now.', 'error'); return null; }
    try { const r = await invoke('recordings:recordNow', ch, minutes, null); toast('Recording ' + ch.name); return r; } catch (e) { fail(e); return null; }
  }
  async function schedule(channel, p, opts) {
    try { await invoke('recordings:scheduleOne', channel, p.start, p.stop, p.title, opts || {}); toast('Scheduled: ' + p.title); } catch (e) { fail(e); }
  }
  async function remind(channel, p) {
    try { await invoke('recordings:remind', channel, p.start, p.title); toast('Reminder set: ' + p.title); } catch (e) { fail(e); }
  }
  window.NuvioRecordings = { recordNow, schedule, remind, scheduleSeries: async (channel, title) => { try { await invoke('recordings:addSeriesRule', channel, title); toast('Series rule added: ' + title); } catch (e) { fail(e); } } };

  function mount(root) {
    root.classList.add('rec');
    const bar = el('div', 'rec-bar');
    const nowBtn = btn('Record now', async () => { await recordNow(); st.section = 'now'; refresh(); }, 'primary');
    nowBtn.title = 'Record the channel that is currently playing';
    const folder = el('span', 'rec-folder dim');
    const choose = btn('Change folder…', async () => {
      const f = await invoke('dialog:folder');
      if (f) { await invoke('recordings:setConfig', { folder: f }); refresh(); }
    });
    const open = btn('Open folder', () => invoke('recordings:openFolder'));
    bar.append(nowBtn, folder, choose, open);

    const tabs = el('div', 'rec-tabs');
    const tabBtns = {};
    for (const [id, label] of SECTIONS) {
      const b = el('button', 'rec-tab'); b.append(document.createTextNode(label));
      b.onclick = () => { st.section = id; render(); };
      tabs.append(b); tabBtns[id] = b;
    }
    const list = el('div', 'rec-list');

    const rule = el('div', 'rec-rule');
    const t = el('input'); t.type = 'text'; t.placeholder = 'Show title, e.g. The Simpsons';
    const add = btn('Add rule for playing channel', async () => {
      const ch = currentChannel();
      if (!ch) return toast('Play the channel the show airs on first.', 'error');
      if (!t.value.trim()) return toast('Type the show title.', 'error');
      await invoke('recordings:addSeriesRule', ch, t.value.trim());
      t.value = ''; refresh();
    });
    rule.append(t, add); rule.hidden = true;

    root.append(bar, tabs, list, rule);
    Object.assign(st.ui, { list, tabBtns, rule, folder });
    st.mounted = true;
    window.nuvio.on('recordings:updated', (d) => { st.data = d; render(); });
    setInterval(() => { if (st.mounted && !root.hidden) tickLive(); }, 1000);
    refresh();
  }

  window.NuvioUI.registerTab({ id: 'recordings', title: 'Recordings', mount, onShow: () => refresh() });

  // Reminder notification clicked: bring the channel up.
  const hook = () => window.nuvio.on('recordings:remindClicked', (r) => { if (r && r.channel) playChannel(r.channel); });
  if (window.nuvio) hook();
})();
