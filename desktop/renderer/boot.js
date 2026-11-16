'use strict';
(() => {
  let last = null;
  try { last = localStorage.getItem('nuvio.tab'); } catch { /* ignore */ }
  const ids = window.NuvioUI.tabs();
  window.NuvioUI.select(ids.includes(last) ? last : ids[0]);
})();
