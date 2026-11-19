'use strict';
// Shared player (owned by the player work stream). Everything that plays video calls NuvioPlayer.
// Engines: hls.js (HLS), mpegts.js (raw MPEG-TS), native <video> (mp4/webm). The pure logic lives in
// createCore() at the bottom and is exported for node:test when loaded as a CommonJS module.
(function (root) {
  const Core = createCore();
  if (typeof module !== 'undefined' && module.exports) module.exports = Core;
  if (typeof document !== 'undefined' && root.nuvio && document.getElementById('player-dock')) initPlayer(root, Core);

  // ------------------------------------------------------------------------------------------------
  function initPlayer(win, C) {
    const dock = document.getElementById('player-dock');
    const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
    const toast = (t, kind) => { try { if (win.NuvioUI && win.NuvioUI.toast) win.NuvioUI.toast(t, kind); } catch { /* ignore */ } };
    const fmt = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    // ---- DOM ----
    const wrap = el('div', 'video-wrap');
    const video = el('video'); video.autoplay = true; video.playsInline = true; video.controls = false;
    const msg = el('div', 'player-msg');
    const spinner = el('div', 'pl-spinner'); spinner.setAttribute('aria-hidden', 'true');
    const ui = el('div', 'pl-ui');
    const top = el('div', 'pl-top');
    const topText = el('div', 'pl-toptext');
    const chName = el('div', 'pl-name'); const chNow = el('div', 'pl-now');
    topText.append(chName, chNow);
    const clock = el('div', 'pl-clock');
    top.append(topText, clock);
    const bottom = el('div', 'pl-bottom');
    const mkBtn = (label, title, fn) => { const b = el('button', 'pl-btn', label); b.type = 'button'; b.title = title; b.addEventListener('click', (e) => { e.stopPropagation(); fn(); b.blur(); }); return b; };
    const bPrev = mkBtn('⏮', 'Previous channel ([)', () => stepChannel(-1));
    const bPlay = mkBtn('⏸', 'Pause / resume', () => (video.paused ? resume() : pause()));
    const bNext = mkBtn('⏭', 'Next channel (])', () => stepChannel(1));
    const bMute = mkBtn('🔊', 'Mute (M)', () => { video.muted = !video.muted; });
    const vol = el('input', 'pl-vol'); vol.type = 'range'; vol.min = '0'; vol.max = '1'; vol.step = '0.01'; vol.title = 'Volume (Up/Down in fullscreen)';
    vol.addEventListener('input', () => { video.volume = Number(vol.value); if (video.muted && video.volume > 0) video.muted = false; });
    vol.addEventListener('change', () => vol.blur());
    const spacer = el('div', 'pl-spacer');
    const bTracks = mkBtn('Audio / Subs', 'Audio and subtitle tracks', () => toggleMenu());
    const bFull = mkBtn('⛶', 'Fullscreen (F)', () => toggleFullscreen());
    bottom.append(bPrev, bPlay, bNext, bMute, vol, spacer, bTracks, bFull);
    const menu = el('div', 'pl-menu'); menu.hidden = true;
    ui.append(top, bottom, menu);
    wrap.append(video, msg, spinner, ui);

    const info = el('div', 'now-info');
    const title = el('h2', null, 'Select a channel'); const sub = el('p'); const desc = el('p', 'now-desc');
    info.append(title, sub, desc);
    const sched = el('div', 'schedule');
    const hint = el('p', 'hint', 'F fullscreen · M mute · [ ] previous/next channel · double-click video for fullscreen');
    dock.append(wrap, info, sched, hint);

    // ---- state ----
    let hls = null; let mp = null; let engine = null;
    let current = null;
    let S = null; // active playback session
    let genCounter = 0;
    let retryTimer = null; let sniffCtl = null;
    let spinTimer = null;
    let tracksCfg = { tracks: {}, muted: false };
    let saveTimer = null; let volTimer = null;
    let nowNext = null;

    const emit = (type, extra) => win.dispatchEvent(new CustomEvent('nuvio:player', { detail: { type, channel: current, ...extra } }));
    const alive = (gen) => S && S.gen === gen;

    // ---- settings (volume, mute, track memory) ----
    win.nuvio.invoke('settings:get').then((s) => {
      if (s && typeof s.volume === 'number') video.volume = s.volume;
      const p = s && s.features && s.features.player;
      if (p) { tracksCfg = { tracks: p.tracks && typeof p.tracks === 'object' ? p.tracks : {}, muted: !!p.muted }; video.muted = tracksCfg.muted; }
      syncVol();
    }).catch(() => {});
    const savePlayerCfg = () => {
      clearTimeout(saveTimer);
      saveTimer = setTimeout(() => { win.nuvio.invoke('settings:save', { features: { player: { tracks: tracksCfg.tracks, muted: tracksCfg.muted } } }).catch(() => {}); }, 600);
    };
    function syncVol() {
      vol.value = String(video.muted ? 0 : video.volume);
      bMute.textContent = video.muted || video.volume === 0 ? '🔇' : '🔊';
    }
    video.addEventListener('volumechange', () => {
      syncVol();
      clearTimeout(volTimer);
      volTimer = setTimeout(() => win.nuvio.invoke('settings:save', { volume: video.volume }).catch(() => {}), 400);
      if (tracksCfg.muted !== video.muted) { tracksCfg.muted = video.muted; savePlayerCfg(); }
    });

    // ---- spinner / status ----
    function setBuffering(on) {
      clearTimeout(spinTimer);
      if (on) { spinTimer = setTimeout(() => { spinner.classList.add('show'); emit('buffering', { on: true }); }, 400); }
      else if (spinner.classList.contains('show')) { spinner.classList.remove('show'); emit('buffering', { on: false }); }
    }
    function setMsg(text, kind) { msg.textContent = text || ''; msg.className = 'player-msg' + (kind ? ' ' + kind : ''); }

    // ---- engines ----
    function teardown() {
      clearTimeout(retryTimer);
      if (sniffCtl) { try { sniffCtl.abort(); } catch { /* ignore */ } sniffCtl = null; }
      const h = hls; const m = mp; hls = null; mp = null; engine = null;
      if (h) { try { h.destroy(); } catch { /* ignore */ } }
      if (m) { try { m.pause(); m.unload(); m.detachMediaElement(); m.destroy(); } catch { /* ignore */ } }
      video.removeAttribute('src');
      try { video.load(); } catch { /* ignore */ }
    }

    function startEngine(name, url, gen) {
      teardown();
      engine = name;
      S.engine = name; S.progress = false; S.startedAt = Date.now();
      const fail = (reason, retryable) => onEngineFail(gen, reason, retryable);
      if (name === 'hls') {
        if (!win.Hls || !win.Hls.isSupported()) return fail('hls.js unavailable', false);
        const H = win.Hls;
        const h = new H({ lowLatencyMode: false, maxBufferLength: 30, manifestLoadingMaxRetry: 2, levelLoadingMaxRetry: 3, fragLoadingMaxRetry: 4, startPosition: S.startAtSec > 0 ? S.startAtSec : -1 });
        hls = h; let media = 0;
        h.on(H.Events.MANIFEST_PARSED, () => { if (alive(gen) && hls === h) video.play().catch(onPlayReject); });
        h.on(H.Events.AUDIO_TRACKS_UPDATED, () => { if (hls === h) tracksChanged(); });
        h.on(H.Events.SUBTITLE_TRACKS_UPDATED, () => { if (hls === h) tracksChanged(); });
        h.on(H.Events.ERROR, (_e, d) => {
          if (!d.fatal || hls !== h || !alive(gen)) return;
          if (d.type === H.ErrorTypes.MEDIA_ERROR && S.progress && media++ < 2) {
            if (media === 2) h.swapAudioCodec();
            h.recoverMediaError(); return;
          }
          const net = d.type === H.ErrorTypes.NETWORK_ERROR;
          // A dead manifest on a url that is not really HLS: fall through to the next engine straight away.
          fail(d.details || d.type, net && S.progress);
        });
        h.loadSource(url); h.attachMedia(video);
      } else if (name === 'mpegts') {
        const M = win.mpegts;
        if (!M || !M.isSupported()) return fail('mpegts.js unavailable', false);
        const p = M.createPlayer({ type: 'mpegts', isLive: true, url },
          { enableWorker: true, liveBufferLatencyChasing: true, liveBufferLatencyMaxLatency: 6, liveBufferLatencyMinRemain: 1, autoCleanupSourceBuffer: true, lazyLoad: false });
        mp = p;
        p.on(M.Events.ERROR, (type, detail) => { if (mp === p && alive(gen)) fail(detail || type, S.progress); });
        p.on(M.Events.MEDIA_INFO, () => { if (mp === p) tracksChanged(); });
        p.attachMediaElement(video);
        try { p.load(); } catch (e) { return fail(e.message, false); }
        video.play().catch(onPlayReject);
      } else { // native
        video.src = url;
        video.play().catch(onPlayReject);
      }
    }

    function onPlayReject(e) {
      if (e && e.name === 'NotAllowedError') { setMsg('Press play to start (autoplay was blocked).', 'info'); }
    }

    // Failure ladder: retry same engine with backoff (only if it had been playing) -> next engine -> next backup -> reconnect cycle -> give up.
    function onEngineFail(gen, reason, retryable) {
      if (!alive(gen)) return;
      const s = S;
      if (retryable && s.retries < C.MAX_RETRIES) {
        s.retries++;
        const wait = C.backoffMs(s.retries);
        setMsg(`Connection lost, retrying (${s.retries}/${C.MAX_RETRIES})…`, 'info'); setBuffering(true);
        clearTimeout(retryTimer);
        retryTimer = setTimeout(() => { if (alive(gen)) startEngine(s.engines[s.ei], s.sources[s.si], gen); }, wait);
        return;
      }
      if (s.ei + 1 < s.engines.length) {
        s.ei++; s.retries = 0;
        setMsg('Trying ' + C.engineLabel(s.engines[s.ei]) + ' playback…', 'info');
        startEngine(s.engines[s.ei], s.sources[s.si], gen);
        return;
      }
      if (s.si + 1 < s.sources.length) {
        const from = s.si; s.si++;
        toast(`${(current && current.name) || 'Channel'}: switching to backup ${s.si} of ${s.sources.length - 1}`);
        runSource(gen);
        void from; return;
      }
      if (s.cycle < C.MAX_CYCLES) {
        s.cycle++;
        const wait = C.backoffMs(s.cycle + 2);
        setMsg(`Could not play, retrying in ${Math.round(wait / 1000)}s…`, 'info'); setBuffering(true);
        clearTimeout(retryTimer);
        retryTimer = setTimeout(() => { if (alive(gen)) { s.si = 0; runSource(gen); } }, wait);
        return;
      }
      teardown(); setBuffering(false);
      setMsg('Could not play this channel (' + reason + ').', 'error');
      toast('Playback failed: ' + ((current && current.name) || '') + ' (' + reason + ')', 'error');
      emit('error', { reason });
    }

    async function runSource(gen) {
      const s = S; if (!alive(gen)) return;
      const url = s.sources[s.si];
      s.retries = 0;
      let kind = C.classifyUrl(url);
      if (kind === 'unsupported') {
        const scheme = C.schemeOf(url);
        setMsg(`This stream type (${scheme}) is not supported. Desktop playback needs an http(s) stream.`, 'error');
        if (s.si + 1 < s.sources.length) { s.si++; return runSource(gen); }
        setBuffering(false); emit('error', { reason: 'unsupported scheme ' + scheme }); return;
      }
      setBuffering(true);
      if (kind === 'unknown') {
        setMsg('Detecting stream type…', 'info');
        kind = await sniff(url, gen);
        if (!alive(gen)) return;
      }
      setMsg('', '');
      s.engines = C.enginePlan(kind); s.ei = 0;
      startEngine(s.engines[0], url, gen);
    }

    async function sniff(url, gen) {
      if (!/^https?:/i.test(url)) return 'unknown';
      const ctl = new AbortController(); sniffCtl = ctl;
      const timer = setTimeout(() => ctl.abort(), 6000);
      try {
        const res = await fetch(url, { headers: { Range: 'bytes=0-2047' }, signal: ctl.signal });
        const reader = res.body.getReader(); const chunks = []; let n = 0;
        while (n < 1600) { const { done, value } = await reader.read(); if (done) break; chunks.push(value); n += value.length; }
        try { ctl.abort(); } catch { /* ignore */ }
        const u8 = new Uint8Array(n); let o = 0; for (const c of chunks) { u8.set(c, o); o += c.length; }
        return C.sniffBytes(u8, res.headers.get('content-type'));
      } catch { return 'unknown'; } finally { clearTimeout(timer); if (sniffCtl === ctl) sniffCtl = null; void gen; }
    }

    // ---- video element events ----
    video.addEventListener('playing', () => {
      if (!S) return;
      setBuffering(false); S.progress = true; S.stallAt = 0; setMsg('', '');
      bPlay.textContent = '⏸';
      if (!S.announced) { S.announced = true; emit('playing'); applyRemembered(); }
    });
    video.addEventListener('pause', () => { bPlay.textContent = '▶'; showUi(); });
    video.addEventListener('play', () => { bPlay.textContent = '⏸'; });
    video.addEventListener('waiting', () => { if (S) setBuffering(true); });
    video.addEventListener('stalled', () => { if (S && S.progress) setBuffering(true); });
    video.addEventListener('canplay', () => { if (S) setBuffering(false); });
    video.addEventListener('loadedmetadata', () => {
      if (S && S.startAtSec > 0 && isFinite(video.duration) && video.duration > S.startAtSec) { try { video.currentTime = S.startAtSec; } catch { /* ignore */ } }
      tracksChanged();
    });
    video.addEventListener('error', () => {
      if (!S || engine !== 'native') return; // hls.js/mpegts.js report through their own events
      onEngineFail(S.gen, (video.error && video.error.message) || 'media error', S.progress);
    });
    try {
      if (video.audioTracks) { video.audioTracks.addEventListener('addtrack', tracksChanged); }
      video.textTracks.addEventListener('addtrack', tracksChanged);
    } catch { /* ignore */ }

    // Stall watchdog: live stream whose clock stops advancing while it should be playing.
    let lastT = -1; let lastAdv = Date.now();
    setInterval(() => {
      if (!S || !S.live || !S.engine || video.paused || video.ended) { lastAdv = Date.now(); return; }
      if (Math.abs(video.currentTime - lastT) > 0.01) { lastT = video.currentTime; lastAdv = Date.now(); return; }
      if (Date.now() - lastAdv < C.STALL_MS) return;
      lastAdv = Date.now();
      setBuffering(true);
      if (S.progress) { onEngineFail(S.gen, 'stalled', true); }
      else if (Date.now() - S.startedAt > C.STARTUP_MS) { onEngineFail(S.gen, 'no data', false); }
    }, 1000);

    // ---- public control ----
    function stop() {
      S = null; genCounter++;
      teardown(); setBuffering(false); setMsg('', ''); closeMenu();
      emit('stopped');
    }
    function pause() { video.pause(); }
    function resume() { video.play().catch(onPlayReject); }

    function play(ch) {
      if (!ch || typeof ch.url !== 'string' || !ch.url) { setMsg('No stream url for this channel.', 'error'); current = ch || null; emit('error', { reason: 'no url' }); return; }
      teardown();
      current = ch;
      const gen = ++genCounter;
      const sources = C.buildSources(ch);
      const live = C.classifyUrl(ch.url) !== 'file' && !(Number(ch.startAtSec) > 0);
      S = { gen, sources, si: 0, engines: [], ei: 0, retries: 0, cycle: 0, progress: false, announced: false, live, startedAt: Date.now(), startAtSec: Number(ch.startAtSec) > 0 ? Number(ch.startAtSec) : 0, engine: null };
      lastAdv = Date.now(); lastT = -1;
      title.textContent = ch.name || ''; chName.textContent = ch.name || '';
      sub.textContent = ch.group || ''; desc.textContent = ''; chNow.textContent = '';
      sched.replaceChildren(); nowNext = null; closeMenu();
      setMsg('', ''); showUi();
      loadGuide(ch);
      runSource(gen);
    }

    function loadGuide(ch) {
      if (!ch.tvgId) return;
      win.nuvio.invoke('guide:schedule', ch.tvgId, Date.now() - 3600e3, Date.now() + 12 * 3600e3).then((list) => {
        if (current !== ch || !Array.isArray(list)) return;
        const now = Date.now();
        for (const p of list) {
          if (p.stop < now) continue;
          const row = el('div', 'item' + (p.start <= now ? ' now' : ''));
          row.append(el('div', 't', `${fmt(p.start)} – ${fmt(p.stop)}`), el('div', 'title', p.title || ''));
          sched.append(row);
          if (p.start <= now) { sub.textContent = `${ch.group || ''} · ${p.title}`; desc.textContent = p.desc || ''; }
        }
      }).catch(() => {});
      refreshNowNext();
    }
    function refreshNowNext() {
      const ch = current; if (!ch || !ch.tvgId) { nowNext = null; renderNow(); return; }
      win.nuvio.invoke('guide:nowNext', [ch.tvgId]).then((r) => { if (current === ch) { nowNext = (r && r[ch.tvgId]) || null; renderNow(); } }).catch(() => {});
    }
    function renderNow() {
      if (!nowNext || (!nowNext.now && !nowNext.next)) { chNow.textContent = ''; return; }
      const parts = [];
      if (nowNext.now) parts.push(`Now: ${nowNext.now.title || ''} (${fmt(nowNext.now.start)}–${fmt(nowNext.now.stop)})`);
      if (nowNext.next) parts.push(`Next: ${nowNext.next.title || ''} (${fmt(nowNext.next.start)})`);
      chNow.textContent = parts.join('   ·   ');
    }
    setInterval(() => { if (current) refreshNowNext(); }, 60000);

    // ---- tracks ----
    function getTracks() {
      const out = { audio: [], subs: [], subsActive: -1 };
      if (hls) {
        (hls.audioTracks || []).forEach((t, i) => out.audio.push({ id: i, label: t.name || t.lang || 'Track ' + (i + 1), lang: t.lang || '', active: hls.audioTrack === i }));
        (hls.subtitleTracks || []).forEach((t, i) => out.subs.push({ id: i, label: t.name || t.lang || 'Subtitle ' + (i + 1), lang: t.lang || '', active: hls.subtitleTrack === i }));
        out.subsActive = hls.subtitleTrack;
        return out;
      }
      try {
        const at = video.audioTracks;
        if (at && at.length > 1) for (let i = 0; i < at.length; i++) out.audio.push({ id: i, label: at[i].label || at[i].language || 'Track ' + (i + 1), lang: at[i].language || '', active: !!at[i].enabled });
      } catch { /* ignore */ }
      const tt = [...video.textTracks].filter((t) => t.kind === 'subtitles' || t.kind === 'captions');
      tt.forEach((t, i) => { out.subs.push({ id: i, label: t.label || t.language || 'Subtitle ' + (i + 1), lang: t.language || '', active: t.mode === 'showing' }); if (t.mode === 'showing') out.subsActive = i; });
      return out;
    }
    function setAudio(id, remember) {
      if (hls) hls.audioTrack = id;
      else { try { const at = video.audioTracks; for (let i = 0; i < at.length; i++) at[i].enabled = i === id; } catch { /* ignore */ } }
      if (remember) { const t = getTracks().audio.find((x) => x.id === id); if (t) remember1({ a: C.trackKey(t) }); }
    }
    function setSub(id, remember) {
      if (hls) { hls.subtitleDisplay = id >= 0; hls.subtitleTrack = id; }
      else { [...video.textTracks].filter((t) => t.kind === 'subtitles' || t.kind === 'captions').forEach((t, i) => { t.mode = i === id ? 'showing' : 'disabled'; }); }
      if (remember) {
        const t = id >= 0 ? getTracks().subs.find((x) => x.id === id) : null;
        remember1({ s: t ? C.trackKey(t) : 'off' });
      }
    }
    function remember1(patch) {
      if (!current || !current.url) return;
      tracksCfg.tracks = C.rememberTrack(tracksCfg.tracks, current.url, patch, C.TRACK_CAP);
      savePlayerCfg();
    }
    function applyRemembered() {
      if (!current) return;
      const mem = tracksCfg.tracks[current.url]; if (!mem) return;
      const t = getTracks();
      if (mem.a) { const i = C.pickTrack(t.audio, mem.a); if (i >= 0 && !t.audio.find((x) => x.id === i).active) setAudio(i, false); }
      if (mem.s === 'off') { if (t.subsActive >= 0) setSub(-1, false); }
      else if (mem.s) { const i = C.pickTrack(t.subs, mem.s); if (i >= 0 && !t.subs.find((x) => x.id === i).active) setSub(i, false); }
    }
    function tracksChanged() { if (S && S.announced) applyRemembered(); if (!menu.hidden) buildMenu(); }

    function buildMenu() {
      const t = getTracks();
      menu.replaceChildren();
      const section = (label, items, onPick, offItem) => {
        menu.append(el('div', 'pl-menu-h', label));
        if (!items.length && !offItem) { menu.append(el('div', 'pl-menu-empty', 'None available')); return; }
        if (offItem) { const b = el('button', 'pl-item' + (offItem.active ? ' on' : ''), offItem.label); b.type = 'button'; b.onclick = () => { onPick(-1); buildMenu(); }; menu.append(b); }
        for (const it of items) { const b = el('button', 'pl-item' + (it.active ? ' on' : ''), it.label + (it.lang && !it.label.toLowerCase().includes(it.lang.toLowerCase()) ? ` (${it.lang})` : '')); b.type = 'button'; b.onclick = () => { onPick(it.id); buildMenu(); }; menu.append(b); }
      };
      section('Audio', t.audio, (id) => setAudio(id, true));
      section('Subtitles', t.subs, (id) => setSub(id, true), t.subs.length ? { label: 'Off', active: t.subsActive < 0 } : null);
    }
    function toggleMenu() { if (menu.hidden) { buildMenu(); menu.hidden = false; showUi(); } else closeMenu(); }
    function closeMenu() { menu.hidden = true; }
    menu.addEventListener('click', (e) => e.stopPropagation());

    // ---- overlay visibility ----
    let hideTimer = null; let overUi = false;
    function showUi() {
      wrap.classList.remove('ui-hidden');
      clearTimeout(hideTimer);
      hideTimer = setTimeout(() => { if (!video.paused && menu.hidden && !overUi) wrap.classList.add('ui-hidden'); }, 3500);
    }
    wrap.addEventListener('mousemove', showUi);
    wrap.addEventListener('mousedown', showUi);
    bottom.addEventListener('mouseenter', () => { overUi = true; });
    bottom.addEventListener('mouseleave', () => { overUi = false; showUi(); });
    menu.addEventListener('mouseenter', () => { overUi = true; });
    menu.addEventListener('mouseleave', () => { overUi = false; showUi(); });
    wrap.addEventListener('dblclick', (e) => { if (e.target === video || e.target === msg || e.target === ui) toggleFullscreen(); });
    wrap.addEventListener('wheel', (e) => { if (document.fullscreenElement === wrap) { e.preventDefault(); adjustVolume(e.deltaY < 0 ? 0.05 : -0.05); } }, { passive: false });
    setInterval(() => { clock.textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }, 1000);
    clock.textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    document.addEventListener('fullscreenchange', () => {
      const fs = document.fullscreenElement === wrap;
      wrap.classList.toggle('fs', fs); bFull.textContent = fs ? '⤢' : '⛶'; closeMenu(); showUi();
    });

    const toggleFullscreen = () => { if (document.fullscreenElement) document.exitFullscreen(); else wrap.requestFullscreen().catch(() => {}); };
    function adjustVolume(d) { video.muted = false; video.volume = Math.min(1, Math.max(0, Math.round((video.volume + d) * 100) / 100)); showUi(); }
    function stepChannel(delta) { win.dispatchEvent(new CustomEvent('nuvio:channelstep', { detail: { delta } })); }

    // ---- keys ----
    // Capture phase so that, in fullscreen, the player owns Arrow/PageUp/PageDown/[ ] and the channel list does not also react.
    win.addEventListener('keydown', (e) => {
      const t = e.target;
      const typing = t && (t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable || (t.tagName === 'INPUT' && t.type !== 'range'));
      if (typing || e.ctrlKey || e.altKey || e.metaKey) return;
      const fs = document.fullscreenElement === wrap;
      const k = e.key;
      if (k === 'Escape' && !menu.hidden) { closeMenu(); e.stopPropagation(); return; }
      if (fs) {
        if (k === 'ArrowUp' || k === 'ArrowDown') { e.preventDefault(); e.stopImmediatePropagation(); adjustVolume(k === 'ArrowUp' ? 0.05 : -0.05); return; }
        if (k === ']' || k === 'PageDown') { e.preventDefault(); e.stopImmediatePropagation(); stepChannel(1); showUi(); return; }
        if (k === '[' || k === 'PageUp') { e.preventDefault(); e.stopImmediatePropagation(); stepChannel(-1); showUi(); return; }
        if (k === ' ') { e.preventDefault(); e.stopImmediatePropagation(); if (video.paused) resume(); else pause(); return; }
        if (k === 't' || k === 'T') { toggleMenu(); return; }
        showUi();
      }
      if (k === 'f' || k === 'F') toggleFullscreen();
      else if (k === 'm' || k === 'M') video.muted = !video.muted;
    }, true);

    win.NuvioPlayer = {
      play, stop, pause, resume, current: () => current, toggleFullscreen, video: () => video,
      _debug: () => ({ engine, session: S && { si: S.si, ei: S.ei, engines: S.engines, sources: S.sources, retries: S.retries, progress: S.progress }, tracks: tracksCfg.tracks }),
    };
    syncVol();
  }

  // ------------------------------------------------------------------------------------------------
  // Pure logic (no DOM): exported for node:test.
  function createCore() {
    const MAX_RETRIES = 3; const MAX_CYCLES = 2; const STALL_MS = 8000; const STARTUP_MS = 20000; const TRACK_CAP = 500;
    const UNSUPPORTED = /^(rtmps?|rtsp|rtp|udp|mms|mmsh|srt):/i;

    function schemeOf(url) { const m = /^([a-z][a-z0-9+.-]*):/i.exec(String(url || '')); return m ? m[1].toLowerCase() : ''; }

    // 'hls' | 'ts' | 'file' | 'unknown' | 'unsupported'
    function classifyUrl(url) {
      const u = String(url || '').trim();
      if (!u) return 'unsupported';
      if (UNSUPPORTED.test(u)) return 'unsupported';
      const scheme = schemeOf(u);
      if (scheme && !['http', 'https', 'file', 'blob', 'data'].includes(scheme)) return 'unsupported';
      const path = u.split('#')[0].split('?')[0].toLowerCase();
      if (/\.m3u8?$/.test(path)) return 'hls';
      if (/\.(ts|mts|m2ts)$/.test(path)) return 'ts';
      if (/\.(mp4|m4v|webm|mov|mkv|ogv)$/.test(path)) return 'file';
      return 'unknown';
    }

    // Identify a stream from its first bytes (Uint8Array/array) and optional content-type.
    function sniffBytes(bytes, contentType) {
      const b = bytes || [];
      const n = b.length;
      let i = 0;
      if (n >= 3 && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf) i = 3; // BOM
      while (i < n && (b[i] === 0x20 || b[i] === 0x0a || b[i] === 0x0d || b[i] === 0x09)) i++;
      if (n - i >= 7 && String.fromCharCode(b[i], b[i + 1], b[i + 2], b[i + 3], b[i + 4], b[i + 5], b[i + 6]) === '#EXTM3U') return 'hls';
      if (n >= 3 && b[0] === 0x47) {
        // sync byte every 188 (or 192 for m2ts with timecode) bytes; accept short buffers
        const ok = (step, off) => { let seen = 0; for (let p = off; p < n; p += step) { if (b[p] !== 0x47) return false; seen++; } return seen >= 2; };
        if (n < 189 || ok(188, 0)) return 'ts';
      }
      if (n >= 8 && b[4] === 0x66 && b[5] === 0x74 && b[6] === 0x79 && b[7] === 0x70) return 'file'; // ftyp
      if (n >= 4 && b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3) return 'file'; // EBML (webm/mkv)
      if (n >= 192 && b[4] === 0x47 && b[196] === 0x47) return 'ts'; // m2ts
      const ct = String(contentType || '').toLowerCase();
      if (/mpegurl/.test(ct)) return 'hls';
      if (/mp2t|mpeg-?ts/.test(ct)) return 'ts';
      if (/^video\/(mp4|webm)/.test(ct)) return 'file';
      return 'unknown';
    }

    // Engines to try, in order, for a stream kind (HLS<->TS fallback like the Android player).
    function enginePlan(kind) {
      switch (kind) {
        case 'hls': return ['hls', 'mpegts'];
        case 'ts': return ['mpegts', 'hls'];
        case 'file': return ['native'];
        default: return ['hls', 'mpegts', 'native'];
      }
    }
    const engineLabel = (e) => ({ hls: 'HLS', mpegts: 'MPEG-TS', native: 'native' }[e] || e);

    // Exponential backoff (attempt starts at 1): 1s, 2s, 4s ... capped. rnd in [0,1) adds up to 20% jitter when given.
    function backoffMs(attempt, base = 1000, max = 15000, rnd) {
      const a = Math.max(1, attempt | 0);
      const v = Math.min(max, base * 2 ** (a - 1));
      return Math.round(rnd == null ? v : v * (1 + 0.2 * rnd));
    }

    // Main url followed by unique, valid backups.
    function buildSources(ch) {
      const out = []; const seen = new Set();
      const add = (u) => { if (typeof u === 'string' && u.trim() && !seen.has(u.trim())) { seen.add(u.trim()); out.push(u.trim()); } };
      if (ch) { add(ch.url); if (Array.isArray(ch.backups)) ch.backups.forEach(add); }
      return out;
    }
    // Index of the next source to try after `idx`, or -1.
    function nextSource(sources, idx) { return idx + 1 < sources.length ? idx + 1 : -1; }

    // Track memory: { [channelUrl]: { a?: audioKey, s?: subKey|'off' } }, insertion ordered, capped (oldest dropped).
    const trackKey = (t) => String((t && (t.lang || t.label)) || '').toLowerCase();
    function rememberTrack(map, url, patch, cap = TRACK_CAP) {
      const out = { ...(map || {}) };
      const prev = out[url] || {};
      delete out[url];
      out[url] = { ...prev, ...patch };
      const keys = Object.keys(out);
      for (let i = 0; i < keys.length - cap; i++) delete out[keys[i]];
      return out;
    }
    // Index (by item.id) of the track matching a remembered key, or -1.
    function pickTrack(list, key) {
      if (!key) return -1;
      const k = String(key).toLowerCase();
      const hit = (list || []).find((t) => trackKey(t) === k) || (list || []).find((t) => String(t.label || '').toLowerCase() === k);
      return hit ? hit.id : -1;
    }

    return { MAX_RETRIES, MAX_CYCLES, STALL_MS, STARTUP_MS, TRACK_CAP, schemeOf, classifyUrl, sniffBytes, enginePlan, engineLabel, backoffMs, buildSources, nextSource, trackKey, rememberTrack, pickTrack };
  }
})(typeof window !== 'undefined' ? window : globalThis);
