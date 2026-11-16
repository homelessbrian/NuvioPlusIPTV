'use strict';
// Tab shell. Features call NuvioUI.registerTab({ id, title, mount(root), onShow?(), onHide?() }).
(() => {
  const tabs = [];
  let active = null;
  const $ = (id) => document.getElementById(id);

  function select(id) {
    const t = tabs.find((x) => x.id === id) || tabs[0];
    if (!t) return;
    if (active && active !== t && active.onHide) active.onHide();
    active = t;
    for (const x of tabs) { x.root.hidden = x !== t; x.btn.classList.toggle('active', x === t); x.btn.setAttribute('aria-selected', String(x === t)); }
    document.body.dataset.tab = t.id;
    if (!t.mounted) { t.mounted = true; t.mount(t.root); }
    if (t.onShow) t.onShow();
    try { localStorage.setItem('nuvio.tab', t.id); } catch { /* ignore */ }
  }

  function registerTab(def) {
    const root = document.createElement('section');
    root.className = 'tab-root';
    root.id = 'tab-' + def.id;
    root.hidden = true;
    $('tab-content').append(root);
    const btn = document.createElement('button');
    btn.className = 'tab-btn'; btn.textContent = def.title; btn.setAttribute('role', 'tab');
    btn.onclick = () => select(def.id);
    $('tabs').append(btn);
    tabs.push({ ...def, root, btn, mounted: false });
  }

  function toast(text, kind) {
    const t = document.createElement('div');
    t.className = 'toast' + (kind ? ' ' + kind : '');
    t.textContent = text;
    $('toasts').append(t);
    setTimeout(() => t.remove(), kind === 'error' ? 8000 : 4000);
  }

  window.NuvioUI = { registerTab, select, toast, activeId: () => (active && active.id) || null, tabs: () => tabs.map((t) => t.id) };

  // Ctrl+1..9 switches tabs.
  window.addEventListener('keydown', (e) => {
    if (e.ctrlKey && /^[1-9]$/.test(e.key)) { const t = tabs[Number(e.key) - 1]; if (t) { e.preventDefault(); select(t.id); } }
  });
})();
