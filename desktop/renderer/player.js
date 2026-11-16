'use strict';
// Shared player (baseline; owned by the player work stream). Everything that plays video calls NuvioPlayer.
(() => {
  const dock = document.getElementById('player-dock');
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const wrap = el('div', 'video-wrap');
  const video = el('video'); video.controls = true; video.autoplay = true;
  const msg = el('div', 'player-msg');
  wrap.append(video, msg);
  const info = el('div', 'now-info');
  const title = el('h2', null, 'Select a channel'); const sub = el('p'); const desc = el('p', 'now-desc');
  info.append(title, sub, desc);
  const sched = el('div', 'schedule');
  const hint = el('p', 'hint', 'F fullscreen · M mute · [ ] previous/next channel');
  dock.append(wrap, info, sched, hint);

  let hls = null;
  let current = null;
  const emit = (type, extra) => window.dispatchEvent(new CustomEvent('nuvio:player', { detail: { type, channel: current, ...extra } }));
  const fmt = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  function stop() {
    if (hls) { hls.destroy(); hls = null; }
    video.removeAttribute('src'); video.load(); msg.textContent = '';
    emit('stopped');
  }

  function play(ch) {
    current = ch;
    msg.textContent = '';
    title.textContent = ch.name || '';
    sub.textContent = ch.group || '';
    desc.textContent = '';
    sched.replaceChildren();
    if (hls) { hls.destroy(); hls = null; }
    const url = ch.url;
    const isHls = /\.m3u8(\?|$)/i.test(url);
    const isFile = /\.(mp4|webm|m4v|mkv)(\?|$)/i.test(url);
    const native = () => { video.src = url; video.play().catch(() => {}); };
    if (/^(rtmp|rtsp|udp):/i.test(url)) {
      msg.textContent = 'This stream type (' + url.split(':')[0] + ') is not supported yet.';
      emit('error');
    } else if (isFile) {
      native();
    } else if (window.Hls && window.Hls.isSupported()) {
      let ok = false; let retries = 0;
      const h = new window.Hls({ lowLatencyMode: false, maxBufferLength: 30 });
      hls = h;
      h.on(window.Hls.Events.MANIFEST_PARSED, () => { ok = true; video.play().catch(() => {}); emit('playing'); });
      h.on(window.Hls.Events.ERROR, (_e, d) => {
        if (!d.fatal || hls !== h) return;
        if (d.type === window.Hls.ErrorTypes.NETWORK_ERROR && ok && retries++ < 3) { h.startLoad(); return; }
        if (d.type === window.Hls.ErrorTypes.MEDIA_ERROR && ok) { h.recoverMediaError(); return; }
        h.destroy(); hls = null;
        if (!ok && !isHls) { native(); video.onerror = () => { msg.textContent = 'Could not play this stream. Raw MPEG-TS is not supported yet.'; emit('error'); }; }
        else { msg.textContent = 'Could not play this channel (' + (d.details || d.type) + ').'; emit('error'); }
      });
      h.loadSource(url); h.attachMedia(video);
    } else native();
    if (ch.tvgId) {
      window.nuvio.invoke('guide:schedule', ch.tvgId, Date.now() - 3600e3, Date.now() + 12 * 3600e3).then((list) => {
        if (current !== ch) return;
        const now = Date.now();
        for (const p of list) {
          if (p.stop < now) continue;
          const row = el('div', 'item' + (p.start <= now ? ' now' : ''));
          row.append(el('div', 't', `${fmt(p.start)} – ${fmt(p.stop)}`), el('div', 'title', p.title || ''));
          sched.append(row);
          if (p.start <= now) { sub.textContent = `${ch.group || ''} · ${p.title}`; desc.textContent = p.desc || ''; }
        }
      }).catch(() => {});
    }
  }

  const toggleFullscreen = () => { if (document.fullscreenElement) document.exitFullscreen(); else wrap.requestFullscreen(); };
  window.addEventListener('keydown', (e) => {
    if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) return;
    if (e.key.toLowerCase() === 'f') toggleFullscreen();
    else if (e.key.toLowerCase() === 'm') video.muted = !video.muted;
  });
  window.nuvio.invoke('settings:get').then((s) => { video.volume = s.volume ?? 1; });
  video.addEventListener('volumechange', () => window.nuvio.invoke('settings:save', { volume: video.volume }));

  window.NuvioPlayer = { play, stop, current: () => current, toggleFullscreen, video: () => video };
})();
