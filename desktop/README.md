# Nuvio + IPTV for Windows (early preview)

A Windows desktop app (Electron) for the Live TV side of Nuvio + IPTV. It is **not** a port of the whole Android/TV app yet; see `../plan.md` for the roadmap.

## What works
- Import an M3U/M3U8 playlist from a URL or a local file (groups, logos, `tvg-id`).
- Load an XMLTV guide from a URL or local file (`.xml` or `.xml.gz`), streamed so large guides don't fill memory; only the next ~48 h are kept.
- Browse groups, search (`/`), keyboard navigation, now/next in the list and a per-channel schedule.
- Play HLS (`.m3u8`) and MP4-style streams in the app.
- Settings persist per Windows user and are encrypted with Windows DPAPI.

## Not yet
Raw MPEG-TS streams, rtmp/rtsp/udp, Sports, VOD, recording, catch-up, VPN, account sync, Google Drive backup, auto-update. The installer is unsigned, so Windows SmartScreen will warn.

## Build
```
cd desktop
npm ci
npm test          # parser tests + an end-to-end run of the real app against a local fixture server
npm run dist      # dist/Nuvio-IPTV-Setup-<version>.exe
```
CI: `.github/workflows/desktop-windows.yml` builds the installer on a Windows runner, installs it silently, launches it, and uploads it as an artifact.

## Notes
- The app answers HTTP(S) requests with `Access-Control-Allow-Origin: *` so the player can read HLS playlists from IPTV servers that don't send CORS headers. Content is restricted by a CSP, with `contextIsolation` and a sandboxed renderer.
