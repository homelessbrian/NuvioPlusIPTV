# Nuvio + IPTV for Windows (preview)

A Windows desktop app (Electron) that brings the Live TV side of Nuvio + IPTV to the PC, in the same dark Nuvio look (Inter font, Nuvio palette, left side menu).

## What works
- **Sources:** M3U/M3U8 playlist (URL or file), **Xtream Codes** login (live channels, the account's XMLTV guide, movies and series), XMLTV guide (URL or file, `.xml` / `.xml.gz`).
- **Live TV:** groups, favorites, recents, hide/show channels, search, now/next, keyboard control.
- **Guide:** TiviMate-style grid backed by a local SQLite store (about 7 days, imported in seconds, searchable), smooth with 25,000+ channels; record / remind / record-series from any programme.
- **Player:** HLS, raw MPEG-TS (mpegts.js) and MP4; format sniffing for extensionless URLs, retry with backoff, automatic backup-channel switching, audio and subtitle tracks (remembered per channel), fullscreen info bar, channel up/down.
- **On Demand:** movies and series from your Xtream account (categories, search, details, seasons and episodes).
- **Sports:** ESPN scores and schedules for 200+ leagues, follow teams/leagues/names, **channels showing each game** found through your guide and channel names (exact-phrase matching), hide scores, league tables.
- **Recordings:** record now or on a schedule with the bundled ffmpeg (stream copy), series rules, reminders as Windows notifications.
- **Settings:** stored encrypted for your Windows account (DPAPI); export/import backup JSON.

## Not ported yet (see `docs/` and `../plan.md`)
Catch-up playback UI (the Xtream catch-up URL builder exists), sports reminders/alerts/score overlay/pick-your-teams flow, non-ESPN sports feeds (HockeyTech, MiLB, etc.), Nuvio account sync and Google Drive backup, VPN, parental controls, auto-update, code signing (SmartScreen will warn), rtmp/rtsp/udp streams.

## Build
```
cd desktop
npm ci
npm test          # unit tests + end-to-end runs of the real app against local fixture servers
npm run dist      # dist/Nuvio-IPTV-Setup-<version>.exe
```
CI (`.github/workflows/desktop-windows.yml`) builds the installer on a Windows runner, installs it silently, launches it and uploads it as an artifact.

## Notes
- The app answers HTTP(S) responses with `Access-Control-Allow-Origin: *` so the player can read streams from IPTV servers without CORS headers. The renderer is sandboxed with `contextIsolation` and a CSP.
- See `docs/CONTRACTS.md` for the module architecture and `docs/guide.md` for the guide store.
