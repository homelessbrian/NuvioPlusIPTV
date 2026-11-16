'use strict';
// Settings tab: sources (M3U URL/file, Xtream login, XMLTV URL/file), backup export/import.
(() => {
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  function field(label, input) { const l = el('label', 'set-field'); l.append(el('span', null, label), input); return l; }
  const text = (ph, type) => { const i = el('input'); i.type = type || 'text'; i.placeholder = ph || ''; return i; };

  window.NuvioUI.registerTab({
    id: 'settings', title: 'Settings',
    mount(root) {
      root.classList.add('set');
      const box = el('div', 'set-box'); root.append(box);
      box.append(el('h2', null, 'Sources'));
      const plUrl = text('https://… M3U playlist URL'); const plFile = el('span', 'dim'); const plPick = el('button', null, 'Choose file…');
      const epgUrl = text('https://… XMLTV guide URL (.xml or .xml.gz)'); const epgFile = el('span', 'dim'); const epgPick = el('button', null, 'Choose file…');
      const xs = text('http://server:port (Xtream Codes server)'); const xu = text('Username'); const xp = text('Password', 'password');
      let pl = null; let epg = null;
      plPick.onclick = async () => { const p = await window.nuvio.invoke('dialog:pick', 'playlist'); if (p) { pl = { kind: 'file', value: p }; plUrl.value = ''; plFile.textContent = p; } };
      epgPick.onclick = async () => { const p = await window.nuvio.invoke('dialog:pick', 'guide'); if (p) { epg = { kind: 'file', value: p }; epgUrl.value = ''; epgFile.textContent = p; } };
      const prow = el('div', 'set-row'); prow.append(plPick, plFile);
      const erow = el('div', 'set-row'); erow.append(epgPick, epgFile);
      box.append(field('Playlist URL', plUrl), prow, el('p', 'dim', 'or sign in with Xtream Codes (also loads live channels, the guide and On Demand):'),
        field('Server', xs), field('Username', xu), field('Password', xp), field('Guide URL', epgUrl), erow);
      box.append(el('p', 'dim', 'Sources are stored encrypted for your Windows account.'));
      const save = el('button', 'primary', 'Save and load');
      save.onclick = async () => {
        const playlist = plUrl.value.trim() ? { kind: 'url', value: plUrl.value.trim() } : pl;
        const guide = epgUrl.value.trim() ? { kind: 'url', value: epgUrl.value.trim() } : epg;
        const xtream = xs.value.trim() ? { server: xs.value.trim(), username: xu.value.trim(), password: xp.value } : null;
        await window.nuvio.invoke('settings:save', { playlist, guide, xtream });
        window.NuvioUI.toast('Saved. Loading…');
        window.NuvioUI.select('livetv');
        if (window.NuvioLiveTv) window.NuvioLiveTv.reload();
      };
      box.append(save);

      box.append(el('h2', null, 'Backup'));
      box.append(el('p', 'dim', 'Favorites, hidden channels and sources can be saved to a JSON file and restored on another PC.'));
      const exp = el('button', null, 'Export…'); const imp = el('button', null, 'Import…');
      exp.onclick = async () => { const r = await window.nuvio.invoke('settings:export'); if (r && r.path) window.NuvioUI.toast('Saved to ' + r.path); };
      imp.onclick = async () => { const r = await window.nuvio.invoke('settings:import'); if (r && r.ok) { window.NuvioUI.toast('Imported. Reloading…'); if (window.NuvioLiveTv) window.NuvioLiveTv.reload(); fill(); } };
      const brow = el('div', 'set-row'); brow.append(exp, imp); box.append(brow);

      async function fill() {
        const s = await window.nuvio.invoke('settings:get');
        pl = s.playlist; epg = s.guide;
        plUrl.value = pl && pl.kind === 'url' ? pl.value : ''; plFile.textContent = pl && pl.kind === 'file' ? pl.value : '';
        epgUrl.value = epg && epg.kind === 'url' ? epg.value : ''; epgFile.textContent = epg && epg.kind === 'file' ? epg.value : '';
        xs.value = s.xtream ? s.xtream.server : ''; xu.value = s.xtream ? s.xtream.username : ''; xp.value = s.xtream ? s.xtream.password : '';
      }
      this.onShow = fill; fill();
    },
    onShow() {},
  });
})();
